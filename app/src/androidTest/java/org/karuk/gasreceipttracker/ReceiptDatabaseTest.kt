package org.karuk.gasreceipttracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.json.JSONObject
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReceiptDatabaseTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=ins.targetContext
    private val prefs get()=LedgerStore.prefs(ctx)
    private fun receipt()=JSONObject().put("date","10/07/2026").put("receiptDate","10/07/2026")
        .put("station","TEST STATION").put("vehicle","309").put("gallons","8.765").put("price","6.999").put("cost","61.35")
    private fun seed(){
        prefs.edit().putString("driver","Test Driver").putString("vehicle","309").putString("cardLast4","1234")
            .putBoolean("setupDone",true).putString("receipts",JSONArray().put(receipt()).toString()).commit()
        RequestWorkflow(ctx).initialize()
    }
    private fun signature():File {
        val b=Bitmap.createBitmap(400,110,Bitmap.Config.ARGB_8888);val c=Canvas(b);c.drawColor(Color.WHITE)
        c.drawText("TEST SIGNATURE",10f,75f,Paint().apply{color=Color.BLACK;textSize=36f})
        val f=File(ctx.cacheDir,"database_test_sign.png");f.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle();return f
    }
    @Before fun reset(){prefs.edit().clear().commit()}
    @After fun cleanup(){prefs.edit().clear().commit()}
    @Test fun purchaseRequestUsesLocalTodayAndReceiptDateDoesNotChange(){
        val r=receipt();val before=r.toString()
        val clock=Clock.fixed(Instant.parse("2026-10-14T02:00:00Z"),ZoneId.of("America/Los_Angeles"))
        val p=OfficialPurchaseRequisition.prepare(ctx,r,"Test Driver","1234",signature(),clock)
        assertEquals("2026-10-13",p.requestDate)
        HSSFWorkbook(p.excel.inputStream()).use{assertEquals("October 13, 2026",it.getSheet("Gas").getRow(6).getCell(2).stringCellValue)}
        assertEquals(before,r.toString())
        val utc=OfficialPurchaseRequisition.prepare(ctx,r,"Test Driver","1234",signature(),clock.withZone(ZoneId.of("UTC")))
        assertEquals("2026-10-14",utc.requestDate);assertNotEquals(p.excel.name,utc.excel.name)
        HSSFWorkbook(p.excel.inputStream()).use{assertEquals("October 13, 2026",it.getSheet("Gas").getRow(6).getCell(2).stringCellValue)}
    }
    @Test fun parserHandlesSingleGSuffixAndFuelTotalWithoutChangingReceiptValues(){
        val p=FuelReceiptParser.parse("1881 Ft Jones Rd.\nLegend 295 Inc Chevr\n10/07/2026\n04:22:54 PM\nXXXX5530\nPUMP# 5\nUNLEAD REG 8.765G\nPRICE/GAL $6.999\nFUEL TOTAL $61.35\nTOTAL = $61.35\nCREDIT $61.35")
        assertEquals("8.765",p.gallons);assertEquals("6.999",p.price);assertEquals("61.35",p.total)
        assertEquals("10/07/2026",p.date);assertEquals("Legend 295 Inc Chevr",p.station);assertEquals("5530",p.cardLast4)
    }
    @Test fun parserAlignsSeparatedLabelAndAmountColumns(){
        fun line(s:String,x:Float,y:Float,w:Float=130f)=FuelReceiptParser.Line(s,x,y,w,18f)
        val p=FuelReceiptParser.parse("",listOf(line("UNLEAD REG",10f,100f),line("PRICE/GAL",10f,130f),line("FUEL TOTAL",10f,190f),
            line("8.765G",185f,100f),line("$6.999",185f,130f),line("$61.35",185f,190f)))
        assertEquals("8.765",p.gallons);assertEquals("6.999",p.price);assertEquals("61.35",p.total)
    }
    @Test fun parserNeverSubstitutesPriceProductForPrintedTotal(){
        val p=FuelReceiptParser.parse("GALLONS 10.000\nPRICE/GAL $5.999\nFUEL TOTAL $45.67\nSUBTOTAL $99.99\nTAX $3.00")
        assertEquals("45.67",p.total);assertTrue(p.warnings.any{it.contains("differs")})
        val missing=FuelReceiptParser.parse("GALLONS 10.000\nPRICE/GAL $5.999\nAUTH 543210")
        assertEquals("",missing.total);assertTrue(missing.warnings.any{it.contains("Total amount")})
    }
    @Test fun actualFuelPhotoRecognizesGallonsPriceAndTotalAndSavesRawScan(){
        val f=File(ctx.filesDir,"receipt_2222222222222.jpg")
        ins.context.assets.open("fuel_receipt_columns.webp").use{input->f.outputStream().use{input.copyTo(it)}}
        val latch=CountDownLatch(1);var scan:FuelScan?=null
        ins.runOnMainSync{ReceiptReader.scan(ctx,Uri.fromFile(f)){scan=it;latch.countDown()}}
        assertTrue("Receipt scan timed out",latch.await(45,TimeUnit.SECONDS))
        val p=assertNotNullReturn(scan)
        val out=ctx.getExternalFilesDir("verification")!!.apply{mkdirs()}
        File(out,"Receipt_Photo_OCR_Results.txt").writeText("Gallons: ${p.gallons}\nPrice: ${p.price}\nTotal: ${p.total}\nRaw OCR:\n${p.rawText}\nWarnings: ${p.warnings}")
        assertEquals("8.765",p.gallons);assertEquals("6.999",p.price);assertEquals("61.35",p.total)
        assertTrue(p.rawText.isNotBlank());assertTrue(p.scanId.isNotBlank())
        assertTrue(LedgerStore.search(ctx,"61.35").length()>0)
    }
    private fun assertNotNullReturn(scan:FuelScan?):FuelScan{assertNotNull("OCR did not return a scan",scan);return scan!!}
    @Test fun databaseSurvivesCloseAndSearchesOriginalReceiptDate(){
        seed();val state=RequestWorkflow(ctx);state.stampRequestDate();state.sync()
        assertTrue(ctx.getDatabasePath("gas_receipt_tracker.db").isFile)
        val id=state.current().getString("id");LedgerStore.closeForTests()
        assertEquals(id,RequestWorkflow(ctx).current().getString("id"))
        val rows=LedgerStore.search(ctx,"10/07/2026");assertTrue(rows.length()>0)
        assertTrue((0 until rows.length()).map{rows.getJSONObject(it)}.any{it.getString("kind")=="receipt"&&it.getJSONObject("payload").getString("date")=="10/07/2026"})
        assertEquals(0,LedgerStore.search(ctx,"%' OR 1=1 --").length())
    }
    @Test fun badStateWriteRollsBackAndPreservesExistingDatabase(){
        seed();val original=prefs.getString(RequestWorkflow.STATE,"")
        assertFalse(prefs.edit().putString(RequestWorkflow.STATE,"broken JSON").putString("driver","Incorrect replacement").commit())
        assertEquals(original,prefs.getString(RequestWorkflow.STATE,""));assertEquals("Test Driver",prefs.getString("driver",""))
    }
    @Test fun legacyMigrationKeepsAllFieldsAndRunsOnlyOnce(){
        LedgerStore.closeForTests();ctx.deleteDatabase("gas_receipt_tracker.db")
        val old=ctx.getSharedPreferences("usage_data",Context.MODE_PRIVATE)
        old.edit().clear().putString("driver","Legacy Driver").putString("customUserField","keep me").putBoolean("ccSelf",true)
            .putString("receipts",JSONArray().put(receipt()).toString()).commit()
        try{
            val migrated=LedgerStore.prefs(ctx);assertEquals("Legacy Driver",migrated.getString("driver",""));assertEquals("keep me",migrated.getString("customUserField",""))
            assertTrue(migrated.getBoolean("ccSelf",false));assertEquals("Legacy Driver",old.getString("driver",""))
            migrated.edit().putString("driver","New Driver").commit();LedgerStore.closeForTests()
            assertEquals("New Driver",LedgerStore.prefs(ctx).getString("driver",""))
        }finally{old.edit().clear().commit()}
    }
    @Test fun backupRestoresSearchableScansAndGeneratedExcelMetadata(){
        seed();val f=File(ctx.filesDir,"receipt_3333333333333.jpg");f.writeBytes(byteArrayOf(1,2,3))
        LedgerStore.rememberScan(ctx,JSONObject().put("image",Uri.fromFile(f).toString()).put("rawText","FUEL TOTAL 61.35").put("receiptDate","10/07/2026"))
        OfficialPurchaseRequisition.prepare(ctx,receipt(),"Test Driver","1234",signature())
        val counts=LedgerStore.counts(ctx)
        val zip=File(ctx.filesDir,"exports/database_backup_test.zip").apply{parentFile?.mkdirs()}
        val uri=OfficialPurchaseRequisition.uri(ctx,zip);TrackerBackup.export(ctx,uri)
        prefs.edit().clear().commit();TrackerBackup.restore(ctx,uri)
        assertEquals(counts.optInt("scan"),LedgerStore.counts(ctx).optInt("scan"))
        assertEquals(counts.optInt("document"),LedgerStore.counts(ctx).optInt("document"))
        assertTrue(LedgerStore.search(ctx,"FUEL TOTAL").length()>0)
    }
    @Test fun savedDatabaseScreenFindsReceiptBeforeAnyEmailIsSent(){
        seed()
        val a=ins.startActivitySync(android.content.Intent(ctx,MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)
            (0 until v.childCount).flatMap{views(v.getChildAt(it))}else emptyList()
        fun click(caption:String){
            ins.runOnMainSync{views(a.window.decorView).filterIsInstance<android.widget.Button>()
                .first{it.text.toString()==caption}.performClick()};ins.waitForIdleSync()
        }
        try{
            click("More");click("Search saved database records")
            ins.runOnMainSync{
                views(a.window.decorView).filterIsInstance<android.widget.EditText>()
                    .first{it.hint.toString()=="Search saved records"}.setText("61.35")
            }
            click("Search database")
            var found=false
            ins.runOnMainSync{found=views(a.window.decorView).filterIsInstance<android.widget.TextView>()
                .any{it.text.toString().contains("Receipt - TEST STATION")}}
            assertTrue("Saved receipt is not visible in database search",found)
            assertNull(RequestWorkflow(ctx).pendingEmail())
            val out=ctx.getExternalFilesDir("verification")!!.apply{mkdirs()}
            Thread.sleep(250)
            ins.uiAutomation.takeScreenshot()?.let{b->File(out,"UI_Database_Search.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
            File(out,"Database_Verification.txt").writeText("Authoritative local database: gas_receipt_tracker.db\n"+
                "Search for 61.35 returned the saved receipt before email.\n"+
                "Original receipt date: 10/07/2026\nCounts: "+LedgerStore.counts(ctx).toString())
        }finally{ins.runOnMainSync{a.finish()};ins.waitForIdleSync()}
    }

}
