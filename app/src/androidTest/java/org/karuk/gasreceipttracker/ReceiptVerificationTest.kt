package org.karuk.gasreceipttracker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.view.MotionEvent
import android.os.SystemClock
import android.widget.Spinner
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReceiptVerificationTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val prefs get()=context.getSharedPreferences("usage_data",Context.MODE_PRIVATE)
    private var activity:ReceiptReviewActivity?=null

    @Before fun before(){prefs.edit().clear().commit();context.deleteDatabase("fuel_records.db")}
    @After fun after(){
        activity?.let{instrumentation.runOnMainSync{it.finish()}}
        activity=null;instrumentation.waitForIdleSync()
        prefs.edit().clear().commit();context.deleteDatabase("fuel_records.db")
    }
    @Test fun matchingCardLastFourAndBrandNeedsNoAcknowledgment(){
        val result=ReceiptCardVerifier.verify("1234","Visa","1234","Visa")
        assertEquals("MATCH",result.status);assertFalse(result.mismatch)
    }
    @Test fun differentLastFourIsAlwaysFlagged(){
        val result=ReceiptCardVerifier.verify("1234","Visa","5678","Visa")
        assertEquals("MISMATCH",result.status);assertTrue(result.mismatch)
        assertTrue(result.message.contains("5678"));assertTrue(result.message.contains("1234"))
    }
    @Test fun differentCardBrandAlsoWarns(){
        assertTrue(ReceiptCardVerifier.verify("1234","Visa","1234","Mastercard").mismatch)
    }
    @Test fun absentPrintedCardNumberIsNotAssumedToMatch(){
        val result=ReceiptCardVerifier.verify("1234","Visa","","")
        assertEquals("UNVERIFIED",result.status);assertFalse(result.mismatch)
    }
    @Test fun manuallyCorrectedPrintedDigitsCanResolveMismatch(){
        assertTrue(ReceiptCardVerifier.verify("1234","Visa","5678","Visa").mismatch)
        assertFalse(ReceiptCardVerifier.verify("1234","Visa","1234","Visa").mismatch)
        assertEquals("1234",ReceiptPrivacy.last4("1234"))
        assertEquals("",ReceiptPrivacy.last4("4111111111111111"))
    }
    @Test fun rawOcrRedactsUnmaskedFullCardNumbers(){
        val redacted=ReceiptPrivacy.redact("VISA 4111111111111111\nVISA XXXX5678")
        assertFalse(redacted.contains("4111111111111111"))
        assertTrue(redacted.contains("XXXX5678"))
    }
    private fun box(key:String,text:String,x:Float,y:Float):ReceiptEvidence =
        ReceiptEvidence(key,text,x,y,x+.11f,y+.028f)
    @Test fun matchingBoxesFindGallonsPriceFuelTotalAndMaskedCard(){
        val scan=ReceiptTextParser.parse("""
            Legend 295 Inc Chevr
            10/07/2026
            XXXXXXXXXXXX5678
            VISA
            UNLEAD REG 8.765G
            PRICE/GAL $6.9993
            FUEL TOTAL $61.35
            TOTAL = $61.35
        """.trimIndent())
        val samples=listOf(
            "Legend 295 Inc Chevr", "10/07/2026", "XXXXXXXXXXXX5678", "VISA",
            "UNLEAD REG 8.765G","PRICE/GAL $6.9993","FUEL TOTAL $61.35","TOTAL = $61.35")
        val lines=samples.mapIndexed{i,line->
            val region=box("",line,.1f,.06f+i*.095f)
            val word=when(i){4->"8.765G";5->"$6.9993";6->"$61.35";7->"$61.35";else->line}
            ReceiptOcrLine(line,region,listOf(ReceiptOcrWord(word,region)))
        }
        val matches=ReceiptEvidenceMatcher.locate(scan,lines).associateBy{it.field}
        assertNotNull(matches["gallons"]);assertNotNull(matches["price"])
        assertNotNull(matches["cardLast4"]);assertNotNull(matches["cardType"])
        assertNotNull(matches["station"])
        assertEquals("FUEL TOTAL $61.35",matches["total"]?.recognized)
        assertEquals(.63f,matches["total"]!!.top,.0001f)
    }
    @Test fun noMatchingOCRWordDoesNotInventHighlight(){
        val scan=FuelScan("Chevron","10/07/2026","8.765","6.9993","61.35","")
        val line=ReceiptOcrLine("PRICE/GAL $8.1111",
            box("","",.15f,.2f),
            listOf(ReceiptOcrWord("$8.1111",box("","",.15f,.2f))))
        val marks=ReceiptEvidenceMatcher.locate(scan,listOf(line))
        assertTrue(marks.none{it.field=="price"||it.field=="total"})
    }
    private fun makePhoto():File{
        val b=Bitmap.createBitmap(720,1100,Bitmap.Config.ARGB_8888)
        val c=Canvas(b);c.drawColor(Color.WHITE)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=34f}
        listOf("CHEVRON", "10/07/2026", "VISA XXXX5678",
            "UNLEAD REG 8.765G","PRICE/GAL $6.9993","FUEL TOTAL $61.35")
            .forEachIndexed{i,s->c.drawText(s,30f,160f+i*118,p)}
        val file=File(context.filesDir,"receipt_1122334455667.jpg")
        file.outputStream().use{b.compress(Bitmap.CompressFormat.JPEG,92,it)}
        b.recycle()
        return file
    }
    private fun sampleEvidence():String {
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=34f}
        fun region(field:String,row:Int,prefix:String,matched:String,full:String):ReceiptEvidence {
            val x=30f+paint.measureText(prefix)
            val base=160f+row*118f
            return ReceiptEvidence(field,full,x/720f,(base-34f)/1100f,
                (x+paint.measureText(matched))/720f,(base+6f)/1100f)
        }
        return ReceiptEvidence.asJson(listOf(
            region("station",0,"","CHEVRON","CHEVRON"),
            region("date",1,"","10/07/2026","10/07/2026"),
            region("cardType",2,"","VISA","VISA XXXX5678"),
            region("cardLast4",2,"VISA XXXX","5678","VISA XXXX5678"),
            region("gallons",3,"UNLEAD REG ","8.765G","UNLEAD REG 8.765G"),
            region("price",4,"PRICE/GAL $","6.9993","PRICE/GAL $6.9993"),
            region("total",5,"FUEL TOTAL $","61.35","FUEL TOTAL $61.35")
        )).toString()
    }
    private fun seed():String {
        prefs.edit().putString("driver","Test User").putString("cardBrand","Visa")
            .putString("cardLast4","1234").putString("vehicle","309")
            .putString("trips","[]").putString("receipts","[]")
            .putString("pending_receipt",Uri.fromFile(makePhoto()).toString())
            .putString("ocr_station","Chevron").putString("ocr_date","10/07/2026")
            .putString("ocr_gallons","8.765").putString("ocr_price","6.9993")
            .putString("ocr_total","61.35").putString("ocr_card_type","Visa")
            .putString("ocr_card_last4","5678").putString("ocr_evidence",sampleEvidence()).commit()
        return RequestWorkflow(context).also{it.initialize()}.current().getString("id")
    }
    private fun all(view:View):List<View> =
        listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap{all(view.getChildAt(it))}else emptyList()
    private fun findButton(caption:String):Button{
        val a=activity?:error("Activity missing")
        return all(a.window.decorView).filterIsInstance<Button>().first{it.text.toString()==caption}
    }
    private fun screenshot(name:String){
        instrumentation.waitForIdleSync()
        Thread.sleep(650)
        instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->
            val dir=context.getExternalFilesDir("verification")!!.apply{mkdirs()}
            File(dir,name+".png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }
    @Test fun highlightedReceiptLetsDriverCycleCorrectAndConfirmDifferentCard(){
        val id=seed()
        val a=instrumentation.startActivitySync(Intent(context,ReceiptReviewActivity::class.java)
            .putExtra(ReceiptReviewActivity.EXTRA_REQUEST_ID,id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReceiptReviewActivity
        activity=a;instrumentation.waitForIdleSync()
        val overlay=all(a.window.decorView).filterIsInstance<ReceiptHighlightView>().single()
        assertEquals("gallons",overlay.activeField)
        assertTrue(overlay.hasHighlight("price"))
        assertNotNull(overlay.selectedRect())
        screenshot("UI_Receipt_Highlight")
        instrumentation.runOnMainSync{
            all(a.window.decorView).filterIsInstance<Spinner>().single().setSelection(5)
        }
        instrumentation.waitForIdleSync()
        assertEquals("price",overlay.activeField)
        assertEquals("Price per gallon",all(a.window.decorView)
            .filterIsInstance<Spinner>().single().selectedItem.toString())
        instrumentation.runOnMainSync{
            all(a.window.decorView).filterIsInstance<EditText>().first().setText("6.9993")
            findButton("Save checked fields and return").performClick()
        }
        instrumentation.waitForIdleSync()
        assertFalse("Mismatch must not save without acknowledgment",a.isFinishing)
        screenshot("UI_Card_Mismatch")
        instrumentation.runOnMainSync{
            val ok=all(a.window.decorView).filterIsInstance<Button>().firstOrNull{it.text.toString()=="Review card"}
            ok?.performClick()
            all(a.window.decorView).filterIsInstance<CheckBox>().first().isChecked=true
            findButton("Save checked fields and return").performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals("MISMATCH_ACKNOWLEDGED",prefs.getString("ocr_card_verification",""))
        assertEquals("true",prefs.getString("ocr_card_mismatch_ack",""))
        assertEquals("1234",prefs.getString("cardLast4",""))
        assertEquals("5678",prefs.getString("ocr_card_last4",""))
    }
    @Test fun wrongHighlightTapMustNotSwitchPriceToGallonsOrChangeItsValue(){
        val id=seed()
        prefs.edit().putString("cardLast4","5678").commit()
        val a=instrumentation.startActivitySync(Intent(context,ReceiptReviewActivity::class.java)
            .putExtra(ReceiptReviewActivity.EXTRA_REQUEST_ID,id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReceiptReviewActivity
        activity=a;instrumentation.waitForIdleSync()
        val overlay=all(a.window.decorView).filterIsInstance<ReceiptHighlightView>().single()
        val spinner=all(a.window.decorView).filterIsInstance<Spinner>().single()
        instrumentation.runOnMainSync{spinner.setSelection(5)}
        instrumentation.waitForIdleSync()
        assertEquals("price",overlay.activeField)
        val gallon=ReceiptEvidence.fromArray(prefs.getString("ocr_evidence","[]")!!)
            .first{it.field=="gallons"}
        instrumentation.runOnMainSync{
            val x=(gallon.left+gallon.right)*overlay.width/2f
            val y=(gallon.top+gallon.bottom)*overlay.height/2f
            val tick=SystemClock.uptimeMillis()
            val down=MotionEvent.obtain(tick,tick,MotionEvent.ACTION_DOWN,x,y,0)
            val up=MotionEvent.obtain(tick,tick+50,MotionEvent.ACTION_UP,x,y,0)
            overlay.dispatchTouchEvent(down)
            overlay.dispatchTouchEvent(up)
            down.recycle();up.recycle()
        }
        instrumentation.waitForIdleSync()
        assertEquals("Tapped gallons must not change selected field","price",overlay.activeField)
        val text=all(a.window.decorView).filterIsInstance<EditText>().single()
        assertEquals("6.9993",text.text.toString())
        instrumentation.runOnMainSync{
            text.setText("5.9993")
            findButton("Save checked fields and return").performClick()
        }
        instrumentation.waitForIdleSync()
        assertEquals("5.9993",prefs.getString("ocr_price",""))
        assertEquals("8.765",prefs.getString("ocr_gallons",""))
    }

    @Test fun reviewShowsOneEditableFieldAndExplicitSelector(){
        val id=seed()
        val a=instrumentation.startActivitySync(Intent(context,ReceiptReviewActivity::class.java)
            .putExtra(ReceiptReviewActivity.EXTRA_REQUEST_ID,id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReceiptReviewActivity
        activity=a;instrumentation.waitForIdleSync()
        val spinner=all(a.window.decorView).filterIsInstance<Spinner>().single()
        assertEquals(10,spinner.adapter.count)
        assertEquals(1,all(a.window.decorView).filterIsInstance<EditText>().count{it.isEnabled})
        instrumentation.runOnMainSync{spinner.setSelection(5)}
        instrumentation.waitForIdleSync()
        assertEquals("Price per gallon",spinner.selectedItem.toString())
        assertEquals("6.9993",all(a.window.decorView).filterIsInstance<EditText>().single().text.toString())
    }

    @Test fun savedReceiptCanBeRecheckedWithOriginalHighlightsReadOnly(){
        seed()
        val record=JSONObject().put("image",prefs.getString("pending_receipt",""))
            .put("station","Chevron").put("date","10/07/2026")
            .put("gallons","8.765").put("price","6.9993")
            .put("cost","61.35").put("cardType","Visa")
            .put("receiptCardLast4","5678").put("cardVerificationStatus","MISMATCH_ACKNOWLEDGED")
            .put("profileCardLast4AtReview","1234")
            .put("profileCardBrandAtReview","Visa")
            .put("ocrEvidence",JSONArray(sampleEvidence()))
        val a=instrumentation.startActivitySync(Intent(context,ReceiptReviewActivity::class.java)
            .putExtra(ReceiptReviewActivity.EXTRA_HISTORY,true)
            .putExtra(ReceiptReviewActivity.EXTRA_RECEIPT,record.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ReceiptReviewActivity
        activity=a;instrumentation.waitForIdleSync()
        val overlay=all(a.window.decorView).filterIsInstance<ReceiptHighlightView>().single()
        assertTrue(overlay.hasHighlight("cardLast4"))
        assertEquals("cardLast4",overlay.activeField)
        assertFalse(all(a.window.decorView).filterIsInstance<EditText>().first().isEnabled)
        assertTrue(all(a.window.decorView).filterIsInstance<TextView>()
            .any{it.text.toString().contains("MISMATCH_ACKNOWLEDGED")})
        screenshot("UI_Saved_Card_Verification")
    }
}