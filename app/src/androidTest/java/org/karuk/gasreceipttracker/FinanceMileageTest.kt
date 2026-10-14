package org.karuk.gasreceipttracker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.view.View
import android.view.ViewGroup
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
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class FinanceMileageTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=instrumentation.targetContext
    private val prefs get()=ctx.getSharedPreferences("usage_data",Context.MODE_PRIVATE)
    private var activity:Activity?=null
    private val captures get()=ctx.getExternalFilesDir("verification")!!.apply{mkdirs()}
    @Before fun before(){prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db")}
    @After fun after(){
        activity?.let{instrumentation.runOnMainSync{it.finish()}}
        activity=null;instrumentation.waitForIdleSync()
        prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db")
    }
    private fun receipt(amount:String="61.35",date:String="10/7/2026",odometer:String="9750",
                        fill:String="full"):JSONObject =
        JSONObject().put("id","sample-receipt-1").put("date",date).put("station","Chevron Yreka")
            .put("vehicle","309").put("gallons","8.765").put("price","6.9993")
            .put("cost",amount).put("odometer",odometer).put("fillType",fill)
            .put("image","file:///private/receipt_1234567890.jpg")
    private fun trip()=JSONObject().put("driver","Test Driver").put("vehicle","309")
        .put("department","ELDERS").put("startDate","Oct 7, 2026")
        .put("endDate","Oct 7, 2026").put("startOdometer","9650")
        .put("endOdometer","9750").put("destination","Happy Camp")
    private fun seed():RequestWorkflow{
        prefs.edit().putBoolean("setupDone",true).putString("driver","Test Driver")
            .putString("vehicle","309").putString("vehicles","309,310")
            .putString("department","ELDERS").putString("cardLast4","1234")
            .putString("trips",JSONArray().put(trip()).toString())
            .putString("receipts",JSONArray().put(receipt()).toString()).commit()
        return RequestWorkflow(ctx).also{it.initialize()}
    }
    private fun signed(w:RequestWorkflow){
        val dummy=File(ctx.cacheDir,"finance_test_signature.png")
        dummy.writeBytes(byteArrayOf(1,2,3,4))
        w.saveSignature(dummy)
    }
    private fun syntheticPdf():File{
        val out=File(ctx.filesDir,"exports/Test_Finance_Source.pdf").apply{parentFile?.mkdirs()}
        val doc=PdfDocument()
        try{
            for(i in 1..3){
                val page=doc.startPage(PdfDocument.PageInfo.Builder(612,792,i).create())
                page.canvas.drawText("Synthetic Finance Packet Page "+i,
                    28f,48f,Paint().apply{color=Color.BLACK;textSize=17f})
                doc.finishPage(page)
            }
            out.outputStream().use{doc.writeTo(it)}
        }finally{doc.close()}
        return out
    }
    private fun sendSample(w:RequestWorkflow):FinancePacketRecord{
        signed(w)
        val source=syntheticPdf()
        val frozen=FinancePackets.freeze(ctx,w.current(),OfficialPurchaseRequisition.uri(ctx,source))
        val pending=w.stageEmail(frozen.toString(),"Gas PR finance","finance@example.com","copy@example.com")
        w.confirmSent(pending.getString("token"))
        return FinancePackets.list(ctx).single()
    }
    private fun image(name:String){
        Thread.sleep(400)
        instrumentation.uiAutomation.takeScreenshot()?.let{b->
            File(captures,name+".png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            b.recycle()
        }
    }
    private fun all(v:View):List<View> =
        listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{all(v.getChildAt(it))}else emptyList()
    private fun start(type:Class<out Activity>):Activity{
        val a=instrumentation.startActivitySync(
            Intent(ctx,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        activity=a;instrumentation.waitForIdleSync()
        return a
    }

    @Test fun copiesTheExactThreePagePdfBeforeFinanceEmail(){
        val w=seed();val record=sendSample(w)
        assertEquals("PR-00001",record.reference)
        val uri=FinancePackets.pdf(ctx,record)
        val raw=ctx.contentResolver.openInputStream(uri)!!.use{it.readBytes()}
        val original=syntheticPdf().readBytes()
        assertArrayEquals("The archived packet must be byte-identical to the sent packet",original,raw)
        assertTrue(record.pdfUri.contains("Finance_PR-00001_"))
        assertEquals(raw.size.toLong(),record.pdfBytes)
        assertTrue(record.sha256.isNotBlank())
        assertEquals(1,JSONArray(prefs.getString("archives","[]")).length())
    }

    @Test fun defaultFinanceLookupUsesRequestDatePlusReceiptDateAndAmount(){
        val w=seed();sendSample(w)
        val packet=FinancePackets.list(ctx).single()
        assertEquals(LocalDate.now().toString(),packet.prDate)
        assertEquals("2026-10-07",packet.purchaseDate)
        assertEquals(6135L,packet.cents)
        for(term in listOf(packet.prDate,"2026-10-07","10/7/2026","61.35","309","Chevron","PR-00001")){
            assertEquals("Search for "+term,1,FinancePackets.list(ctx,term).size)
        }
        assertTrue(FinancePackets.list(ctx,"99999").isEmpty())
        assertEquals(1,FinancePackets.list(ctx,"2026-10-07 61.35").size)
        assertEquals(1,FinancePackets.list(ctx,"2026-10-07 61.35 309").size)
        assertTrue(FinancePackets.list(ctx,"2026-10-08 61.35").isEmpty())
    }

    @Test fun manualFinanceFollowUpStatusSurvivesSyncAndCanBeFiltered(){
        val w=seed();val row=sendSample(w)
        FinancePackets.updateStatus(ctx,row.id,FinancePackets.FOLLOW_UP,
            "Finance asked for the original purchase date and receipt total")
        assertEquals(FinancePackets.FOLLOW_UP,FinancePackets.list(ctx).single().financeStatus)
        assertTrue(FinancePackets.list(ctx,status=FinancePackets.COMPLETED).isEmpty())
        w.initialize();w.sync()
        assertEquals("Finance asked for the original purchase date and receipt total",
            FinancePackets.list(ctx).single().notes)
        assertEquals(1,FinancePackets.list(ctx,status=FinancePackets.FOLLOW_UP).size)
    }

    @Test fun reopeningOrResendingDoesNotChangeSignedPacketOrCloseAnotherPR(){
        val w=seed();val packet=sendSample(w)
        val second=w.current().getString("id")
        val before=ctx.contentResolver.openInputStream(FinancePackets.pdf(ctx,packet))!!.use{it.readBytes()}
        val after=ctx.contentResolver.openInputStream(FinancePackets.pdf(ctx,packet))!!.use{it.readBytes()}
        assertArrayEquals(before,after)
        assertEquals(second,w.current().getString("id"))
        assertNull(w.pendingEmail())
        assertEquals(1,FinancePackets.list(ctx).size)
        assertEquals(FinancePackets.AWAITING,packet.financeStatus)
    }

    @Test fun portableBackupRestoresCompleteFinancePacketAndFollowUp(){
        val w=seed();val packet=sendSample(w)
        FinancePackets.updateStatus(ctx,packet.id,FinancePackets.COMPLETED,"Finance processed this item")
        val target=File(ctx.filesDir,"exports/Test_Finance_Backup.zip")
        TrackerBackup.export(ctx,OfficialPurchaseRequisition.uri(ctx,target))
        val archived=FinancePackets.ownedFile(ctx,packet.pdfUri)!!
        val oldBytes=archived.readBytes()
        assertTrue(archived.delete())
        prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db")
        TrackerBackup.restore(ctx,OfficialPurchaseRequisition.uri(ctx,target))
        RequestWorkflow(ctx).initialize()
        val restored=FinancePackets.list(ctx).single()
        assertEquals(FinancePackets.COMPLETED,restored.financeStatus)
        assertEquals("Finance processed this item",restored.notes)
        assertArrayEquals(oldBytes,ctx.contentResolver.openInputStream(FinancePackets.pdf(ctx,restored))!!.use{it.readBytes()})
    }

    @Test fun databaseV1MigratesToV2WithoutDroppingOriginalTables(){
        val dbfile=ctx.getDatabasePath("fuel_records.db").apply{parentFile?.mkdirs()}
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(dbfile,null).use{db->
            db.execSQL("CREATE TABLE preserved_sample (id TEXT PRIMARY KEY, value TEXT)")
            db.execSQL("INSERT INTO preserved_sample VALUES ('old','keep me')")
            db.version=1
        }
        TrackerDatabase(ctx).use{db->
            assertEquals(2,db.readableDatabase.version)
            db.readableDatabase.rawQuery("SELECT value FROM preserved_sample",null).use{cursor->
                assertTrue(cursor.moveToFirst());assertEquals("keep me",cursor.getString(0))
            }
            db.readableDatabase.rawQuery("SELECT * FROM finance_packets LIMIT 0",null).close()
        }
    }

    @Test fun historicalFullTankAnnotationDoesNotChangeSubmittedPdf(){
        val w=seed();val packet=sendSample(w)
        val oldUri=packet.pdfUri
        val original=ctx.contentResolver.openInputStream(FinancePackets.pdf(ctx,packet))!!.use{it.readBytes()}
        w.setFillType(packet.id,"sample-receipt-1","partial")
        assertEquals("partial",TrackerDatabase(ctx).use{
            it.receiptSearch("Chevron").single().payload.getString("fillType")})
        assertEquals(oldUri,FinancePackets.list(ctx).single().pdfUri)
        assertArrayEquals(original,ctx.contentResolver.openInputStream(FinancePackets.pdf(ctx,packet))!!.use{it.readBytes()})
    }

    private fun point(i:Int,odo:Int,gal:Int=10,type:String="full",vehicle:String="309")=
        MileageAnalytics.Stop("record-"+i,vehicle,
            "2026-01-"+String.format(java.util.Locale.US,"%02d",i),odo.toDouble(),
            gal.toDouble(),type,50.0,"PR-test")

    @Test fun twoFullTanksCalculateMilesOverLaterGallons(){
        val r=MileageAnalytics.report("309",listOf(point(1,1000),point(2,1200)))
        assertEquals(1,r.samples.size)
        assertEquals(200.0,r.samples[0].miles,.000001)
        assertEquals(10.0,r.samples[0].gallons,.000001)
        assertEquals(20.0,r.samples[0].mpg,.000001)
    }

    @Test fun partialFillGallonsAreIncludedBetweenConfirmedFullTanks(){
        val r=MileageAnalytics.report("309",listOf(point(1,1000),
            point(2,1120,4,"partial"),point(3,1240,8)))
        assertEquals(1,r.samples.size)
        assertEquals(12.0,r.samples.single().gallons,.000001)
        assertEquals(20.0,r.samples.single().mpg,.000001)
    }

    @Test fun unverifiedIntermediatePurchaseInterruptsMpgCalculation(){
        val r=MileageAnalytics.report("309",listOf(point(1,1000),
            point(2,1100,5,"unknown"),point(3,1400),point(4,1600)))
        assertEquals(1,r.samples.size)
        assertEquals(20.0,r.samples.single().mpg,.000001)
        assertEquals(1,r.unknownFills)
    }

    @Test fun twentyPercentDeclineAfterThreeBaselineCyclesFlagsPossibleMaintenance(){
        val r=MileageAnalytics.report("309",listOf(point(1,1000),point(2,1200),
            point(3,1400),point(4,1600),point(5,1720)))
        assertEquals(4,r.samples.size)
        assertEquals(20.0,r.samples[0].mpg,.000001)
        assertEquals(12.0,r.samples.last().mpg,.000001)
        assertEquals(20.0,r.samples.last().baseline!!,.000001)
        assertEquals(40.0,r.samples.last().declinePercent!!,.000001)
        assertTrue(r.samples.last().maintenanceFlag)
        assertEquals(1,r.alerts)
    }

    @Test fun oneLowFuelReadingWithoutBaselineMustNotFlagMaintenance(){
        val r=MileageAnalytics.report("309",listOf(point(1,1000),point(2,1120)))
        assertEquals(12.0,r.samples.single().mpg,.000001)
        assertFalse(r.samples.single().maintenanceFlag)
        assertNull(r.samples.single().baseline)
    }

    @Test fun odometerResetDoesNotProduceFakeMaintenanceAlert(){
        val r=MileageAnalytics.report("309",listOf(point(1,1000),
            point(2,980),point(3,1180)))
        assertEquals(1,r.invalid)
        assertEquals(1,r.samples.size)
        assertFalse(r.samples.single().maintenanceFlag)
    }

    @Test fun financeArchiveActivityShowsPurchasePrDatesAndFullPdfActions(){
        val w=seed();sendSample(w)
        val a=start(FinanceArchiveActivity::class.java)
        val texts=all(a.window.decorView).filterIsInstance<TextView>().map{it.text.toString()}
        assertTrue(texts.any{it.contains("Finance packet archive")})
        assertTrue(texts.any{it.contains("PR-00001")})
        assertTrue(texts.any{it.contains("2026-10-07")})
        assertTrue(all(a.window.decorView).filterIsInstance<Button>()
            .any{it.text.toString().startsWith("Open complete PDF")})
        image("UI_Finance_Archive_Search")
    }

    @Test fun vehicleDashboardContainsNativeGraphAndFullTankGuidance(){
        seed()
        val a=start(MileageActivity::class.java)
        assertTrue(all(a.window.decorView).any{it is VehicleMpgChart})
        val labels=all(a.window.decorView).filterIsInstance<TextView>().map{it.text.toString()}
        assertTrue(labels.any{it.contains("Vehicle mileage dashboard")})
        assertTrue(labels.any{it.contains("Full-tank")||it.contains("full-tank")})
        image("UI_Vehicle_MPG_Dashboard")
    }
}
