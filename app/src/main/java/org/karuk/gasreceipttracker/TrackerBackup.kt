package org.karuk.gasreceipttracker

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable backup includes private receipt photographs and the signature. */
object TrackerBackup {
    private val keys = listOf("driver","vehicle","department","trips","receipts","archives",
        "emailTo","emailSubject","emailBody","cardLast4","signatureFile",
        "pending_receipt","ocr_station","ocr_date","ocr_gallons","ocr_price","ocr_total","draftOdometer")
    private fun safeName(name: String): Boolean =
        name.matches(Regex("""(?:receipt_[0-9]+\.jpg|reimbursement_signature\.png|Karuk_Purchase_Requisitions_Original\.xls|Karuk_Purchase_Requisition_Official\.pdf)"""))
    fun export(context: Context, destination: Uri) {
        val prefs = context.getSharedPreferences("usage_data", Context.MODE_PRIVATE)
        val json = JSONObject().put("format", 1)
        for (key in keys) json.put(key, prefs.getString(key, ""))
        context.contentResolver.openOutputStream(destination)?.use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("data.json"))
                zip.write(json.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                context.filesDir.listFiles()?.filter { it.isFile && safeName(it.name) }?.forEach { file ->
                    zip.putNextEntry(ZipEntry("files/" + file.name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        } ?: error("Cannot write backup")
    }
    fun restore(context: Context, source: Uri) {
        val files = mutableListOf<Pair<String, ByteArray>>()
        var json: JSONObject? = null
        context.contentResolver.openInputStream(source)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (name == "data.json") {
                        json = JSONObject(zip.readBytes().toString(Charsets.UTF_8))
                    } else if (name.startsWith("files/") && safeName(name.removePrefix("files/"))) {
                        val bytes = zip.readBytes()
                        require(bytes.size <= 20_000_000) { "Image in backup is too large" }
                        files.add(name.removePrefix("files/") to bytes)
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } ?: error("Cannot open backup")
        val restored = requireNotNull(json) { "Missing backup data" }
        require(restored.optInt("format") == 1) { "Unsupported backup version" }
        for (key in listOf("trips","receipts","archives")) JSONArray(restored.optString(key, "[]"))
        files.forEach { (name, bytes) -> File(context.filesDir, name).writeBytes(bytes) }
        val edit = context.getSharedPreferences("usage_data", Context.MODE_PRIVATE).edit()
        // Signature location must be reconstructed for the destination phone.
        if (File(context.filesDir, "reimbursement_signature.png").exists())
            edit.putString("signatureFile", File(context.filesDir, "reimbursement_signature.png").absolutePath)
        for (key in keys) if (key != "signatureFile" && restored.has(key)) edit.putString(key, restored.optString(key))
        edit.commit()
    }
}
