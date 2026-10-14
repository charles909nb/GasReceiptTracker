package org.karuk.gasreceipttracker

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.Button
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
class GuidedSearchUiTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext
    private val prefs get() = ctx.getSharedPreferences("usage_data", android.content.Context.MODE_PRIVATE)
    private var activity: MainActivity? = null
    private fun views(root: View): List<View> = listOf(root) +
        if(root is ViewGroup)(0 until root.childCount).flatMap { views(root.getChildAt(it)) }
        else emptyList()
    private fun click(name:String){
        val a=activity?:error("Activity missing")
        ins.runOnMainSync{
            views(a.window.decorView).filterIsInstance<Button>()
                .first { it.text.toString()==name }.performClick()
        }
        ins.waitForIdleSync()
    }
    private fun hasText(snippet:String):Boolean {
        val a=activity?:return false
        var match=false
        ins.runOnMainSync{
            match=views(a.window.decorView).filterIsInstance<TextView>()
                .any { it.text.toString().contains(snippet,true) }
        }
        return match
    }
    private fun screenshot(file:String){
        ins.waitForIdleSync()
        Thread.sleep(250)
        ins.uiAutomation.takeScreenshot()?.let { b->
            File(ctx.getExternalFilesDir("verification"),file+".png").apply{parentFile?.mkdirs()}
                .outputStream().use { b.compress(Bitmap.CompressFormat.PNG,100,it) }
            b.recycle()
        }
    }
    @Before fun reset(){prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db")}
    @After fun finish(){activity?.let { ins.runOnMainSync{it.finish()} };activity=null;ins.waitForIdleSync()}
    private fun launchWithReceipt(){
        val image=File(ctx.filesDir,"receipt_20261007.jpg")
        val bitmap=Bitmap.createBitmap(240,480,Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply { drawColor(Color.WHITE);drawText("TEST SAMPLE $61.35",20f,30f,
            Paint().apply { color=Color.BLACK;textSize=15f }) }
        image.outputStream().use{bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)}
        bitmap.recycle()
        val receipt=JSONObject().put("station","Sample Chevron").put("date","10/07/2026")
            .put("vehicle","309").put("gallons","8.765").put("price","6.9993")
            .put("cost","61.35").put("image",android.net.Uri.fromFile(image).toString())
        prefs.edit().putBoolean("setupDone",true).putString("driver","Test Driver")
            .putString("vehicle","309").putString("vehicles","309")
            .putString("receipts",JSONArray().put(receipt).toString())
            .putString("trips","[]").commit()
        RequestWorkflow(ctx).initialize()
        activity=(ins.startActivitySync(Intent(ctx,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity)
        ins.waitForIdleSync()
    }
    @Test fun savedReceiptSearchProvidesRecordedAmountSuggestionsAndResults(){
        launchWithReceipt()
        click("Requests")
        click("Search saved fuel receipts")
        assertTrue(hasText("Find a saved record"))
        val a=requireNotNull(activity)
        ins.runOnMainSync {
            views(a.window.decorView).filterIsInstance<Spinner>().first().setSelection(1) // Amount
        }
        ins.waitForIdleSync()
        assertTrue(hasText("Find records near this saved amount"))
        ins.runOnMainSync {
            views(a.window.decorView).filterIsInstance<AutoCompleteTextView>()
                .first().setText("60")
        }
        ins.waitForIdleSync()
        assertTrue(hasText("1 matching saved receipt"))
        screenshot("UI_Receipt_Guided_Search")
    }
    @Test fun financeArchiveShowsExactDateAndBetweenDateOptionsWithoutFreeTextSearch(){
        launchWithReceipt()
        click("Requests")
        click("Find complete packets sent to Finance")
        assertTrue(hasText("Find a saved record"))
        val a=requireNotNull(activity)
        ins.runOnMainSync {
            val spinners=views(a.window.decorView).filterIsInstance<Spinner>()
            assertTrue(spinners.size>=3) // field, date match, sort
            spinners[1].setSelection(2) // Between dates
        }
        ins.waitForIdleSync()
        assertTrue(hasText("From recorded date"))
        assertTrue(hasText("Through recorded date"))
        assertTrue(hasText("0 matching complete Finance packet"))
        screenshot("UI_Finance_Guided_Search")
    }
}