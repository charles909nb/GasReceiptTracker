package org.karuk.gasreceipttracker

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Conservative full-to-full gas mileage, not gallons per individual receipt.
 * Partial fills are accumulated. An unverified/unknown fill interrupts the
 * calculation so it cannot cause a false maintenance warning.
 */
object MileageAnalytics {
    const val DROP_THRESHOLD=0.20
    const val HISTORY_SAMPLES=3
    data class Stop(val id:String,val vehicle:String,val date:String,val odometer:Double,
        val gallons:Double,val fillType:String,val amount:Double,val requestId:String="")
    data class Sample(val startDate:String,val endDate:String,val startOdometer:Double,
        val endOdometer:Double,val miles:Double,val gallons:Double,val mpg:Double,
        val baseline:Double?,val declinePercent:Double?,val maintenanceFlag:Boolean)
    data class Report(val vehicle:String,val recorded:Int,val fullTanks:Int,
        val unknownFills:Int,val invalid:Int,val samples:List<Sample>){
        val latest:Sample? get()=samples.lastOrNull()
        val alerts:Int get()=samples.count{it.maintenanceFlag}
        val averageMpg:Double? get()=samples.takeLast(3).takeIf{it.isNotEmpty()}?.map{it.mpg}?.average()
    }
    private fun fillType(receipt:JSONObject):String{
        val named=receipt.optString("fillType").lowercase(Locale.US)
        if(named in listOf("full","partial"))return named
        if(receipt.has("fullTank"))return if(receipt.optBoolean("fullTank"))"full"else"partial"
        return "unknown"
    }
    fun fromReceipt(id:String,receipt:JSONObject):Stop?{
        val vehicle=receipt.optString("vehicle").trim()
        val od=receipt.optString("odometer").toDoubleOrNull()
        val gal=receipt.optString("gallons").toDoubleOrNull()
        if(vehicle.isBlank()||od==null||gal==null||!od.isFinite()||!gal.isFinite()||
            od<0.0||gal<=0.0)return null
        return Stop(id,vehicle,FinancePackets.normalizeDate(receipt.optString("date")),
            od,gal,fillType(receipt),receipt.optDouble("cost",0.0))
    }
    fun load(context:Context):List<Stop> = TrackerDatabase(context).use{db->
        db.receiptSearch("",10000).mapNotNull{saved->
            fromReceipt(saved.id,saved.payload)?.copy(requestId=saved.requestId)
        }
    }
    fun vehicleNumbers(context:Context):List<String> =
        load(context).map{it.vehicle}.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)

    fun report(vehicle:String,stops:List<Stop>):Report{
        val rows=stops.filter{it.vehicle==vehicle}
            .sortedWith(compareBy<Stop>{if(it.date.isBlank())"9999" else it.date}
                .thenBy{it.odometer}.thenBy{it.id})
        val samples=mutableListOf<Sample>()
        var full:Stop?=null
        var betweenGallons=0.0
        var unknown=0
        var invalid=0
        for(stop in rows){
            when(stop.fillType){
                "full"->{
                    val previous=full
                    if(previous!=null){
                        val miles=stop.odometer-previous.odometer
                        val consumed=betweenGallons+stop.gallons
                        val mpg=if(consumed>0) miles/consumed else Double.NaN
                        // Implausible sample, data correction or odometer reset:
                        // do not include in a vehicle's maintenance baseline.
                        if(miles>=25.0 && consumed>0 && mpg.isFinite() && mpg in 3.0..100.0){
                            val recent=samples.takeLast(HISTORY_SAMPLES)
                            val baseline=recent.takeIf{it.size==HISTORY_SAMPLES}?.map{it.mpg}?.average()
                            val drop=baseline?.let{100.0*(1-mpg/it)}
                            samples.add(Sample(previous.date,stop.date,previous.odometer,
                                stop.odometer,miles,consumed,mpg,baseline,drop,
                                drop!=null && drop>=DROP_THRESHOLD*100.0))
                        }else invalid++
                    }
                    full=stop;betweenGallons=0.0
                }
                "partial"->if(full!=null)betweenGallons+=stop.gallons
                else->{
                    unknown++
                    // Even if a later fill is marked full, an intervening unknown
                    // purchase prevents a reliable full-to-full comparison.
                    full=null
                    betweenGallons=0.0
                }
            }
        }
        return Report(vehicle,rows.size,rows.count{it.fillType=="full"},unknown,invalid,samples)
    }
}
