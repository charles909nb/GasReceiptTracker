package org.karuk.gasreceipttracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.abs

/** Local-only station/field layout hints. No receipt images, values or card digits are stored. */
object ReceiptLocalLearning {
    const val KEY = "local_receipt_layout_hints"
    private fun prefs(ctx:Context)=ctx.getSharedPreferences("usage_data",Context.MODE_PRIVATE)
    private fun stationId(station:String):String {
        val normalized=station.lowercase(Locale.US).replace(Regex("[^a-z0-9]"),"").take(90)
        if(normalized.length<3)return ""
        return MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray())
            .take(12).joinToString(""){"%02x".format(it)}
    }
    fun learn(ctx:Context,station:String,field:String,box:ReceiptEvidence){
        val id=stationId(station);if(id.isBlank())return
        if(field !in setOf("date","time","fuelType","gallons","price","total","cardType","cardLast4","pump"))return
        val data=runCatching{JSONObject(prefs(ctx).getString(KEY,"{}")?:"{}")}.getOrDefault(JSONObject())
        val key=id+"_"+field
        val old=data.optJSONObject(key)
        val count=(old?.optInt("count",0)?:0).coerceIn(0,19)
        val factor=1.0/(count+1)
        fun avg(name:String,new:Float):Double=(old?.optDouble(name,new.toDouble())?:new.toDouble())*(1-factor)+new*factor
        val entry=JSONObject().put("x",avg("x",(box.left+box.right)/2))
            .put("y",avg("y",(box.top+box.bottom)/2))
            .put("w",avg("w",box.right-box.left))
            .put("h",avg("h",box.bottom-box.top))
            .put("count",count+1)
        data.put(key,entry)
        // Bound growth, retain only layout coordinates and counts.
        if(data.length()>180){val oldest=data.keys().next();data.remove(oldest)}
        check(prefs(ctx).edit().putString(KEY,data.toString()).commit()){"Could not save local learning"}
    }
    fun hint(ctx:Context,station:String,field:String):JSONObject? {
        val id=stationId(station);if(id.isBlank())return null
        return runCatching{JSONObject(prefs(ctx).getString(KEY,"{}")?:"{}")
            .optJSONObject(id+"_"+field)}.getOrNull()
    }
    fun candidate(ctx:Context,station:String,field:String,lines:List<ReceiptOcrLine>):ReceiptOcrLine?{
        val hint=hint(ctx,station,field)?:return null
        val x=hint.optDouble("x");val y=hint.optDouble("y")
        return lines.map{line->
            val bx=(line.box.left+line.box.right)/2.0
            val by=(line.box.top+line.box.bottom)/2.0
            line to (abs(bx-x)+abs(by-y)*1.5)
        }.filter{it.second<0.16}.minByOrNull{it.second}?.first
    }
    fun extract(field:String,raw:String):String {
        val text=raw.trim()
        return when(field){
            "pump"->Regex("""(?<!\\d)\\d{1,3}(?!\\d)""").find(text)?.value?:""
            "gallons"->Regex("""(\d{1,4}[.,]\d{1,3})""").find(text)?.value?.replace(',','.')?:""
            "price"->Regex("""(\d{1,3}[.,]\d{2,4})""").find(text)?.value?.replace(',','.')?:""
            "total"->Regex("""(\d{1,6}[.,]\d{2})""").findAll(text).lastOrNull()?.value?.replace(',','.')?:""
            "date"->Regex("""\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}""").find(text)?.value?:""
            "time"->Regex("""\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM)?""",RegexOption.IGNORE_CASE).find(text)?.value?:""
            "cardLast4"->Regex("""(?:[*Xx#]{2,}[-\s]*)?(\d{4})\b""").find(text)?.groupValues?.get(1)?:""
            "cardType"->Regex("""VISA|MASTER\s*CARD|AMEX|AMERICAN EXPRESS|DISCOVER""",RegexOption.IGNORE_CASE).find(text)?.value?:""
            "fuelType"->Regex("""UNLEADED|REGULAR|PREMIUM|DIESEL|MIDGRADE""",RegexOption.IGNORE_CASE).find(text)?.value?:""
            else->text.take(90)
        }
    }
    data class LearnedLayout(val key:String,val stationCode:String,val field:String,val confirmations:Int)
    fun entries(ctx:Context):List<LearnedLayout>{
        val root=runCatching{JSONObject(prefs(ctx).getString(KEY,"{}")?:"{}")}.getOrDefault(JSONObject())
        val keys=root.keys().asSequence().toList().sorted()
        return keys.mapNotNull{key->
            val entry=root.optJSONObject(key)?:return@mapNotNull null
            val underscore=key.lastIndexOf('_')
            if(underscore<=0 || underscore>=key.length-1)return@mapNotNull null
            LearnedLayout(key,key.substring(0,underscore).take(8),
                key.substring(underscore+1),entry.optInt("count",0))
        }
    }
    fun delete(ctx:Context,key:String):Boolean {
        val root=runCatching{JSONObject(prefs(ctx).getString(KEY,"{}")?:"{}")}.getOrNull()
            ?:return false
        if(!root.has(key))return false
        root.remove(key)
        return prefs(ctx).edit().putString(KEY,root.toString()).commit()
    }
    fun clear(ctx:Context)=prefs(ctx).edit().remove(KEY).commit()
}
