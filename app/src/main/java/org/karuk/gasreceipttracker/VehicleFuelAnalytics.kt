package org.karuk.gasreceipttracker

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Calculated only from consecutive fill-ups for one vehicle.
 * Assumes tanks are filled consistently; partial fills distort MPG.
 * A warning is a maintenance screening signal, not a diagnosis.
 */
object VehicleFuelAnalytics {
    data class Fill(val vehicle:String,val date:String,val odometer:Double,
        val gallons:Double,val total:Double,val receiptId:String)
    data class Point(val date:String,val odometer:Double,val miles:Double,
        val gallons:Double,val mpg:Double,val baseline:Double?,val warning:Boolean,
        val receiptId:String)
    fun fills(orders:JSONArray):List<Fill>{
        val result=mutableListOf<Fill>()
        for(i in 0 until orders.length()){
            val order=orders.optJSONObject(i)?:continue
            val receipts=order.optJSONArray("receipts")?:continue
            for(j in 0 until receipts.length()){
                val r=receipts.optJSONObject(j)?:continue
                val vehicle=r.optString("vehicle").trim()
                val odo=r.optString("odometer").toDoubleOrNull()
                val gallons=r.optString("gallons").toDoubleOrNull()
                val cost=r.optString("cost").toDoubleOrNull()?:0.0
                if(vehicle.isBlank()||odo==null||gallons==null||!odo.isFinite()||
                    !gallons.isFinite()||odo<0||gallons<=0)continue
                result.add(Fill(vehicle,r.optString("date"),odo,gallons,cost,r.optString("id")))
            }
        }
        return result
    }
    fun points(fills:List<Fill>,vehicle:String):List<Point>{
        val ordered=fills.filter{it.vehicle==vehicle}.sortedWith(
            compareBy<Fill>{FinancePacketArchive.dateMillis(it.date)}
                .thenBy{it.odometer})
        val points=mutableListOf<Point>()
        var previous:Fill?=null
        for(current in ordered){
            val prior=previous
            previous=current
            if(prior==null)continue
            val miles=current.odometer-prior.odometer
            if(miles<=0||miles>2000)continue // avoid duplicates and implausible jumps
            val mpg=miles/current.gallons
            if(!mpg.isFinite()||mpg<1||mpg>100)continue
            val recent=points.takeLast(3).map{it.mpg}
            val baseline=if(recent.size>=3)recent.average()else null
            val warning=baseline!=null && mpg<baseline*0.8 && baseline-mpg>=2.0
            points.add(Point(current.date,current.odometer,miles,current.gallons,
                mpg,baseline,warning,current.receiptId))
        }
        return points
    }
    fun summary(points:List<Point>):String {
        if(points.isEmpty())return "At least two valid fueling odometer readings are needed."
        val latest=points.last()
        val mean=points.map{it.mpg}.average()
        return String.format(Locale.US,"Latest %.1f MPG  •  Overall %.1f MPG  •  %d intervals",
            latest.mpg,mean,points.size)
    }
}
