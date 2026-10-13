package org.karuk.gasreceipttracker

import android.content.Context
import android.graphics.Bitmap
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
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), 260)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { current = Path().also { it.moveTo(event.x, event.y); strokes.add(it) }; invalidate(); return true }
            MotionEvent.ACTION_MOVE -> { current?.lineTo(event.x, event.y); invalidate(); return true }
            MotionEvent.ACTION_UP -> { current?.lineTo(event.x, event.y); current = null; invalidate(); return true }
        }
        return true
    }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); strokes.forEach { canvas.drawPath(it, ink) } }
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

data class FuelScan(val station: String, val date: String, val gallons: String, val price: String, val total: String, val rawText: String)

/** OCR is a suggestion only. The driver must verify extracted values. */
object ReceiptReader {
    private val amount = Regex("""(?:\$\s*)?(\d{1,5}[.,]\d{2})""")
    private val gallons = Regex("""(\d{1,4}[.,]\d{3})\s*(?:GAL|GALLONS|GALS)""", RegexOption.IGNORE_CASE)
    private val gallonsAfter = Regex("""(?:GAL|GALLONS|GALS)\s*[:#]?\s*(\d{1,4}[.,]\d{3})""", RegexOption.IGNORE_CASE)
    private val date = Regex("""\b\d{1,2}[/\-]\d{1,2}[/\-]\d{2,4}\b""")
    fun scan(context: Context, uri: Uri, done: (FuelScan?) -> Unit) {
        try {
            val image = InputImage.fromFilePath(context, uri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            recognizer.process(image).addOnSuccessListener { result ->
                val lines = result.textBlocks.flatMap { it.lines }.map { it.text.trim() }.filter { it.isNotEmpty() }
                val totalLine = lines.lastOrNull { it.contains("TOTAL", true) || it.contains("AMOUNT", true) || it.contains("SALE", true) }
                val total = totalLine?.let { amount.findAll(it).lastOrNull()?.groupValues?.get(1) } ?: ""
                val gallonLine = lines.firstOrNull { it.contains("GAL", true) }
                val qty = gallonLine?.let { gallons.find(it)?.groupValues?.get(1) ?: gallonsAfter.find(it)?.groupValues?.get(1) } ?: ""
                val unitPrice = lines.firstNotNullOfOrNull { line ->
                    Regex("""(?:PRICE|/GAL|P/G|UNIT)\s*[:\$]?\s*(\d{1,2}[.,]\d{3})""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)
                } ?: ""
                done(FuelScan(lines.firstOrNull().orEmpty(), date.find(result.text)?.value.orEmpty(),
                    qty, unitPrice, total, result.text))
                recognizer.close()
            }.addOnFailureListener { recognizer.close(); done(null) }
        } catch (_: Exception) { done(null) }
    }
}
