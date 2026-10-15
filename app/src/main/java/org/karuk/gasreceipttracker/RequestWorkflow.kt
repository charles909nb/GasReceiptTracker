package org.karuk.gasreceipttracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Atomic request state plus existing active-form keys for 1.8 compatibility.
 * A share intent is never proof of sending. Only explicit confirmation closes a request.
 */
class RequestWorkflow(private val context: Context) {
    private val prefs = context.getSharedPreferences("usage_data", Context.MODE_PRIVATE)
    companion object {
        const val STATE = "requestWorkflowV1"
        const val PENDING_EMAIL = "pendingRequestEmail"
        val DRAFT_KEYS = listOf("pending_receipt", "ocr_station", "ocr_date", "ocr_gallons", "ocr_price", "ocr_total",
            "draftOdometer", "draftStation", "draftDate", "draftGallons", "draftPrice", "draftTotal",
            "draftStartOdometer", "draftDestination", "draftPurpose", "draftEndOdometer", "draftFuelVehicle",
            "ocr_raw", "ocr_fuel_type", "ocr_card_last4", "ocr_time", "ocr_warn",
            "ocr_card_type", "ocr_evidence", "ocr_reviewed_at", "ocr_corrections",
            "ocr_card_verification", "ocr_card_mismatch_ack",
            "ocr_profile_card_last4_at_review", "ocr_profile_card_brand_at_review", "ocr_pump")
        val PROFILE_KEYS = listOf("driver", "vehicle", "department", "cardLast4", "cardBrand")
        fun stamp() = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(Date())
    }
    private fun copy(j: JSONObject) = JSONObject(j.toString())
    private fun pref(k: String) = prefs.getString(k, "").orEmpty()
    private fun array(k: String) = JSONArray(prefs.getString(k, "[]").orEmpty().ifBlank { "[]" })
    private fun fields(keys: List<String>) = JSONObject().apply { keys.forEach { put(it, pref(it)) } }
    private fun orders(s: JSONObject) = s.getJSONArray("orders")
    private fun find(s: JSONObject, id: String): JSONObject? {
        val all = orders(s)
        for (i in 0 until all.length()) if (all.getJSONObject(i).getString("id") == id) return all.getJSONObject(i)
        return null
    }
    private fun active(s: JSONObject): JSONObject = find(s, s.getString("activeId")) ?: error("Active request is missing. Restore a backup rather than clearing records.")
    private fun blank(s: JSONObject, title: String = "New travel", kind: String = "Travel"): JSONObject {
        val number = s.optInt("nextNumber", 1)
        s.put("nextNumber", number + 1)
        return JSONObject().put("id", UUID.randomUUID().toString()).put("reference", "PR-%05d".format(Locale.US, number))
            .put("title", title).put("kind", kind).put("status", "draft").put("createdAt", stamp())
            .put("profile", fields(PROFILE_KEYS)).put("drafts", JSONObject()).put("trips", JSONArray())
            .put("receipts", JSONArray()).put("signatureFile", "")
    }
    @Synchronized fun initialize() {
        if (pref(STATE).isNotBlank()) {
            val existing = load()
            TrackerDatabase(context).use { it.saveState(existing, prefs) }
            return
        }
        // If preferences were lost but the SQLite database survived,
        // restore the request queue rather than creating a blank form.
        val fromDatabase = TrackerDatabase(context).use { it.recoverState() }
        if (!fromDatabase.isNullOrBlank()) {
            val recovered = JSONObject(fromDatabase)
            require(recovered.optInt("schema") == 1) { "Unsupported request backup schema" }
            active(recovered)
            write(recovered, true)
            return
        }
        val s = JSONObject().put("schema", 1).put("nextNumber", 1).put("orders", JSONArray())
        val initial = blank(s)
        initial.put("trips", array("trips")).put("receipts", array("receipts")).put("drafts", fields(DRAFT_KEYS))
        val oldSignature = pref("signatureFile")
        if (oldSignature.isNotBlank() && File(oldSignature).isFile) initial.put("signatureFile", oldSignature)
        if (initial.getJSONArray("trips").length() > 0) initial.put("title", initial.getJSONArray("trips").getJSONObject(0).optString("destination", "Current travel"))
        orders(s).put(initial); s.put("activeId", initial.getString("id"))
        write(s, true)
    }
    private fun load(): JSONObject {
        if (pref(STATE).isBlank()) initialize()
        val s = JSONObject(pref(STATE))
        require(s.optInt("schema") == 1) { "This request format requires a newer app." }
        orders(s); active(s)
        return s
    }
    private fun capture(s: JSONObject) {
        val a = active(s)
        a.put("trips", array("trips")).put("receipts", array("receipts"))
            .put("drafts", fields(DRAFT_KEYS)).put("profile", fields(PROFILE_KEYS))
            .put("signatureFile", pref("signatureFile"))
        if(a.optString("title")=="New travel" && a.getJSONArray("trips").length()>0)
            a.put("title",a.getJSONArray("trips").getJSONObject(0).optString("destination","New travel"))
    }
    private fun write(s: JSONObject, activate: Boolean = false, extra: ((android.content.SharedPreferences.Editor) -> Unit)? = null) {
        val e = prefs.edit().putString(STATE, s.toString())
        if (activate) {
            val a = active(s)
            e.putString("trips", a.getJSONArray("trips").toString()).putString("receipts", a.getJSONArray("receipts").toString())
                .putString("signatureFile", a.optString("signatureFile"))
            DRAFT_KEYS.forEach { e.remove(it) }
            val d = a.optJSONObject("drafts") ?: JSONObject()
            DRAFT_KEYS.forEach { if (d.has(it)) e.putString(it, d.optString(it)) }
            val p = a.getJSONObject("profile")
            PROFILE_KEYS.forEach { e.putString(it, p.optString(it)) }
            e.remove("pendingSignatureAction").remove("pendingSignatureRequest").remove("reviewedRequestFingerprint")
        }
        extra?.invoke(e)
        check(e.commit()) { "The phone could not save the request. Nothing should be deleted; free some storage and retry." }
        // Keep a transactionally indexed copy for offline searching and recovery.
        TrackerDatabase(context).use { it.saveState(s, prefs) }
    }
    @Synchronized fun sync() { val s = load(); capture(s); write(s) }
    @Synchronized fun current(): JSONObject { val s = load(); capture(s); return copy(active(s)) }
    @Synchronized fun all(): JSONArray { val s = load(); capture(s); return JSONArray(orders(s).toString()) }
    fun hasOpenTrip(a: JSONObject = current()): Boolean {
        val t = a.optJSONArray("trips") ?: JSONArray()
        return (0 until t.length()).any { !t.getJSONObject(it).has("endOdometer") }
    }
    fun hasWork(a: JSONObject): Boolean {
        if (a.getJSONArray("trips").length() > 0 || a.getJSONArray("receipts").length() > 0) return true
        val d = a.optJSONObject("drafts") ?: return false
        return listOf("pending_receipt", "draftDestination", "draftPurpose", "ocr_total").any { d.optString(it).isNotBlank() }
    }
    @Synchronized fun create(title: String, kind: String, activate: Boolean = true): JSONObject {
        require(title.isNotBlank()) { "Enter a request title or destination." }
        require(kind in listOf("Travel", "Work order"))
        check(pref(PENDING_EMAIL).isBlank()) { "Confirm whether the pending email was sent before changing requests." }
        val s = load(); capture(s)
        check(!activate || !hasOpenTrip(active(s))) { "End the current trip before starting another request. You may queue it instead." }
        val a = active(s)
        if (activate && !hasWork(a) && a.optString("signatureFile").isBlank()) {
            a.put("title", title.trim()).put("kind", kind)
            if (kind == "Travel") a.getJSONObject("drafts").put("draftDestination", title.trim())
            write(s, true)
            TrackerDatabase(context).use { it.log(a.getString("id"), "request_started", title.trim()) }
            return copy(a)
        }
        val next = blank(s, title.trim(), kind)
        if (kind == "Travel") next.getJSONObject("drafts").put("draftDestination", title.trim())
        orders(s).put(next)
        if (activate) s.put("activeId", next.getString("id"))
        write(s, activate)
        TrackerDatabase(context).use { it.log(next.getString("id"), if(activate) "request_started" else "request_queued", title.trim()) }
        return copy(next)
    }
    @Synchronized fun select(id: String) {
        val s = load(); capture(s)
        if (s.getString("activeId") == id) return
        check(pref(PENDING_EMAIL).isBlank()) { "Confirm the pending email first." }
        check(!hasOpenTrip(active(s))) { "End your active trip before switching requests." }
        val target = find(s, id) ?: error("Request not found")
        check(target.optString("status") == "draft") { "Completed requests are read-only in History." }
        s.put("activeId", id); write(s, true)
    }
    /** Amend a saved receipt in-place without changing its ID, photo, original Finance PDF
     * or unrelated requests. The archived sent packet remains an immutable snapshot. */
    @Synchronized fun correctSavedReceipt(
        requestId: String, receiptId: String, updates: JSONObject
    ): JSONObject {
        val state = load()
        capture(state)
        check(pendingEmail()?.optString("requestId") != requestId) {
            "Finish or cancel the pending Finance email before correcting this receipt."
        }
        val request = find(state, requestId) ?: error("The original request cannot be found.")
        val receipts = request.optJSONArray("receipts") ?: error("This request has no saved receipts.")
        val receipt = (0 until receipts.length()).map { receipts.getJSONObject(it) }
            .firstOrNull { it.optString("id") == receiptId }
            ?: error("The original saved receipt cannot be found.")
        val allowed = setOf("station", "date", "receiptTime", "fuelType", "gallons", "price",
            "cost", "pumpNumber", "cardType", "receiptCardLast4", "cardLast4", "cardVerificationStatus",
            "cardMismatchAcknowledged", "reviewedAt", "ocrCorrections", "ocrEvidence")
        for (key in allowed) if (updates.has(key)) receipt.put(key, updates.get(key))
        val revision = JSONObject().put("at", stamp())
            .put("changedFields", updates.optJSONArray("changedFields") ?: JSONArray())
            .put("financePacketPreviouslySent", request.optString("status") == "sent")
        val history = receipt.optJSONArray("correctionHistory") ?: JSONArray()
        history.put(revision)
        receipt.put("correctionHistory", history)
        if (request.optString("status") == "sent")
            receipt.put("correctedAfterFinanceSubmission", true)
        val activeId = state.optString("activeId")
        write(state, false) { editor ->
            if (activeId == requestId) editor.putString("receipts", receipts.toString())
            editor.remove("reviewedRequestFingerprint")
        }
        TrackerDatabase(context).use { it.log(requestId, "saved_receipt_corrected",
            "Receipt $receiptId corrected; original image and sent packet preserved.") }
        return copy(receipt)
    }

