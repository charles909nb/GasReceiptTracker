package org.karuk.gasreceipttracker

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.widget.*
import android.text.InputType
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("usage_data", MODE_PRIVATE) }
    private lateinit var body: LinearLayout
    private var cameraUri: Uri? = null
    private val now get() = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(Date())
    private fun data(key: String): JSONArray = try { JSONArray(prefs.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
    private fun store(key: String, value: JSONArray) { prefs.edit().putString(key, value.toString()).commit() }
    private fun pref(key: String) = prefs.getString(key, "") ?: ""
    private fun label(text: String, size: Float = 18f) {
        val v = TextView(this); v.text = text; v.textSize = size; v.setTextColor(Color.DKGRAY)
        v.setPadding(0, 10, 0, 10); body.addView(v)
    }
    private fun edit(hint: String, value: String = "", number: Boolean = false): EditText {
        val e = EditText(this); e.hint = hint; e.setText(value); e.setSingleLine(true)
        if (number) e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        body.addView(e); return e
    }
    private fun action(text: String, run: () -> Unit) {
        val b = Button(this); b.text = text; body.addView(b); b.setOnClickListener { run() }
    }
    private fun alert(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    private fun draw(title: String) {
        val scroll = ScrollView(this); body = LinearLayout(this); body.orientation = LinearLayout.VERTICAL
        body.setPadding(22, 16, 22, 24); scroll.addView(body); setContentView(scroll)
        label("Gas Receipt Tracker - v1.4", 23f)
        val nav = LinearLayout(this); nav.orientation = LinearLayout.HORIZONTAL
        for ((caption, dest) in listOf("Trips" to "home", "Fuel" to "fuel", "Sheets" to "sheets", "Email" to "email", "Sign" to "sign")) {
            val b = Button(this); b.text = caption; nav.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
            b.setOnClickListener { show(dest) }
        }
        body.addView(nav); label(title, 22f)
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); show("home") }
    private fun show(where: String) {
        when(where) { "fuel" -> fuel(); "sheets" -> sheets(); "email" -> emailSettings(); "sign" -> signatureScreen(); else -> trips() }
    }
    private fun trips() {
        draw("Vehicle usage / Trips")
        val driver = edit("Driver's name", pref("driver"))
        val vehicle = edit("Vehicle number", pref("vehicle"))
        val dept = edit("Department / program", pref("department"))
        action("Save driver and vehicle") {
            prefs.edit().putString("driver", driver.text.toString()).putString("vehicle", vehicle.text.toString())
                .putString("department", dept.text.toString()).commit()
            alert("Driver and vehicle saved")
        }
        val entries = data("trips")
        var active = -1
        for (i in 0 until entries.length()) if (!entries.getJSONObject(i).has("endOdometer")) active = i
        if (active >= 0) {
            val trip = entries.getJSONObject(active)
            label("Trip in progress: " + trip.optString("destination") + "\n" + trip.optString("purpose") + "\nStarting odometer: " + trip.optString("startOdometer"))
            val end = edit("Ending odometer", number = true)
            action("End trip") {
                val od = end.text.toString().toDoubleOrNull()
                if (od == null || od < trip.optDouble("startOdometer")) { end.error = "Enter a valid ending odometer"; return@action }
                trip.put("endOdometer", end.text.toString()).put("endDate", now)
                store("trips", entries); show("home")
            }
        } else {
            label("Start a trip")
            val start = edit("Starting odometer", number = true)
            val destination = edit("Destination")
            val purpose = edit("Purpose of travel")
            action("Start trip") {
                if (start.text.toString().toDoubleOrNull() == null || destination.text.isBlank() || purpose.text.isBlank()) {
                    alert("Enter odometer, destination and purpose"); return@action
                }
                prefs.edit().putString("driver", driver.text.toString()).putString("vehicle", vehicle.text.toString())
                    .putString("department", dept.text.toString()).commit()
                entries.put(JSONObject().put("driver", driver.text.toString()).put("vehicle", vehicle.text.toString())
                    .put("department", dept.text.toString()).put("startDate", now)
                    .put("startOdometer", start.text.toString()).put("destination", destination.text.toString())
                    .put("purpose", purpose.text.toString()))
                store("trips", entries); show("home")
            }
        }
        label("Current sheet - " + entries.length() + " trips", 20f)
        for (i in 0 until entries.length()) {
            val j = entries.getJSONObject(i)
            label((i + 1).toString() + ". " + j.optString("destination") + " - " + j.optString("purpose") +
                "\n" + j.optString("startOdometer") + " to " + j.optString("endOdometer", "OPEN"))
        }
        action("Record fuel purchase") { show("fuel") }
        action("Preview / export vehicle usage sheet") { show("sheets") }
    }
    private fun fuel() {
        draw("Fuel receipts")
        val receipts = data("receipts")
        label("Attach a full-size receipt photograph and enter fueling information.")
        val station = edit("Gas station", pref("ocr_station"))
        val date = edit("Fuel date and time", pref("ocr_date").ifBlank { now })
        val vehicle = edit("Vehicle number", pref("vehicle"))
        val odometer = edit("Odometer at fueling", number = true)
        val gallons = edit("Total gallons", pref("ocr_gallons"), number = true)
        val price = edit("Price per gallon", pref("ocr_price"), number = true)
        val cost = edit("Total cost", pref("ocr_total"), number = true)
        label(if (pref("pending_receipt").isBlank()) "No receipt image attached" else "Receipt image attached")
        label("Receipt scanning suggests values. Check each value against the image before saving.", 14f)
        action("Take receipt photo") {
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "fuel_" + System.currentTimeMillis() + ".jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/GasReceiptTracker")
                }
                cameraUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                startActivityForResult(intent, 201)
            } catch (e: Exception) { alert("Camera unavailable: " + e.message) }
        }
        action("Choose existing receipt photo") {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
            startActivityForResult(intent, 202)
        }
        action("Save fuel receipt") {
            val selected = pref("pending_receipt")
            if (selected.isBlank() || odometer.text.toString().toDoubleOrNull() == null ||
                gallons.text.toString().toDoubleOrNull() == null || cost.text.toString().toDoubleOrNull() == null) {
                alert("Attach receipt and enter odometer, gallons, and total cost"); return@action
            }
            receipts.put(JSONObject().put("station", station.text.toString()).put("date", date.text.toString())
                .put("vehicle", vehicle.text.toString()).put("odometer", odometer.text.toString())
                .put("gallons", gallons.text.toString()).put("price", price.text.toString())
                .put("cost", cost.text.toString()).put("image", selected))
            store("receipts", receipts); prefs.edit().remove("pending_receipt").remove("ocr_station").remove("ocr_date").remove("ocr_gallons").remove("ocr_price").remove("ocr_total").commit()
            alert("Fuel receipt saved"); show("fuel")
        }
        label("Saved receipts (" + receipts.length() + ")", 20f)
        for (i in receipts.length() - 1 downTo 0) {
            val r = receipts.getJSONObject(i)
            label(r.optString("date") + " - " + r.optString("station") + "\n" +
                r.optString("gallons") + " gallons, $" + r.optString("cost") + ", Vehicle " + r.optString("vehicle"))
            action("View receipt " + (i + 1)) {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(r.getString("image")), "image/*")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (_: Exception) { alert("Receipt image unavailable") }
            }
        }
    }
    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = if (requestCode == 201) cameraUri else if (requestCode == 202) data?.data else null
        if (uri != null) {
            if (requestCode == 202) try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            prefs.edit().putString("pending_receipt", uri.toString()).commit()
            ReceiptReader.scan(this, uri) { scan ->
                if (scan != null) prefs.edit().putString("ocr_station", scan.station)
                    .putString("ocr_date", scan.date).putString("ocr_gallons", scan.gallons)
                    .putString("ocr_price", scan.price).putString("ocr_total", scan.total).commit()
                runOnUiThread { show("fuel"); alert(if (scan == null) "Receipt scan failed; enter details manually" else "Receipt scanned; please verify values") }
            }
        }
    }
    private fun sheets() {
        draw("Usage sheets / Export")
        val entries = data("trips")
        val receipts = data("receipts")
        label("Current sheet: " + entries.length() + " trips, " + receipts.length() + " saved fuel receipts")
        for (i in 0 until entries.length()) {
            val t = entries.getJSONObject(i)
            label((i + 1).toString() + ". " + t.optString("startDate") + " to " + t.optString("endDate", "OPEN") +
                "\n" + t.optString("destination") + " - " + t.optString("purpose") +
                "\n" + t.optString("startOdometer") + " to " + t.optString("endOdometer", "OPEN"))
        }
        action("Create and email usage PDF") { exportPdf(entries, receipts) }
        label("The PDF contains typed vehicle usage and receipt images. Official Excel purchase requisition export is not yet included.")
        action("Archive current sheet and start a new one") {
            var open = false
            for (i in 0 until entries.length()) if (!entries.getJSONObject(i).has("endOdometer")) open = true
            if (open) { alert("End the active trip before archiving"); return@action }
            if (entries.length() == 0) { alert("No trips to archive"); return@action }
            val history = data("archives")
            history.put(JSONObject().put("archivedAt", now).put("trips", entries).put("receipts", receipts))
            store("archives", history); store("trips", JSONArray()); store("receipts", JSONArray())
            alert("Sheet archived. New sheet started."); show("sheets")
        }
        label("Archived sheets: " + data("archives").length())
    }
    private fun exportPdf(entries: JSONArray, receipts: JSONArray) {
        if (entries.length() == 0 && receipts.length() == 0) { alert("No records to export"); return }
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 12f }
        var pageNo = 0
        fun newPage(): PdfDocument.Page {
            pageNo++
            return doc.startPage(PdfDocument.PageInfo.Builder(612, 792, pageNo).create())
        }
        fun line(canvas: android.graphics.Canvas, value: String, y: Float, size: Float = 12f) {
            paint.textSize = size
            canvas.drawText(value.take(95), 35f, y, paint)
        }
        var page = newPage()
        var canvas = page.canvas
        line(canvas, "VEHICLE USAGE SHEET", 45f, 20f)
        line(canvas, "Driver: " + pref("driver") + "   Vehicle #: " + pref("vehicle") + "   Department: " + pref("department"), 76f)
        var y = 105f
        for (i in 0 until entries.length()) {
            if (y > 665f) { doc.finishPage(page); page = newPage(); canvas = page.canvas; y = 45f }
            val t = entries.getJSONObject(i)
            line(canvas, "DRIVER: " + t.optString("driver") + "    VEHICLE: " + t.optString("vehicle") + "    PROGRAM: " + t.optString("department"), y)
            line(canvas, "START DATE/OD: " + t.optString("startDate") + " / " + t.optString("startOdometer"), y + 17)
            line(canvas, "END DATE/OD: " + t.optString("endDate", "OPEN") + " / " + t.optString("endOdometer", "OPEN"), y + 34)
            line(canvas, "DESTINATION: " + t.optString("destination"), y + 51)
            line(canvas, "PURPOSE OF TRIP: " + t.optString("purpose"), y + 68)
            canvas.drawLine(35f, y + 77, 575f, y + 77, paint)
            y += 100f
        }
        if (receipts.length() > 0) {
            val r = receipts.getJSONObject(receipts.length() - 1)
            if (y > 705f) { doc.finishPage(page); page = newPage(); canvas = page.canvas; y = 50f }
            line(canvas, "ODOMETER READING AT FUELING: " + r.optString("odometer"), y + 10)
            line(canvas, "TOTAL GALLONS: " + r.optString("gallons") + "    TOTAL COSTS: $" + r.optString("cost"), y + 30)
        }
        doc.finishPage(page)
        for (i in 0 until receipts.length()) {
            val r = receipts.getJSONObject(i)
            try {
                val bitmap = contentResolver.openInputStream(Uri.parse(r.getString("image"))).use { BitmapFactory.decodeStream(it) } ?: continue
                val receiptPage = newPage()
                line(receiptPage.canvas, "ORIGINAL RECEIPT IMAGE " + (i + 1) + " - " + r.optString("date"), 36f, 13f)
                val scale = minOf(540f / bitmap.width, 715f / bitmap.height)
                receiptPage.canvas.drawBitmap(bitmap, null,
                    android.graphics.RectF(35f, 55f, 35f + bitmap.width * scale, 55f + bitmap.height * scale), paint)
                doc.finishPage(receiptPage)
                bitmap.recycle()
            } catch (_: Exception) {}
        }
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "Vehicle_Usage_" + System.currentTimeMillis() + ".pdf")
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Downloads.RELATIVE_PATH, "Download/GasReceiptTracker")
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Cannot create PDF")
            contentResolver.openOutputStream(uri).use { output ->
                if (output == null) throw IllegalStateException("Cannot write PDF")
                doc.writeTo(output)
            }
            sendPacket(uri, receipts)
        } catch (e: Exception) { alert("PDF export failed: " + e.message) }
        finally { doc.close() }
    }
    private fun renderTemplate(template: String, receipt: JSONObject): String {
        val values = mapOf(
            "{date}" to receipt.optString("date"),
            "{vehicle}" to receipt.optString("vehicle", pref("vehicle")),
            "{driver}" to pref("driver"),
            "{program}" to pref("department"),
            "{station}" to receipt.optString("station"),
            "{amount}" to receipt.optString("cost"),
            "{gallons}" to receipt.optString("gallons"),
            "{pricePerGallon}" to receipt.optString("price"),
            "{odometer}" to receipt.optString("odometer"),
            "{cardLast4}" to pref("cardLast4")
        )
        var result = template
        for ((key, value) in values) result = result.replace(key, value)
        return result
    }
    private fun emailSettings() {
        draw("Email templates")
        label("Save reusable recipient, subject, and body. Change them at any time. The app opens your email application for final review and sending.")
        val recipient = edit("Recipient email", pref("emailTo"))
        val card = edit("Credit card last 4 digits", pref("cardLast4"))
        val subject = edit("Subject template", prefs.getString("emailSubject", "Gas Receipt {date} - Pay Credit Card {cardLast4}") ?: "")
        label("Email body template (editable):")
        val message = EditText(this)
        message.minLines = 5
        message.setSingleLine(false)
        message.setText(prefs.getString("emailBody",
            "Please find attached the fuel reimbursement packet for vehicle {vehicle}.\\nAmount: \\u0024{amount}\\nGallons: {gallons}\\nStation: {station}\\nDate: {date}\\nDriver: {driver}\\nProgram: {program}"))
        body.addView(message)
        label("Available fields: {date}, {vehicle}, {driver}, {program}, {station}, {amount}, {gallons}, {pricePerGallon}, {odometer}, {cardLast4}", 14f)
        action("Save email template") {
            prefs.edit().putString("emailTo", recipient.text.toString().trim())
                .putString("cardLast4", card.text.toString().trim())
                .putString("emailSubject", subject.text.toString())
                .putString("emailBody", message.text.toString()).commit()
            alert("Email settings saved")
        }
        action("Preview with latest fuel receipt") {
            val receipts = data("receipts")
            if (receipts.length() == 0) { alert("Save a receipt first"); return@action }
            val receipt = receipts.getJSONObject(receipts.length() - 1)
            label("Subject: " + renderTemplate(subject.text.toString(), receipt) +
                "\\n\\n" + renderTemplate(message.text.toString(), receipt), 15f)
        }
    }
    private fun sendPacket(uri: Uri, receipts: JSONArray) {
        val receipt = if (receipts.length() > 0) receipts.getJSONObject(receipts.length() - 1) else JSONObject()
        val subject = renderTemplate(prefs.getString("emailSubject",
            "Gas Receipt {date} - Pay Credit Card {cardLast4}") ?: "", receipt)
        val message = renderTemplate(prefs.getString("emailBody",
            "Please find attached the vehicle usage and fuel receipt PDF for vehicle {vehicle}. Amount: \\u0024{amount}. Gallons: {gallons}.") ?: "", receipt)
        val to = pref("emailTo")
        val email = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, message)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(email, "Email reimbursement PDF"))
    }

    private fun signatureScreen() {
        draw("Reimbursement signature")
        label("Sign with your finger or stylus. Your signature stays in the app's private storage. It must be reviewed before a reimbursement is submitted.")
        val pad = SignaturePad(this)
        body.addView(pad)
        action("Clear signature") { pad.clear() }
        action("Save signature") {
            if (!pad.hasSignature()) { alert("Please sign first"); return@action }
            try { pad.save(this); alert("Signature saved on device") }
            catch (e: Exception) { alert("Signature could not be saved: " + e.message) }
        }
        label("Signature capture is ready. Inserting it into the original official Excel purchase requisition is not yet implemented.")
    }

}
