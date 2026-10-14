package org.karuk.gasreceipttracker

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
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
class WorkflowTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=ins.targetContext
    private val prefs get()=ctx.getSharedPreferences("usage_data",Context.MODE_PRIVATE)
    private var activity:MainActivity?=null
    private val out get()=ctx.getExternalFilesDir("verification")!!.apply{mkdirs()}
    private fun receipt():JSONObject{
        val file=File(ctx.filesDir,"receipt_1111111111111.jpg")
        val b=Bitmap.createBitmap(300,500,Bitmap.Config.ARGB_8888)
        val c=Canvas(b);c.drawColor(Color.WHITE)
        c.drawText("TEST RECEIPT",20f,80f,Paint().apply{color=Color.BLACK;textSize=24f})
        file.outputStream().use{b.compress(Bitmap.CompressFormat.JPEG,90,it)};b.recycle()
        return JSONObject().put("date","10/8/2026").put("station","SAMPLE STATION").put("vehicle","309")
            .put("gallons","8.765").put("price","6.999").put("cost","61.35").put("odometer","9750")
            .put("image",android.net.Uri.fromFile(file).toString())
    }
    private fun trip(open:Boolean=false):JSONObject=JSONObject().put("driver","Test Driver").put("vehicle","309")
        .put("department","TEST").put("startDate","October 8, 2026").put("startOdometer","9700")
        .put("destination","Happy Camp").put("purpose","Test travel").apply{if(!open)put("endDate","October 8, 2026").put("endOdometer","9750")}
    private fun seed(withReceipt:Boolean=true,open:Boolean=false){
        prefs.edit().putBoolean("setupDone",true).putString("driver","Test Driver").putString("department","TEST")
            .putString("vehicle","309").putString("vehicles","309,310").putString("cardLast4","1234")
            .putString("myEmail","tester@example.com").putString("emailTo","finance@example.com")
            .putString("emailCc","copy@example.com").putBoolean("ccSelf",true)
            .putString("trips",JSONArray().put(trip(open)).toString())
            .putString("receipts",if(withReceipt)JSONArray().put(receipt()).toString()else "[]").commit()
    }
    private fun sign(w:RequestWorkflow):File{
        val file=File(ctx.cacheDir,"test_signature_workflow.png")
        val b=Bitmap.createBitmap(400,100,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.WHITE)
        c.drawText("TEST SIGNATURE",10f,65f,Paint().apply{color=Color.BLACK;textSize=36f})
        file.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle();return w.saveSignature(file)
    }
    @Before fun reset(){prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db") }
    @After fun finish(){activity?.let{ins.runOnMainSync{it.finish()}};activity=null;ins.waitForIdleSync();prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db")}
    @Test fun migrationKeepsExistingTripsReceiptAndProfile(){
        seed();val w=RequestWorkflow(ctx);w.initialize()
        assertEquals(1,w.all().length());assertEquals("PR-00001",w.current().getString("reference"))
        assertEquals(1,JSONArray(prefs.getString("trips","[]")).length())
        assertEquals("61.35",w.current().getJSONArray("receipts").getJSONObject(0).getString("cost"))
        w.initialize();assertEquals(1,w.all().length())
    }
    @Test fun newTravelCreatesCleanPrWithoutLosingUnsentWork(){
        seed();val w=RequestWorkflow(ctx);w.initialize();val old=w.current();sign(w)
        val next=w.create("Orleans visit","Travel")
        assertNotEquals(old.getString("id"),next.getString("id"));assertEquals("PR-00002",next.getString("reference"))
        assertEquals(0,next.getJSONArray("receipts").length());assertFalse(w.signed())
        assertEquals("1234",prefs.getString("cardLast4",""));assertEquals("Orleans visit",prefs.getString("draftDestination",""))
        w.select(old.getString("id"));assertTrue(w.signed());assertEquals(1,w.current().getJSONArray("receipts").length())
    }
    @Test fun canceledEmailKeepsRequestAndDoesNotArchive(){
        seed();val w=RequestWorkflow(ctx);w.initialize();sign(w);val id=w.current().getString("id")
        w.stageEmail("content://test/packet.pdf","Test","finance@example.com","")
        assertEquals(id,w.current().getString("id"));assertEquals("draft",w.current().getString("status"))
        w.cancelEmail();assertEquals(id,w.current().getString("id"));assertEquals(0,JSONArray(prefs.getString("archives","[]")).length())
    }
    @Test fun sentConfirmationSelectsQueuedWorkOrderAndKeepsExactHistory(){
        seed();val w=RequestWorkflow(ctx);w.initialize();sign(w);val old=w.current()
        val next=w.create("Vehicle service visit","Work order",false)
        val pending=w.stageEmail("content://test/packet.pdf","Test subject","finance@example.com","tester@example.com")
        val active=w.confirmSent(pending.getString("token"))
        assertEquals(next.getString("id"),active.getString("id"));assertEquals("Work order",active.getString("kind"))
        val history=JSONArray(prefs.getString("archives","[]"));assertEquals(1,history.length())
        assertEquals(old.getString("id"),history.getJSONObject(0).getString("id"))
        assertEquals("confirmed_by_user",history.getJSONObject(0).getString("sendConfirmation"))
        assertEquals("61.35",history.getJSONObject(0).getJSONArray("receipts").getJSONObject(0).getString("cost"))
        assertFalse(w.signed());assertNull(w.pendingEmail())
        w.confirmSent(pending.getString("token"));assertEquals(1,JSONArray(prefs.getString("archives","[]")).length())
    }
    @Test fun sentLastRequestAutomaticallyCreatesExactlyOneNewPr(){
        seed();val w=RequestWorkflow(ctx);w.initialize();sign(w)
        val token=w.stageEmail("content://test/packet.pdf","Test","finance@example.com","").getString("token")
        w.confirmSent(token);assertEquals("PR-00002",w.current().getString("reference"))
        assertEquals(0,w.current().getJSONArray("trips").length());assertEquals(0,w.current().getJSONArray("receipts").length())
        val restarted=RequestWorkflow(ctx);restarted.initialize();assertEquals(2,restarted.all().length())
        assertEquals("PR-00002",restarted.current().getString("reference"));assertEquals("",prefs.getString("signatureFile",""))
    }
    @Test fun changedRequestCannotBeAccidentallyConfirmedAsSent(){
        seed();val w=RequestWorkflow(ctx);w.initialize();sign(w)
        val token=w.stageEmail("content://test/packet.pdf","Test","finance@example.com","").getString("token")
        prefs.edit().putString("driver","Changed driver").commit()
        try{w.confirmSent(token);fail("Changed request closed")}catch(expected:IllegalStateException){assertTrue(expected.message!!.contains("changed"))}
        assertEquals("draft",w.current().getString("status"));assertNotNull(w.pendingEmail())
    }
    @Test fun openTripCannotBeReplacedOrMarkedSent(){
        seed(open=true);val w=RequestWorkflow(ctx);w.initialize();sign(w)
        try{w.create("Other travel","Travel");fail("Open trip switched")}catch(expected:IllegalStateException){}
        val queued=w.create("Later travel","Travel",false);assertNotEquals(queued.getString("id"),w.current().getString("id"))
        try{w.stageEmail("content://test/packet.pdf","Test","finance@example.com","");fail("Open trip sent")}catch(expected:IllegalStateException){}
    }
    @Test fun backupRoundTripPreservesQueueSignaturesAndCc(){
        seed();val w=RequestWorkflow(ctx);w.initialize();val sig=sign(w);w.create("Next travel","Travel",false)
        val savedId=w.current().getString("id")
        val backup=File(ctx.filesDir,"exports/test_backup.zip").apply{parentFile?.mkdirs()}
        val uri=OfficialPurchaseRequisition.uri(ctx,backup)
        TrackerBackup.export(ctx,uri);assertTrue(backup.length()>0)
        prefs.edit().clear().commit();TrackerBackup.restore(ctx,uri)
        val restored=RequestWorkflow(ctx);restored.initialize();assertEquals(savedId,restored.current().getString("id"))
        assertEquals(2,restored.all().length());assertTrue(restored.signed());assertTrue(File(prefs.getString("signatureFile","")!!).isFile)
        assertEquals("tester@example.com",prefs.getString("myEmail",""));assertTrue(prefs.getBoolean("ccSelf",false))
        assertNull(restored.pendingEmail());assertTrue(sig.isFile)
    }
    private fun launch():MainActivity{
        return (ins.startActivitySync(Intent(ctx,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity).also{activity=it;ins.waitForIdleSync()}
    }
    private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
    private fun click(a:MainActivity,caption:String){ins.runOnMainSync{
        val button=views(a.window.decorView).filterIsInstance<Button>().firstOrNull{it.text.toString()==caption}?:error("Missing button: "+caption)
        button.performClick()
    };ins.waitForIdleSync()}
    private fun visibleText(a:MainActivity,txt:String):Boolean{
        var found=false;ins.runOnMainSync{found=views(a.window.decorView).filterIsInstance<TextView>().any{it.text.toString().contains(txt)}};return found
    }
    private fun screenshot(name:String){Thread.sleep(350);ins.uiAutomation.takeScreenshot()?.let{b->File(out,name+".png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    @Test fun redesignedNavigationRetainsAllSections(){
        seed();val a=launch();assertTrue(visibleText(a,"Your travel workspace"));screenshot("UI_Home")
        click(a,"Fuel");assertTrue(visibleText(a,"Scan and crop receipt automatically"))
        click(a,"Docs");assertTrue(visibleText(a,"Original Karuk Gas worksheet included"));screenshot("UI_Documents")
        click(a,"Requests");assertTrue(visibleText(a,"Queue travel or work order"));screenshot("UI_Requests")
        click(a,"More");assertTrue(visibleText(a,"Backup and restore"));screenshot("UI_More")
    }
    @Test fun missingSignatureRedirectsAndSavingContinuesToInAppPdf(){
        seed();val a=launch();click(a,"Docs");click(a,"Review filled Gas requisition (PDF)")
        assertTrue(visibleText(a,"Sign this request"));assertEquals("requisition",prefs.getString("pendingSignatureAction",""));screenshot("UI_Signature")
        ins.runOnMainSync{
            val pad=views(a.window.decorView).filterIsInstance<SignaturePad>().first()
            val t=android.os.SystemClock.uptimeMillis()
            listOf(Triple(MotionEvent.ACTION_DOWN,30f,55f),Triple(MotionEvent.ACTION_MOVE,100f,100f),
                Triple(MotionEvent.ACTION_MOVE,160f,45f),Triple(MotionEvent.ACTION_UP,230f,105f)).forEachIndexed{i,e->
                val event=MotionEvent.obtain(t,t+i*30,e.first,e.second,e.third,0);pad.dispatchTouchEvent(event);event.recycle()
            }
        }
        val monitor=ins.addMonitor(PdfPreviewActivity::class.java.name,null,false)
        click(a,"Save signature and continue")
        val preview=monitor.waitForActivityWithTimeout(15000);assertNotNull("Signed PDF did not open",preview)
        Thread.sleep(1000);screenshot("UI_Signed_Pdf")
        assertTrue(RequestWorkflow(ctx).signed());assertEquals("",prefs.getString("pendingSignatureAction",""))
        ins.runOnMainSync{preview!!.finish()};ins.removeMonitor(monitor)
    }
    @Test fun signatureContinuationSurvivesRestartAndCancelKeepsData(){
        seed();val w=RequestWorkflow(ctx);w.initialize()
        prefs.edit().putString("pendingSignatureAction","email").putString("pendingSignatureRequest",w.current().getString("id")).commit()
        val a=launch();assertTrue(visibleText(a,"Sign this request"));click(a,"Cancel and return to documents")
        assertEquals("",prefs.getString("pendingSignatureAction",""));assertNull(w.pendingEmail());assertEquals(1,w.current().getJSONArray("receipts").length())
    }
    @Test fun returningFromEmailOffersConfirmationWithoutAutomaticallyClosing(){
        seed();val w=RequestWorkflow(ctx);w.initialize();sign(w);val id=w.current().getString("id")
        w.stageEmail("content://test/packet.pdf","Test","finance@example.com","")
        prefs.edit().putBoolean("requestEmailLeftApp",true).commit()
        launch();Thread.sleep(500);screenshot("UI_Email_Confirmation")
        assertEquals(id,w.current().getString("id"));assertEquals("draft",w.current().getString("status"))
        val buttons=ins.uiAutomation.rootInActiveWindow.findAccessibilityNodeInfosByText("Not sent")
        assertTrue(buttons.isNotEmpty());buttons.first().performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(500);assertNull(w.pendingEmail());assertEquals(id,w.current().getString("id"))
    }
}