    @Synchronized fun saveSignature(source: File): File {
        val s = load(); capture(s); val a = active(s)
        val destination = File(context.filesDir, "signature_" + a.getString("id") + "_" + System.currentTimeMillis() + ".png")
        source.copyTo(destination, overwrite = false)
        a.put("signatureFile", destination.absolutePath).put("signedAt", stamp())
        write(s, false) { it.putString("signatureFile", destination.absolutePath).remove("reviewedRequestFingerprint") }
        TrackerDatabase(context).use { it.log(a.getString("id"), "signature_saved", "Handwritten signature captured") }
        return destination
    }
    fun signed(): Boolean = pref("signatureFile").let { it.isNotBlank() && File(it).isFile && File(it).length() > 0 }
    fun fingerprint(a: JSONObject): String {
        val values = listOf("id", "profile", "trips", "receipts", "signatureFile").joinToString("|") { a.opt(it).toString() }
        return MessageDigest.getInstance("SHA-256").digest(values.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    @Synchronized fun stageEmail(uri: String, subject: String, to: String, cc: String): JSONObject {
        check(pref(PENDING_EMAIL).isBlank()) { "A previous email still needs your confirmation." }
        val s = load(); capture(s); val a = active(s)
        check(!hasOpenTrip(a)) { "Finish the current trip before sending its request." }
        check(a.getJSONArray("receipts").length() == 1) { "This request needs one fuel receipt." }
        check(signed()) { "Sign this request first." }
        val pending = JSONObject().put("token", UUID.randomUUID().toString()).put("requestId", a.getString("id"))
            .put("reference", a.getString("reference")).put("fingerprint", fingerprint(a)).put("uri", uri)
            .put("subject", subject).put("to", to).put("cc", cc).put("preparedAt", stamp())
        write(s, false) { it.putString(PENDING_EMAIL, pending.toString()).putBoolean("requestEmailLeftApp", false) }
        return pending
    }
    fun pendingEmail(): JSONObject? = pref(PENDING_EMAIL).takeIf { it.isNotBlank() }?.let { JSONObject(it) }
    @Synchronized fun cancelEmail() {
        check(prefs.edit().remove(PENDING_EMAIL).remove("requestEmailLeftApp").commit()) { "Could not save email status." }
    }
    @Synchronized fun confirmSent(token: String): JSONObject {
        if (pref("lastConfirmedEmailToken") == token) return current()
        val pending = pendingEmail() ?: error("No email is awaiting confirmation.")
        check(pending.getString("token") == token) { "That email confirmation is no longer current." }
        val s = load(); capture(s)
        val a = find(s, pending.getString("requestId")) ?: error("Request not found")
        check(fingerprint(a) == pending.getString("fingerprint")) { "This request changed after preparing the email. Keep it open, review the changes and prepare a new email." }
        check(a.optString("status") == "draft") { "Request already completed." }
        val archiveUri=FinancePacketArchive.stableCopy(context,a.getString("id"),android.net.Uri.parse(pending.getString("uri")))
        a.put("status", "sent").put("sentAt", stamp()).put("sendConfirmation", "confirmed_by_user")
            .put("sentPdfUri", archiveUri.toString()).put("email", pending)
        val archives = array("archives")
        archives.put(copy(a).put("archivedAt", stamp()))
        moveToNext(s)
        write(s, true) { it.putString("archives", archives.toString()).remove(PENDING_EMAIL)
            .remove("requestEmailLeftApp").putString("lastConfirmedEmailToken", token) }
        TrackerDatabase(context).use { it.log(a.getString("id"), "email_confirmed_sent", pending.optString("subject")) }
        return copy(active(s))
    }
    private fun moveToNext(s: JSONObject) {
        val list = orders(s)
        val next = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { it.optString("status") == "draft" }
            ?: blank(s).also { list.put(it) }
        s.put("activeId", next.getString("id"))
    }
    @Synchronized fun archiveCurrent() {
        check(pref(PENDING_EMAIL).isBlank()) { "Confirm the pending email first." }
        val s = load(); capture(s); val a = active(s)
        check(!hasOpenTrip(a)) { "End the active trip before archiving." }
        check(hasWork(a)) { "This request is empty." }
        a.put("status", "archived").put("archivedAt", stamp())
        val archive = array("archives"); archive.put(copy(a))
        moveToNext(s); write(s, true) { it.putString("archives", archive.toString()) }
        TrackerDatabase(context).use { it.log(a.getString("id"), "archived_without_sending") }
    }
}
