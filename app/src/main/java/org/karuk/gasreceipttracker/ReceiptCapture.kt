package org.karuk.gasreceipttracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.view.MotionEvent
import android.view.View
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/** Signature is saved privately, not uploaded. The user must explicitly sign. */
class SignaturePad(context: Context) : View(context) {
    private val strokes = mutableListOf<Path>()
    private var current: Path? = null
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK; strokeWidth = 5f; style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    init { setBackgroundColor(Color.WHITE); minimumHeight = 240 }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (190 * resources.displayMetrics.density).toInt())
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); current = Path().also { it.moveTo(event.x, event.y); strokes.add(it) }; invalidate(); return true }
            MotionEvent.ACTION_MOVE -> { current?.lineTo(event.x, event.y); invalidate(); return true }
            MotionEvent.ACTION_UP -> { parent?.requestDisallowInterceptTouchEvent(false); performClick(); current?.lineTo(event.x, event.y); current = null; invalidate(); return true }
        }
        return true
    }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); strokes.forEach { canvas.drawPath(it, ink) } }
    override fun performClick():Boolean { super.performClick(); return true }
    fun clear() { strokes.clear(); invalidate() }
    fun hasSignature() = strokes.isNotEmpty()
    fun save(context: Context): File {
        require(hasSignature()) { "Signature is blank" }
        val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE); strokes.forEach { canvas.drawPath(it, ink) }
        val output = File(context.filesDir, "reimbursement_signature.png")
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return output
    }
}

data class FuelScan(
    val station: String, val date: String, val gallons: String, val price: String, val total: String,
    val rawText: String, val fuelType: String = "", val cardLast4: String = "", val time: String = "",
    val warnings: List<String> = emptyList()
)

/** OCR reads locally. The driver reviews extracted values before saving. */
object ReceiptReader {
    private fun quality(s: FuelScan): Int =
        listOf(s.gallons, s.price, s.total).count { it.isNotBlank() } * 10 +
            listOf(s.date, s.station).count { it.isNotBlank() } - s.warnings.size

    private fun contrastEnhanced(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) return null
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 2200) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val source = context.contentResolver.openInputStream(uri).use { input ->
            if (input == null) return null
            BitmapFactory.decodeStream(input, null, opts)
        } ?: return null
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val contrast = 1.65f
        val offset = -70f
        val matrix = android.graphics.ColorMatrix(floatArrayOf(
            contrast, 0f, 0f, 0f, offset,
            0f, contrast, 0f, 0f, offset,
            0f, 0f, contrast, 0f, offset,
            0f, 0f, 0f, 1f, 0f
        ))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
        }
        Canvas(output).drawBitmap(source, 0f, 0f, paint)
        source.recycle()
        return output
    }

    fun scan(context: Context, uri: Uri, done: (FuelScan?) -> Unit) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        fun finish(result: FuelScan?) { recognizer.close(); done(result) }
        try {
            val image = InputImage.fromFilePath(context, uri)
            recognizer.process(image).addOnSuccessListener { text ->
                val first = ReceiptTextParser.parse(text.text)
                if (first.gallons.isNotBlank() && first.price.isNotBlank() && first.total.isNotBlank()) {
                    finish(first)
                    return@addOnSuccessListener
                }
                try {
                    val enhanced = contrastEnhanced(context, uri)
                    if (enhanced == null) { finish(first); return@addOnSuccessListener }
                    recognizer.process(InputImage.fromBitmap(enhanced, 0)).addOnSuccessListener { extra ->
                        val second = ReceiptTextParser.parse(extra.text)
                        // Labels can be legible in different contrast passes.
                        // Combine the source OCR strings to recover all printed fields.
                        val combined = ReceiptTextParser.parse(first.rawText + "\n" + extra.text)
                        enhanced.recycle()
                        finish(listOf(first,second,combined).maxByOrNull { quality(it) } ?: first)
                    }.addOnFailureListener {
                        enhanced.recycle()
                        finish(first)
                    }
                } catch (_: Exception) { finish(first) }
            }.addOnFailureListener { finish(null) }
        } catch (_: Exception) { finish(null) }
    }
}
