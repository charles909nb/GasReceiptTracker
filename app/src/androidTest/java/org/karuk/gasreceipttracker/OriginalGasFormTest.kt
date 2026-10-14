package org.karuk.gasreceipttracker

import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.apache.poi.hssf.usermodel.*
import org.apache.poi.ss.usermodel.CellType
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class OriginalGasFormTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun receipt()=JSONObject().put("date","10/8/2026").put("station","SAMPLE STATION")
        .put("vehicle","309").put("odometer","9750").put("gallons","8.765").put("price","6.999").put("cost","61.35")
    private fun signature():File{
        val bitmap=Bitmap.createBitmap(600,180,Bitmap.Config.ARGB_8888)
        val c=Canvas(bitmap);c.drawColor(Color.WHITE)
        val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=48f;typeface=Typeface.create("cursive",Typeface.NORMAL)}
        c.drawText("Sample signature - TEST ONLY",10f,100f,p)
        val file=File(context.cacheDir,"sample_signature.png")
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();return file
    }
    private fun prepared()=OfficialPurchaseRequisition.prepare(context,receipt(),"Sample Driver","1234",signature())
    @Test fun bundledOriginalIsGasOnlyAndHasNoSampleTransaction(){
        assertTrue(OfficialPurchaseRequisition.hasTemplate(context))
        val raw=context.assets.open(GasFormWorkbook.ASSET).use{it.readBytes()}
        val hash=MessageDigest.getInstance("SHA-256").digest(raw).joinToString(""){"%02x".format(it)}
        assertEquals(GasFormWorkbook.TEMPLATE_SHA256,hash)
        HSSFWorkbook(raw.inputStream()).use{w->
            assertEquals(1,w.numberOfSheets);assertEquals("Gas",w.getSheetName(0))
            val s=w.getSheetAt(0)
            assertEquals("Signature of Individual Requesting P.O.",s.getRow(46).getCell(8).stringCellValue)
            assertEquals(CellType.BLANK,s.getRow(12).getCell(2).cellType)
            assertEquals("SUM(M19:O36)",s.getRow(39).getCell(8).cellFormula)
            assertTrue(s.numMergedRegions>50)
        }
    }
    @Test fun fillsOriginalAndAnchorsSignatureAboveItsCaption(){
        val files=prepared();assertTrue(files.signed)
        assertTrue(files.excel.isFile);assertTrue(files.pdf.isFile)
        HSSFWorkbook(files.excel.inputStream()).use{w->
            val s=w.getSheet("Gas")
            val today = java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, uuuu", java.util.Locale.US))
            assertEquals("Requisition should be dated today, not October 8 receipt date",today,s.getRow(6).getCell(2).stringCellValue)
            assertEquals("Sample Driver",s.getRow(12).getCell(2).stringCellValue)
            assertEquals("pay CC 1234",s.getRow(13).getCell(2).stringCellValue)
            assertEquals(8.765,s.getRow(18).getCell(1).numericCellValue,.000001)
            assertEquals("Gas car 309",s.getRow(18).getCell(3).stringCellValue)
            assertEquals(6.999,s.getRow(18).getCell(10).numericCellValue,.000001)
            assertEquals(61.35,s.getRow(18).getCell(12).numericCellValue,.000001)
            assertEquals(CellType.FORMULA,s.getRow(39).getCell(8).cellType)
            assertEquals(61.35,s.getRow(39).getCell(8).numericCellValue,.000001)
            assertEquals(1,s.drawingPatriarch.children.size)
            val a=(s.drawingPatriarch.children[0] as HSSFPicture).clientAnchor
            assertTrue(a.row1>=43);assertTrue(a.row2<46 || (a.row2==46 && a.dy2==0))
            assertTrue(a.col1>=8);assertTrue(a.col2<=15)
        }
        val out=context.getExternalFilesDir("verification")!!.apply{mkdirs()}
        files.excel.copyTo(File(out,"Sample_Gas_Requisition.xls"),true)
        files.pdf.copyTo(File(out,"Sample_Gas_Requisition.pdf"),true)
        PdfRenderer(ParcelFileDescriptor.open(files.pdf,ParcelFileDescriptor.MODE_READ_ONLY)).use{pdf->
            assertEquals(1,pdf.pageCount)
            pdf.openPage(0).use{page->
                val bitmap=Bitmap.createBitmap(1224,1584,Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE);page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                File(out,"Sample_Gas_Requisition.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
                bitmap.recycle()
            }
        }
    }
    @Test fun changedReceiptDoesNotReusePreviousValuesAndInvalidInputFails(){
        val changed=receipt().put("cost","22.22").put("gallons","4.321").put("vehicle","310")
        val files=OfficialPurchaseRequisition.prepare(context,changed,"Another Driver","4321",signature())
        HSSFWorkbook(files.excel.inputStream()).use{w->val s=w.getSheet("Gas")
            assertEquals(java.time.LocalDate.now(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, uuuu", java.util.Locale.US)),
                s.getRow(6).getCell(2).stringCellValue)
            assertEquals("Another Driver",s.getRow(12).getCell(2).stringCellValue)
            assertEquals("Gas car 310",s.getRow(18).getCell(3).stringCellValue)
            assertEquals(22.22,s.getRow(39).getCell(8).numericCellValue,.000001)
        }
        try { OfficialPurchaseRequisition.prepare(context,receipt().put("gallons","-1"),"Sample","1234",signature());fail("Negative gallons accepted") }
        catch(expected:IllegalArgumentException) { assertTrue(expected.message!!.contains("gallon")) }
    }
    @Test fun threePagePacketAndInAppReviewNeedNoManualTemplateImport(){
        val r=receipt()
        val image=Bitmap.createBitmap(600,850,Bitmap.Config.ARGB_8888)
        val c=Canvas(image);c.drawColor(Color.WHITE)
        val p=Paint().apply{color=Color.BLACK;textSize=30f}
        c.drawText("TEST RECEIPT - NOT A PURCHASE",35f,70f,p)
        c.drawText("8.765 gallons / TOTAL 61.35",35f,140f,p)
        val photo=File(context.filesDir,"receipt_1000000000000.jpg")
        photo.outputStream().use{image.compress(Bitmap.CompressFormat.JPEG,90,it)};image.recycle()
        r.put("image",android.net.Uri.fromFile(photo).toString())
        val trip=JSONObject().put("driver","Sample Driver").put("vehicle","309").put("department","TEST")
            .put("startDate","October 8, 2026").put("endDate","October 8, 2026")
            .put("startOdometer","9700").put("endOdometer","9750").put("destination","Sample destination").put("purpose","Test verification")
        val uri=ReimbursementPacket.create(context,JSONArray().put(trip),JSONArray().put(r),"Sample Driver","1234",signature().absolutePath)
        val out=context.getExternalFilesDir("verification")!!
        context.contentResolver.openInputStream(uri).use{input->File(out,"Sample_Three_Page_Packet.pdf").outputStream().use{input!!.copyTo(it)}}
        PdfRenderer(context.contentResolver.openFileDescriptor(uri,"r")!!).use{assertEquals(3,it.pageCount)}
        val activity=instrumentation.startActivitySync(Intent(context,PdfPreviewActivity::class.java).setData(uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(1800)
        assertFalse(activity.isFinishing)
        instrumentation.uiAutomation.takeScreenshot()?.let{shot->File(out,"In_App_PDF_Preview.png").outputStream().use{shot.compress(Bitmap.CompressFormat.PNG,100,it)};shot.recycle()}
        instrumentation.runOnMainSync{activity.finish()}
    }
}
