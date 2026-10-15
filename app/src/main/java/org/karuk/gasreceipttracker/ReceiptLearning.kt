package org.karuk.gasreceipttracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Version 2.4 local receipt correction memory.
 * A correction is only learned after the user explicitly confirms it.
 * Never memorize transaction-specific totals, dates, card numbers, or full OCR text.
 */
class ReceiptLearning(private val context: Context) {
    private val prefs = context.getSharedPreferences("receipt_learning_v24", Context.MODE_PRIVATE)
    private val key = "rules"
    private val supported = setOf("station", "date", "gallons", "price", "total", "pump")
    private fun normalizedStation(value: String) =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(80)

    fun confirmCorrection(station: String, field: String, ocrLabel: String, correctedValue: String): Boolean {
        if (field !in supported || correctedValue.isBlank() || ocrLabel.isBlank()) return false
        val stationKey = normalizedStation(station)
        if (stationKey.isBlank()) return false
        val label = ocrLabel.lowercase(Locale.US).replace(Regex("[^a-z0-9 /#-]"), "").trim().take(60)
        if (label.isBlank()) return false
        val rules = rules()
        var found = false
        for (i in 0 until rules.length()) {
            val item = rules.getJSONObject(i)
            if (item.optString("station") == stationKey &&
                item.optString("field") == field && item.optString("label") == label) {
                item.put("confirmations", (item.optInt("confirmations", 0) + 1).coerceAtMost(10000))
                found = true
                break
            }
        }
        if (!found) rules.put(JSONObject().put("station", stationKey).put("field", field)
            .put("label", label).put("confirmations", 1))
        return prefs.edit().putString(key, rules.toString()).commit()
    }

    /** Suggestions identify the field for a known label; values must be freshly OCR-read. */
    fun suggestedField(station: String, ocrLabel: String): String? {
        val stationKey = normalizedStation(station)
        val label = ocrLabel.lowercase(Locale.US).replace(Regex("[^a-z0-9 /#-]"), "").trim().take(60)
        val rules = rules()
        var best: JSONObject? = null
        for (i in 0 until rules.length()) {
            val item = rules.getJSONObject(i)
            if (item.optString("station") == stationKey && item.optString("label") == label &&
                item.optInt("confirmations") >= 2 &&
                (best == null || item.optInt("confirmations") > best.optInt("confirmations"))) best = item
        }
        return best?.optString("field")?.takeIf { it in supported }
    }

    fun learnedRuleCount(): Int = rules().length()
    fun exportRules(): String = rules().toString()
    fun importRules(json: String): Boolean {
        val parsed = runCatching { JSONArray(json) }.getOrNull() ?: return false
        if (parsed.length() > 10000) return false
        for (i in 0 until parsed.length()) {
            val item = parsed.optJSONObject(i) ?: return false
            if (item.optString("field") !in supported ||
                item.optString("station").length !in 1..80 ||
                item.optString("label").length !in 1..60 ||
                item.optInt("confirmations") !in 1..10000) return false
        }
        return prefs.edit().putString(key, parsed.toString()).commit()
    }
    fun reset(): Boolean = prefs.edit().remove(key).commit()
    private fun rules(): JSONArray = runCatching { JSONArray(prefs.getString(key, "[]")) }
        .getOrDefault(JSONArray())
}
