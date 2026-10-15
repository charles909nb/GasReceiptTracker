package org.karuk.gasreceipttracker

import org.json.JSONArray
import org.json.JSONObject
import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * Links a single saved fuel receipt to at most one trip, without copying transactions.
 * Matches only when date, vehicle AND completed odometer range identify one trip.
 * Entry timestamps are not trusted as proof of actual travel time for late entries.
 */
object TripReceiptLinker {
    private val dateFormats = listOf(
        "MMM d, yyyy h:mm a", "MMMM d, yyyy h:mm a",
        "MMM d, yyyy", "MMMM d, yyyy",
        "M/d/yyyy", "MM/dd/yyyy", "M/d/yy", "MM/dd/yy",
        "M-d-yyyy", "yyyy-MM-dd"
    )
    fun parseDate(text:String):LocalDate? {
        val value=text.trim()
        if(value.isBlank())return null
        for(pattern in dateFormats){
            val fmt=SimpleDateFormat(pattern,Locale.US).apply { isLenient=false }
            val cursor=ParsePosition(0)
            val result=fmt.parse(value,cursor) ?: continue
            if(cursor.index==value.length)
                return Instant.ofEpochMilli(result.time).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        return null
    }
    fun ensureTripIds(trips:JSONArray):Boolean {
        var changed=false
        for(i in 0 until trips.length()) {
            val t=trips.optJSONObject(i)?:continue
            if(t.optString("id").isBlank()) { t.put("id",UUID.randomUUID().toString()); changed=true }
        }
        return changed
    }
    private fun sameVehicle(a:String,b:String):Boolean =
        a.trim().isNotBlank() && a.trim().equals(b.trim(),ignoreCase=true)

    /** An open trip is not enough to establish a bounded mileage range. */
    fun fits(trip:JSONObject,receipt:JSONObject):Boolean {
        if(!sameVehicle(trip.optString("vehicle"),receipt.optString("vehicle")))return false
        val start=trip.optString("startOdometer").toDoubleOrNull() ?:return false
        val end=trip.optString("endOdometer").toDoubleOrNull() ?:return false
        val fuel=receipt.optString("odometer").toDoubleOrNull() ?:return false
        if(!start.isFinite()||!end.isFinite()||!fuel.isFinite()||start<0||end<start||fuel<start||fuel>end)return false
        val purchase=parseDate(receipt.optString("date")) ?:return false
        val tripActual=trip.optString("tripDate")
        val begin=parseDate(tripActual.ifBlank{trip.optString("startDate")}) ?:return false
        val finish=if(tripActual.isNotBlank()) {
            // A retroactive trip has an explicitly selected travel date, not the
            // possibly much later date when somebody entered the end mileage.
            parseDate(trip.optString("endTravelDate")).takeIf{it!=null} ?: begin
        } else parseDate(trip.optString("endDate")) ?:return false
        return !purchase.isBefore(begin) && !purchase.isAfter(finish)
    }
    private fun history(receipt:JSONObject,from:String,to:String,source:String){
        val rows=receipt.optJSONArray("tripLinkHistory") ?:JSONArray()
        if(rows.length()<50)rows.put(JSONObject()
            .put("from",from).put("to",to).put("source",source)
            .put("at",RequestWorkflow.stamp()))
        receipt.put("tripLinkHistory",rows)
    }
    /** Auto-assign only unique, fully supported matches. Manual links are never overridden. */
    fun autoLink(trips:JSONArray,receipts:JSONArray):Int {
        ensureTripIds(trips)
        var linked=0
        for(i in 0 until receipts.length()){
            val r=receipts.optJSONObject(i)?:continue
            val current=r.optString("tripId")
            if(current.isNotBlank()){
                if(r.optString("tripLinkSource")=="auto"){
                    val existing=(0 until trips.length()).mapNotNull{trips.optJSONObject(it)}
                        .firstOrNull{it.optString("id")==current}
                    if(existing!=null && !fits(existing,r)) {
                        history(r,current,"","auto_removed")
                        r.remove("tripId");r.remove("tripLinkSource")
                    } else continue
                } else continue
            }
            if(r.optBoolean("tripLinkManuallyRemoved"))continue
            val candidates=(0 until trips.length()).mapNotNull{trips.optJSONObject(it)}
                .filter{fits(it,r)}
            if(candidates.size==1){
                val to=candidates.single().optString("id")
                if(to.isNotBlank()) {
                    r.put("tripId",to).put("tripLinkSource","auto")
                    history(r,"",to,"auto")
                    linked++
                }
            }
        }
        return linked
    }
    /** The user can link late, move, or detach without changing receipt identity/cost. */
    fun manualLink(trips:JSONArray,receipts:JSONArray,tripIndex:Int,receiptId:String){
        require(tripIndex in 0 until trips.length()){"Choose a saved trip."}
        ensureTripIds(trips)
        val id=trips.getJSONObject(tripIndex).getString("id")
        val target=(0 until receipts.length()).mapNotNull{receipts.optJSONObject(it)}
            .firstOrNull{it.optString("id")==receiptId} ?:error("Receipt no longer available")
        val old=target.optString("tripId")
        if(old!=id){
            history(target,old,id,"manual")
            target.put("tripId",id)
        }
        target.put("tripLinkSource","manual")
        target.remove("tripLinkManuallyRemoved")
    }
    fun manualUnlink(receipts:JSONArray,receiptId:String){
        val target=(0 until receipts.length()).mapNotNull{receipts.optJSONObject(it)}
            .firstOrNull{it.optString("id")==receiptId} ?:error("Receipt no longer available")
        val old=target.optString("tripId")
        if(old.isNotBlank())history(target,old,"","manual_removed")
        target.remove("tripId");target.remove("tripLinkSource")
        target.put("tripLinkManuallyRemoved",true)
    }
    fun linkedTo(trip:JSONObject,receipts:JSONArray):List<JSONObject>{
        val id=trip.optString("id")
        if(id.isBlank())return emptyList()
        return (0 until receipts.length()).mapNotNull{receipts.optJSONObject(it)}
            .filter{it.optString("tripId")==id}
    }
}