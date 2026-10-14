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

data class FuelScan(val station:String, val date:String, val gallons:String, val price:String, val total:String,
    val rawText:String, val cardLast4:String="", val receiptTime:String="", val warnings:List<String> = emptyList(), val scanId:String="")

/** One on-device recognition pass. Aligns columns by their image coordinates. */
object ReceiptReader {
    fun scan(context:Context, uri:Uri, done:(FuelScan?)->Unit) {
        try {
            val image=InputImage.fromFilePath(context,uri)
            val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val prefs=LedgerStore.prefs(context)
            val state=org.json.JSONObject(prefs.getString(RequestWorkflow.STATE,"{}").orEmpty())
            val requestId=state.optString("activeId")
            recognizer.process(image).addOnSuccessListener { result ->
                try {
                    val positioned=result.textBlocks.flatMap{it.lines}.mapNotNull{l->l.boundingBox?.let{b->
                        FuelReceiptParser.Line(l.text,b.left.toFloat(),b.top.toFloat(),b.width().toFloat(),b.height().toFloat())}}
                    val parsed=FuelReceiptParser.parse(result.text,positioned)
                    val record=parsed.json().put("image",uri.toString()).put("requestId",requestId).put("scannedAt",RequestWorkflow.stamp())
                    val id=LedgerStore.rememberScan(context,record)
                    done(FuelScan(parsed.station,parsed.date,parsed.gallons,parsed.price,parsed.total,parsed.raw,
                        parsed.cardLast4,parsed.time,parsed.warnings,id))
                } catch(e:Exception) { android.util.Log.e("ReceiptReader","Scan could not be saved",e); done(null) }
                finally { recognizer.close() }
            }.addOnFailureListener { e ->
                recognizer.close()
                runCatching { LedgerStore.rememberScan(context,org.json.JSONObject().put("image",uri.toString())
                    .put("requestId",requestId).put("error",e.message).put("scannedAt",RequestWorkflow.stamp())) }
                done(null)
            }
        } catch(e:Exception) { done(null) }
    }
}
