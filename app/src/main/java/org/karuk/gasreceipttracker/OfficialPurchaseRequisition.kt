package org.karuk.gasreceipttracker

import android.content.Context
import android.content.ContentValues
import android.provider.MediaStore
import android.os.Build
import android.net.Uri
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.ss.usermodel.ClientAnchor
import org.apache.poi.ss.usermodel.Workbook
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream

/** Edits the actual Karuk Tribe .xls template, preserving its sheet layout. */
object OfficialPurchaseRequisition {
    fun saveTemplate(context: Context, source: Uri): File {
        val target = File(context.filesDir, "Karuk_Purchase_Requisitions_Original.xls")
        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Cannot open selected template" }
            target.outputStream().use { input.copyTo(it) }
        }
        FileInputStream(target).use { HSSFWorkbook(it).close() }
        return target
    }
    fun export(context: Context, receipt: JSONObject, driver: String, cardLast4: String, signature: File?): Uri {
        val original = File(context.filesDir, "Karuk_Purchase_Requisitions_Original.xls")
        require(original.exists()) { "Import the official purchase requisition template first" }
        val book = FileInputStream(original).use { HSSFWorkbook(it) }
        try {
            val sheet = book.getSheet("Gas") ?: throw IllegalStateException("Original workbook is missing Gas sheet")
            fun put(row: Int, col: Int, value: String) {
                val r = sheet.getRow(row - 1) ?: sheet.createRow(row - 1)
                val c = r.getCell(col - 1) ?: r.createCell(col - 1)
                c.setCellValue(value)
            }
            fun numeric(row: Int, col: Int, value: String) {
                val number = value.replace(",", "").replace("$", "").toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid number: " + value)
                val r = sheet.getRow(row - 1) ?: sheet.createRow(row - 1)
                val c = r.getCell(col - 1) ?: r.createCell(col - 1)
                c.setCellValue(number)
            }
            // Preserve all original cells and formatting except the specified reimbursement fields.
            put(13, 3, driver)
            put(14, 3, "pay CC " + cardLast4)
            put(19, 4, "pay CC " + cardLast4)
            numeric(25, 2, receipt.optString("gallons"))
            put(25, 4, "Gas car " + receipt.optString("vehicle"))
            numeric(25, 13, receipt.optString("cost"))
            if (signature != null && signature.exists()) {
                val bytes = signature.readBytes()
                val imageIndex = book.addPicture(bytes, Workbook.PICTURE_TYPE_PNG)
                val drawing = sheet.createDrawingPatriarch()
                val anchor = book.creationHelper.createClientAnchor()
                anchor.col1 = 9
                anchor.row1 = 44
                anchor.col2 = 14
                anchor.row2 = 46
                drawing.createPicture(anchor, imageIndex)
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "Karuk_Gas_PR_" + System.currentTimeMillis() + ".xls")
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.ms-excel")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Downloads.RELATIVE_PATH, "Download/GasReceiptTracker")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Cannot create workbook")
            context.contentResolver.openOutputStream(uri).use { out ->
                requireNotNull(out) { "Cannot write workbook" }
                book.write(out)
            }
            return uri
        } finally { book.close() }
    }
}
