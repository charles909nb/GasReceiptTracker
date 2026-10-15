package org.karuk.gasreceipttracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FuelReceiptDraftTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=ins.targetContext
    private val prefs get()=ctx.getSharedPreferences("usage_data",Context.MODE_PRIVATE)
    private var activity:MainActivity?=null
    private fun views(view:View):List<View> = listOf(view) +
        if(view is ViewGroup)(0 until view.childCount).flatMap{views(view.getChildAt(it))} else emptyList()
    private fun photo():File {
        val file=File(ctx.filesDir,"receipt_20261015.jpg")
        val bitmap=Bitmap.createBitmap(540,850,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.WHITE);drawText("CHEVRON",30f,90f,
            Paint().apply{color=Color.BLACK;textSize=40f}) }
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,95,it)}
        bitmap.recycle()
        return file
    }
    private fun base():JSONObject=JSONObject()
        .put("image",Uri.fromFile(photo()).toString())
        .put("station","Chevron").put("date","10/15/2026")
        .put("vehicle","309").put("gallons","8.765")
        .put("price","6.9993").put("cost","61.35")
        .put("ocrRawText","PRICE/GAL 6.9993\nFUEL TOTAL 61.35")
        .put("odometer","")
    @Before fun reset(){prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db")}
    @After fun cleanup(){activity?.let{ins.runOnMainSync{it.finish()}}
        activity=null;ins.waitForIdleSync();prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db")}

    @Test fun scanCreatesSearchableDraftWithoutOdometer(){
        val first=FuelReceiptDraft.update(null,base(),false)
        assertEquals("draft",first.getString("status"))
        assertTrue(FuelReceiptDraft.missing(first).contains("Odometer reading"))
        assertEquals("61.35",first.getString("cost"))
        assertTrue(first.getString("ocrRawText").contains("6.9993"))
        prefs.edit().putString("receipts",JSONArray().put(first).toString()).commit()
        RequestWorkflow(ctx).initialize()
        TrackerDatabase(ctx).use{ assertEquals(1,it.receiptSearch("61.35").size) }
    }
    @Test fun completingSameDraftKeepsOriginalIdentityAndImage(){
        val original=FuelReceiptDraft.update(null,base(),false)
        val corrected=FuelReceiptDraft.update(original,JSONObject().put("odometer","11500"),true)
        assertEquals("complete",corrected.getString("status"))
        assertEquals(original.getString("id"),corrected.getString("id"))
        assertEquals(original.getString("savedAt"),corrected.getString("savedAt"))
        assertEquals(original.getString("image"),corrected.getString("image"))
        assertEquals("61.35",corrected.getString("cost"))
        assertEquals("8.765",corrected.getString("gallons"))
        assertEquals(0,corrected.getJSONArray("missingFields").length())
    }
    @Test fun missingCardAcknowledgmentSavesDraftInsteadOfLosingReceipt(){
        val record=FuelReceiptDraft.update(null,base().put("odometer","11500")
            .put("cardVerificationStatus","MISMATCH")
            .put("cardMismatchAcknowledged",false),true)
        assertEquals("draft",record.getString("status"))
        assertTrue(FuelReceiptDraft.missing(record).contains("Payment card check"))
        val done=FuelReceiptDraft.update(record,JSONObject()
            .put("cardVerificationStatus","MISMATCH_ACKNOWLEDGED")
            .put("cardMismatchAcknowledged",true),true)
        assertEquals("complete",done.getString("status"))
    }
    @Test fun incompleteScannedReceiptSurvivesFullBackupAndRestore(){
        val original=FuelReceiptDraft.update(null,base(),false)
        prefs.edit().putString("receipts",JSONArray().put(original).toString())
            .putString("draftFuelReceiptId",original.getString("id"))
            .putString("pending_receipt",original.getString("image")).commit()
        RequestWorkflow(ctx).initialize()
        val backup=File(ctx.filesDir,"exports/fuel_draft_backup.zip")
            .apply{parentFile?.mkdirs()}
        val location=OfficialPurchaseRequisition.uri(ctx,backup)
        TrackerBackup.export(ctx,location)
        prefs.edit().clear().commit()
        ctx.deleteDatabase("fuel_records.db")
        TrackerBackup.restore(ctx,location)
        val recovered=RequestWorkflow(ctx)
        recovered.initialize()
        val restored=recovered.current().getJSONArray("receipts").getJSONObject(0)
        assertEquals("draft",restored.getString("status"))
        assertEquals(original.getString("id"),restored.getString("id"))
        assertTrue(File(Uri.parse(restored.getString("image")).path!!).isFile)
        assertEquals("61.35",restored.getString("cost"))
        assertTrue(FuelReceiptDraft.missing(restored).contains("Odometer reading"))
    }
    private fun launch(){
        prefs.edit().putBoolean("setupDone",true).putString("driver","Test Driver")
            .putString("vehicle","309").putString("vehicles","309")
            .putString("trips","[]").putString("receipts","[]").commit()
        RequestWorkflow(ctx).initialize()
        activity=(ins.startActivitySync(Intent(ctx,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity)
        ins.waitForIdleSync()
        click("Fuel")
    }
    private fun click(text:String){
        val a=requireNotNull(activity)
        ins.runOnMainSync{
            val button=views(a.window.decorView).filterIsInstance<Button>()
                .firstOrNull{it.text.toString().equals(text,true)}
                ?:error("Button not found: $text")
            button.performClick()
        }
        ins.waitForIdleSync()
    }
    private fun allText():List<String> = views(requireNotNull(activity).window.decorView)
        .filterIsInstance<TextView>().map{it.text.toString()}

    @Test fun fuelPageHasOneScanOneSaveAndVehicleAboveOdometer(){
        launch()
        val labels=allText()
        assertTrue(labels.contains("Scan receipt"))
        assertTrue(labels.contains("Save fuel receipt"))
        assertFalse(labels.contains("Save fuel draft"))
        assertFalse(labels.contains("Take receipt photo"))
        assertFalse(labels.contains("Read receipt photo (OCR)"))
        assertFalse(labels.contains("Scan and crop receipt automatically"))
        assertFalse(labels.any{it.contains("original receipt date stays")})
        assertFalse(labels.any{it.contains("Entries are saved automatically")})
        val selected=views(requireNotNull(activity).window.decorView)
            .filterIsInstance<Spinner>().first().selectedItem.toString()
        assertEquals("309",selected)
        assertTrue(labels.indexOf("Vehicle number") < labels.indexOf("Odometer reading at fueling"))
    }
    @Test fun saveWithoutOdometerKeepsDraftThenUpdatesItInPlace(){
        launch()
        val a=requireNotNull(activity)
        val picture=Uri.fromFile(photo()).toString()
        prefs.edit().putString("pending_receipt",picture)
            .putString("ocr_station","Chevron")
            .putString("ocr_date","10/15/2026")
            .putString("ocr_gallons","8.765")
            .putString("ocr_price","6.9993")
            .putString("ocr_total","61.35").commit()
        // Simulate returning to Fuel after capture. The page recovers and persists
        // the pending image/OCR, without requiring a complete odometer entry.
        click("Fuel")
        click("Save fuel receipt")
        val draft=JSONArray(prefs.getString("receipts","[]")).getJSONObject(0)
        assertEquals("draft",draft.getString("status"))
        assertEquals(picture,draft.getString("image"))
        assertEquals("61.35",draft.getString("cost"))
        assertEquals(1,JSONArray(prefs.getString("receipts","[]")).length())
        val oldId=draft.getString("id")
        ins.runOnMainSync{
            val odo=views(a.window.decorView).filterIsInstance<EditText>()
                .first{it.contentDescription=="Odometer reading at fueling"}
            odo.setText("11500")
        }
        click("Save fuel receipt")
        val updated=JSONArray(prefs.getString("receipts","[]")).getJSONObject(0)
        assertEquals(oldId,updated.getString("id"))
        assertEquals("complete",updated.getString("status"))
        assertEquals("11500",updated.getString("odometer"))
        assertEquals(1,JSONArray(prefs.getString("receipts","[]")).length())
        TrackerDatabase(ctx).use{assertEquals(1,it.receiptSearch("61.35").size)}
    }
}