package org.karuk.gasreceipttracker

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import org.json.JSONArray
import org.json.JSONObject

/** Reproduces the five-block vehicle usage sheet layout supplied by the program. */
object VehicleUsagePdf {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private fun text(canvas: Canvas, value: String, x: Float, y: Float, size: Float = 9f, bold: Boolean = false, max: Int = 42) {
        ink.style = Paint.Style.FILL
        ink.textSize = size
        ink.typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        canvas.drawText(value.take(max), x, y, ink)
    }
    private fun rule(canvas: Canvas, x1: Float, y: Float, x2: Float) {
        ink.style = Paint.Style.STROKE
        ink.strokeWidth = 0.7f
        canvas.drawLine(x1, y, x2, y, ink)
        ink.style = Paint.Style.FILL
    }
    fun append(doc: PdfDocument, trips: JSONArray, receipts: JSONArray, pageNumberStart: Int): Int {
        val count = maxOf(1, (trips.length() + 4) / 5)
        val latest = if (receipts.length() > 0) receipts.optJSONObject(receipts.length() - 1) else null
        for (sheet in 0 until count) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNumberStart + sheet).create())
            val canvas = page.canvas
            text(canvas, "VEHICLE USAGE SHEET", 207f, 92f, 18f, true)
            for (block in 0 until 5) {
                val t = trips.optJSONObject(sheet * 5 + block) ?: JSONObject()
                val y = 125f + block * 102f
                rule(canvas, 65f, y, 546f)
                text(canvas, "DRIVERS NAME:", 70f, y + 16, 8f, true)
                text(canvas, t.optString("driver"), 148f, y + 16, 9f, false, 17)
                text(canvas, "VEHICLE: #", 240f, y + 16, 8f, true)
                text(canvas, t.optString("vehicle"), 302f, y + 16)
                text(canvas, "DEPARTMENT/PROGRAM#:", 352f, y + 16, 8f, true)
                text(canvas, t.optString("department"), 477f, y + 16, 9f, false, 12)
                rule(canvas, 148f, y + 19, 235f)
                rule(canvas, 302f, y + 19, 344f)
                rule(canvas, 477f, y + 19, 546f)
                text(canvas, "START DATE/OD:", 70f, y + 43, 8f, true)
                text(canvas, t.optString("startDate").substringBeforeLast(" ") + " " + t.optString("startOdometer"), 158f, y + 43, 9f, false, 27)
                text(canvas, "END DATE/OD:", 315f, y + 43, 8f, true)
                text(canvas, t.optString("endDate").substringBeforeLast(" ") + " " + t.optString("endOdometer"), 395f, y + 43, 9f, false, 27)
                rule(canvas, 158f, y + 46, 308f)
                rule(canvas, 395f, y + 46, 546f)
                text(canvas, "DESTINATION:", 70f, y + 71, 8f, true)
                text(canvas, t.optString("destination"), 147f, y + 71, 9f, false, 30)
                text(canvas, "PURPOSE OF TRIP:", 315f, y + 71, 8f, true)
                text(canvas, t.optString("purpose"), 417f, y + 71, 9f, false, 22)
                rule(canvas, 147f, y + 74, 308f)
                rule(canvas, 417f, y + 74, 546f)
                rule(canvas, 65f, y + 90, 546f)
            }
            text(canvas, "ODOMETER READING AT FUELING:", 70f, 663f, 9f, true)
            text(canvas, if (sheet == count - 1) latest?.optString("odometer").orEmpty() else "", 270f, 663f)
            rule(canvas, 270f, 667f, 546f)
            text(canvas, "TOTAL GALLONS:", 70f, 690f, 9f, true)
            text(canvas, if (sheet == count - 1) latest?.optString("gallons").orEmpty() else "", 170f, 690f)
            rule(canvas, 170f, 694f, 303f)
            text(canvas, "TOTAL COSTS:", 310f, 690f, 9f, true)
            text(canvas, if (sheet == count - 1) latest?.optString("cost").orEmpty() else "", 410f, 690f)
            rule(canvas, 410f, 694f, 546f)
            doc.finishPage(page)
        }
        return count
    }
}
