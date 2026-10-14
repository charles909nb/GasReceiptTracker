package org.karuk.gasreceipttracker

import org.json.JSONObject
import java.util.Locale

/** Human-readable exported document names; never changes the original official worksheet content. */
object PurchaseDocumentNames {
    private fun date(receipt: JSONObject): String {
        val value=receipt.optString("date").trim()
        val iso=Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})$""").matchEntire(value)
        if(iso!=null)return "%04d-%02d-%02d".format(Locale.US,iso.groupValues[1].toInt(),iso.groupValues[2].toInt(),iso.groupValues[3].toInt())
        val us=Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""").matchEntire(value)
        if(us!=null)return "%04d-%02d-%02d".format(Locale.US,us.groupValues[3].toInt(),us.groupValues[1].toInt(),us.groupValues[2].toInt())
        return value.replace(Regex("[^A-Za-z0-9-]+"),"_").take(24).ifBlank { "Undated" }
    }
    private fun vehicle(receipt: JSONObject): String =
        receipt.optString("vehicle").trim().replace(Regex("[^A-Za-z0-9-]+"),"_").take(32).ifBlank { "NoVehicle" }
    private fun base(receipt: JSONObject) = "${date(receipt)}_Purchase_Request_Fuel_${vehicle(receipt)}"
    fun fuelPdfName(receipt: JSONObject): String = "${base(receipt)}.pdf"
    fun fuelExcelName(receipt: JSONObject): String = "${base(receipt)}.xls"
}
