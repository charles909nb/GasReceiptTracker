package org.karuk.gasreceipttracker

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A receipt is a real, searchable, backupable record as soon as a scan exists.
 * Missing fields affect its status, never whether its photograph/OCR is saved.
 * Updating a draft always retains its ID and original creation timestamp.
 */
object FuelReceiptDraft {
    fun missing(receipt: JSONObject): List<String> = buildList {
        if (receipt.optString("image").isBlank()) add("Receipt photo")
        if (receipt.optString("vehicle").isBlank() ||
            receipt.optString("vehicle").startsWith("Choose")) add("Vehicle number")
        val odo = receipt.optString("odometer").toDoubleOrNull()
        if (odo == null || !odo.isFinite() || odo < 0) add("Odometer reading")
        if (receipt.optString("date").isBlank()) add("Receipt date")
        val gallons = receipt.optString("gallons").toDoubleOrNull()
        if (gallons == null || !gallons.isFinite() || gallons <= 0) add("Gallons")
        val total = receipt.optString("cost").toDoubleOrNull()
        if (total == null || !total.isFinite() || total <= 0) add("Total cost")
        if (receipt.optString("cardVerificationStatus") == "MISMATCH" &&
            !receipt.optBoolean("cardMismatchAcknowledged")) add("Payment card check")
    }

    fun update(existing: JSONObject?, values: JSONObject, finishIfComplete: Boolean = false): JSONObject {
        val receipt = if (existing == null) JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("savedAt", RequestWorkflow.stamp())
        else JSONObject(existing.toString())
        for (key in values.keys()) receipt.put(key, values.get(key))
        val absent = missing(receipt)
        receipt.put("status", if (finishIfComplete && absent.isEmpty()) "complete" else "draft")
        receipt.put("missingFields", JSONArray().also { fields -> absent.forEach { fields.put(it) } })
        receipt.put("updatedAt", RequestWorkflow.stamp())
        return receipt
    }

    fun complete(receipt: JSONObject): Boolean = receipt.optString("status", "complete") == "complete"
}