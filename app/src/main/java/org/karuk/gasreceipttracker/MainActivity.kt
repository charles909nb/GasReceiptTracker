package org.karuk.gasreceipttracker

import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
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
import android.text.TextWatcher
import android.text.Editable
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("usage_data", MODE_PRIVATE) }
    private lateinit var body: LinearLayout
    private var cameraUri: Uri? = null
    private var receiptUri: Uri? = null
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
        label("Gas Receipt Tracker - v1.6", 23f)
        val nav = LinearLayout(this); nav.orientation = LinearLayout.HORIZONTAL
        for ((caption, dest) in listOf("Trips" to "home", "Fuel" to "fuel", "Sheets" to "sheets", "Email" to "email", "Sign" to "sign", "Backup" to "backup")) {
            val b = Button(this); b.text = caption; nav.addView(b, LinearLayout.LayoutParams(-2, -2))
            b.setOnClickListener { show(dest) }
        }
        val navScroll = HorizontalScrollView(this); navScroll.isHorizontalScrollBarEnabled = false
        navScroll.addView(nav); body.addView(navScroll); label(title, 22f)
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); show("home") }
    private fun show(where: String) {
        when(where) { "fuel" -> fuel(); "sheets" -> sheets(); "email" -> emailSettings(); "sign" -> signatureScreen(); "backup" -> backupScreen(); else -> trips() }
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
    private fun autoSaveField(field: EditText, key: String) {
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                prefs.edit().putString(key, s?.toString().orEmpty()).apply()
            }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    private fun fuel() {
        draw("Fuel receipts")
        val receipts = data("receipts")
        label("Attach a full-size receipt photograph and enter fueling information.")
        val station = edit("Gas station", pref("ocr_station"))
        val date = edit("Fuel date and time", pref("ocr_date").ifBlank { now })
        val vehicle = edit("Vehicle number", pref("vehicle"))
        val odometer = edit("Odometer at fueling", pref("draftOdometer"), number = true)
        val gallons = edit("Total gallons", pref("ocr_gallons"), number = true)
        val price = edit("Price per gallon", pref("ocr_price"), number = true)
        val cost = edit("Total cost", pref("ocr_total"), number = true)
        autoSaveField(station, "ocr_station")
        autoSaveField(date, "ocr_date")
        autoSaveField(odometer, "draftOdometer")
        autoSaveField(gallons, "ocr_gallons")
        autoSaveField(price, "ocr_price")
        autoSaveField(cost, "ocr_total")
        label("Entries are saved automatically as you type.", 14f)
        label(if (pref("pending_receipt").isBlank()) "No receipt image attached" else "Receipt image attached")
        label("Receipt scanning suggests values. Check each value against the image before saving.", 14f)
        action("Scan and crop receipt automatically") {
            val options = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setPageLimit(1)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
                .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
                .build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(this)
                .addOnSuccessListener { sender ->
                    try { startIntentSenderForResult(sender, 203, null, 0, 0, 0) }
                    catch (e: Exception) { alert("Scanner could not open: " + e.message) }
                }.addOnFailureListener { alert("Scanner unavailable. Use camera or gallery.") }
        }
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
        action("Read receipt photo (OCR)") {
            val image = pref("pending_receipt")
            if (image.isBlank()) { alert("Photograph or choose a receipt first"); return@action }
            ReceiptReader.scan(this, Uri.parse(image)) { result ->
                if (result == null) { alert("Unable to read receipt. Enter values manually."); return@scan }
                station.setText(result.station)
                if (result.date.isNotBlank()) date.setText(result.date)
                if (result.gallons.isNotBlank()) gallons.setText(result.gallons)
                if (result.price.isNotBlank()) price.setText(result.price)
                if (result.total.isNotBlank()) cost.setText(result.total)
                prefs.edit().putString("draftStation", station.text.toString())
                    .putString("draftDate", date.text.toString()).putString("draftGallons", gallons.text.toString())
                    .putString("draftPrice", price.text.toString()).putString("draftTotal", cost.text.toString()).commit()
                alert("Receipt scanned. Check and correct the values before saving.")
            }
        }
        action("Save fuel draft") {
            prefs.edit().putString("ocr_station", station.text.toString())
                .putString("ocr_date", date.text.toString())
                .putString("ocr_gallons", gallons.text.toString())
                .putString("ocr_price", price.text.toString())
                .putString("ocr_total", cost.text.toString())
                .putString("draftOdometer", odometer.text.toString()).commit()
            alert("Fuel draft saved on this phone")
        }
        action("Save fuel receipt") {
            val selected = pref("pending_receipt")
            if (selected.isBlank() || (odometer.text.toString().toDoubleOrNull() ?: -1.0) < 0.0 ||
                (gallons.text.toString().toDoubleOrNull() ?: 0.0) <= 0.0 ||
                (cost.text.toString().toDoubleOrNull() ?: 0.0) <= 0.0) {
                alert("Attach receipt and enter odometer, gallons, and total cost"); return@action
            }
            receipts.put(JSONObject().put("station", station.text.toString()).put("date", date.text.toString())
                .put("vehicle", vehicle.text.toString()).put("odometer", odometer.text.toString())
                .put("gallons", gallons.text.toString()).put("price", price.text.toString())
                .put("cost", cost.text.toString()).put("image", selected))
            store("receipts", receipts); prefs.edit().remove("pending_receipt").remove("ocr_station").remove("ocr_date").remove("ocr_gallons").remove("ocr_price").remove("ocr_total").remove("draftOdometer").commit()
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
        if (requestCode == 207) {
            try { TrackerBackup.restore(this, requireNotNull(data?.data)); show("home"); alert("Backup restored") }
            catch (e: Exception) { alert("Restore failed: " + e.message) }
            return
        }
        if (requestCode == 206) {
            try { TrackerBackup.export(this, requireNotNull(data?.data)); alert("Backup saved") }
            catch (e: Exception) { alert("Backup failed: " + e.message) }
            return
        }
        if (requestCode == 205) {
            val selected = data?.data ?: return
            try {
                ReimbursementPacket.importOfficialPdf(this, selected)
                alert("Official requisition PDF imported")
            } catch (e: Exception) { alert("PDF import failed: " + e.message) }
            show("sheets")
            return
        }
        if (requestCode == 204) {
            val chosen = data?.data ?: return
            try {
                OfficialPurchaseRequisition.saveTemplate(this, chosen)
                alert("Official purchase requisition template saved")
            } catch (e: Exception) { alert("Template import failed: " + e.message) }
            show("sheets")
            return
        }
        val uri = when (requestCode) {
            201 -> cameraUri
            202 -> data?.data
            203 -> GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages?.firstOrNull()?.imageUri
            else -> null
        }
        if (uri != null) {
            if (requestCode == 202) try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            try {
                // Scanner URIs can expire. Keep a private copy before OCR and later PDF export.
                val target = java.io.File(filesDir, "receipt_" + System.currentTimeMillis() + ".jpg")
                contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Receipt image cannot be opened" }
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                val saved = Uri.fromFile(target)
                prefs.edit().putString("pending_receipt", saved.toString())
                    .remove("ocr_station").remove("ocr_date").remove("ocr_gallons")
                    .remove("ocr_price").remove("ocr_total").commit()
                ReceiptReader.scan(this, saved) { scan ->
                    runOnUiThread {
                        if (scan != null) prefs.edit().putString("ocr_station", scan.station)
                            .putString("ocr_date", scan.date).putString("ocr_gallons", scan.gallons)
                            .putString("ocr_price", scan.price).putString("ocr_total", scan.total).commit()
                        show("fuel")
                        alert(if (scan == null) "Receipt scan failed; enter details manually"
                              else "Receipt scanned; please verify values")
                    }
                }
            } catch (e: Exception) { alert("Could not save receipt: " + e.message) }
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
        action("Import original Karuk Excel Purchase Requisition (.xls)") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 204)
        }
        label(if (java.io.File(filesDir, "Karuk_Purchase_Requisitions_Original.xls").exists())
            "Official Excel template imported" else "Official Excel template not imported")
        action("Create signed original Excel Purchase Requisition") {
            if (receipts.length() == 0) { alert("Save a fuel receipt first"); return@action }
            try {
                val receipt = receipts.getJSONObject(receipts.length() - 1)
                val sig = pref("signatureFile").takeIf { it.isNotBlank() }?.let { java.io.File(it) }
                val uri = OfficialPurchaseRequisition.export(this, receipt, pref("driver"), pref("cardLast4"), sig)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.ms-excel"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, "Share original signed Excel requisition"))
            } catch (e: Exception) { alert("Excel export failed: " + e.message) }
        }
        action("Import official Karuk Purchase Requisition PDF") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                .setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE), 205)
        }
        label(if (ReimbursementPacket.hasTemplate(this))
            "Official requisition PDF is available" else "Official requisition PDF not imported")
        action("Generate and email complete reimbursement packet") {
            try {
                val pdf = ReimbursementPacket.create(this, entries, receipts,
                    pref("driver"), pref("cardLast4"), pref("signatureFile"))
                sendPacket(pdf, receipts)
            } catch (e: Exception) { alert("Packet creation failed: " + e.message) }
        }
        action("Create and email usage PDF") { exportPdf(entries, receipts) }
        label("Review the generated packet before emailing. The official requisition field alignment still requires verification.")
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
        val archives = data("archives")
        label("Archived sheets: " + archives.length())
        for (i in archives.length() - 1 downTo 0) {
            val archive = archives.optJSONObject(i) ?: continue
            label("Archived " + archive.optString("archivedAt") +
                " - " + archive.optJSONArray("trips")?.length() + " trips")
            action("Export archived sheet " + (i + 1)) {
                exportPdf(archive.optJSONArray("trips") ?: JSONArray(),
                    archive.optJSONArray("receipts") ?: JSONArray())
            }
        }
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
        for (i in 0 until receipts.length()) {
            val r = receipts.getJSONObject(i)
            try {
                val imageUri = Uri.parse(r.getString("image"))
                val bitmap = if (imageUri.scheme == "file") {
                    BitmapFactory.decodeFile(imageUri.path)
                } else {
                    contentResolver.openInputStream(imageUri).use { BitmapFactory.decodeStream(it) }
                } ?: continue
                val receiptPage = newPage()
                line(receiptPage.canvas, "ORIGINAL RECEIPT IMAGE " + (i + 1) + " - " + r.optString("date"), 36f, 13f)
                val scale = minOf(540f / bitmap.width, 715f / bitmap.height)
                receiptPage.canvas.drawBitmap(bitmap, null,
                    android.graphics.RectF(35f, 55f, 35f + bitmap.width * scale, 55f + bitmap.height * scale), paint)
                doc.finishPage(receiptPage)
                bitmap.recycle()
            } catch (_: Exception) {}
        }
        VehicleUsagePdf.append(doc, entries, receipts, pageNo + 1)
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
    private fun backupScreen() {
        draw("Backup and restore")
        label("Export all trip records, fuel receipts, signature and email templates to one ZIP file. Save it somewhere safe before changing phones.")
        action("Save complete backup") {
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                .setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(Intent.EXTRA_TITLE, "GasReceiptTracker_Backup.zip"), 206)
        }
        action("Restore saved backup") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Restore backup?")
                .setMessage("This replaces current saved trip and receipt records. Export a backup first if you want to keep them.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Continue") { _, _ ->
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*").addCategory(Intent.CATEGORY_OPENABLE), 207)
                }.show()
        }
    }
    private fun signatureScreen() {
        draw("Purchase requisition signature")
        label("Sign with your finger or stylus. This signature is saved privately for use on your purchase requisition.")
        val pad = SignaturePad(this)
        body.addView(pad)
        action("Clear signature") { pad.clear() }
        action("Save signature") {
            if (!pad.hasSignature()) { alert("Please sign first"); return@action }
            try {
                val file = pad.save(this)
                prefs.edit().putString("signatureFile", file.absolutePath).commit()
                alert("Signature saved for purchase requisition")
            } catch (e: Exception) { alert("Could not save signature: " + e.message) }
        }
        label(if (pref("signatureFile").isNotBlank()) "Saved signature available" else "No saved signature")
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

}
