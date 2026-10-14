package org.karuk.gasreceipttracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import java.util.Locale

/** Cross-request vehicle efficiency and maintenance trend dashboard. */
class MileageActivity:Activity(){
    private lateinit var selection:Spinner
    private lateinit var reportBody:LinearLayout
    private var csvToExport=""
    private var selectedVehicle=""
    private fun dp(n:Int)=TrackerStyle.dp(this,n)
    private fun f(value:Double,digits:Int=1)=String.format(Locale.US,"%."+digits+"f",value)
    private fun message(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun text(s:String,size:Float=14f)=TrackerStyle.text(this,s,size,false,TrackerStyle.muted)
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL
            setBackgroundColor(TrackerStyle.background)}
        val header=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(12))
            setBackgroundColor(TrackerStyle.red)
        }
        header.addView(TrackerStyle.text(this,"Vehicle mileage dashboard",20f,true,android.graphics.Color.WHITE))
        header.addView(TrackerStyle.text(this,"Full-tank MPG trends by vehicle",13f,false,android.graphics.Color.WHITE))
        root.addView(header)
        val scroll=ScrollView(this)
        val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(12),dp(18),dp(25))}
        body.addView(text("Full-to-full MPG uses odometer distance divided by gallons bought since the previous full tank, including any partial fills."))
        body.addView(TrackerStyle.text(this,"Vehicle number",13f,true))
        val vehicles=MileageAnalytics.vehicleNumbers(this)
        selection=Spinner(this).apply{
            adapter=ArrayAdapter(this@MileageActivity,
                android.R.layout.simple_spinner_dropdown_item,
                if(vehicles.isEmpty())listOf("No fuel records")else vehicles)
            background=TrackerStyle.shape(this@MileageActivity,android.graphics.Color.WHITE,TrackerStyle.line)
        }
        body.addView(selection,LinearLayout.LayoutParams(-1,dp(52)))
        reportBody=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        body.addView(reportBody)
        scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(TrackerStyle.button(this,"Back to trips"){finish()},
            LinearLayout.LayoutParams(-1,-2).apply{setMargins(dp(18),dp(5),dp(18),dp(10))})
        setContentView(root);TrackerStyle.insets(this,root)
        selection.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){
                selectedVehicle=if(vehicles.isEmpty())""else vehicles[position]
                refresh()
            }
        }
        if(vehicles.isNotEmpty()){
            selectedVehicle=vehicles.first()
            refresh()
        }else{
            reportBody.addView(text("No saved fuel purchases yet. Record a vehicle, odometer and gallons for each purchase to begin."))
        }
    }
    private fun refresh(){
        if(!::reportBody.isInitialized||selectedVehicle.isBlank())return
        reportBody.removeAllViews()
        val entries=try{MileageAnalytics.load(this)}catch(e:Exception){
            reportBody.addView(text("Could not load fuel purchase history: "+e.message));return
        }
        val report=MileageAnalytics.report(selectedVehicle,entries)
        reportBody.addView(TrackerStyle.card(this,
            "Vehicle "+selectedVehicle,
            report.recorded.toString()+" fuel receipts  •  "+report.fullTanks+" confirmed full tanks\n"+
            report.samples.size+" verified MPG cycles  •  "+report.unknownFills+" unknown fill levels"))
        val latest=report.latest
        if(latest==null){
            reportBody.addView(TrackerStyle.card(this,"MPG not available yet",
                "Mark at least two fill-ups as full tanks, with fuel odometers and gallons. "+
                "Mark all intervening fill-ups as partial or full. Unknown purchases interrupt MPG calculations.",true))
        }else{
            val summary=TrackerStyle.card(this,
                "Latest: "+f(latest.mpg)+" MPG  •  Avg last 3: "+
                    (report.averageMpg?.let{f(it)}?:"—")+" MPG",
                "Latest cycle: "+f(latest.miles,0)+" miles ÷ "+f(latest.gallons,3)+
                    " gallons • "+latest.endDate)
            reportBody.addView(summary)
            if(report.alerts>0){
                val flagged=report.samples.lastOrNull{it.maintenanceFlag}!!
                reportBody.addView(TrackerStyle.card(this,"Possible vehicle maintenance check",
                    "MPG dropped "+f(flagged.declinePercent?:0.0)+"% versus the prior 3 verified "+
                    "full-to-full cycles. Confirm fill levels and driving conditions; "+
                    "also check tire pressure, engine performance and idling. "+
                    "This is a trend alert, not a mechanical diagnosis.",true))
            }
        }
        reportBody.addView(TrackerStyle.text(this,"MPG graph",19f,true).apply{
            setPadding(0,dp(15),0,dp(5))})
        reportBody.addView(VehicleMpgChart(this).apply{samples=report.samples},
            LinearLayout.LayoutParams(-1,dp(255)).apply{bottomMargin=dp(5)})
        reportBody.addView(text("Red points mark a 20% or larger MPG decline compared with the previous three valid full-tank cycles. A graph needs verified fill levels.",12f))
        if(report.invalid>0){
            reportBody.addView(text("Excluded "+report.invalid+" implausible or invalid cycles (e.g., odometer reset or inconsistent fuel records).",13f))
        }
        if(report.samples.isNotEmpty()){
            reportBody.addView(TrackerStyle.button(this,"Export MPG data (CSV)"){
                csvToExport=buildCsv(report)
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE).setType("text/csv")
                    .putExtra(Intent.EXTRA_TITLE,"Vehicle_"+selectedVehicle+"_MPG.csv"),241)
            })
        }
        reportBody.addView(TrackerStyle.text(this,"Verified fill cycles",19f,true).apply{
            setPadding(0,dp(12),0,dp(5))})
        for((i,s)in report.samples.withIndex()){
            reportBody.addView(TrackerStyle.card(this,
                "Cycle "+(i+1)+"  •  "+f(s.mpg)+" MPG"+
                    if(s.maintenanceFlag)"   ⚠ LOW"else"",
                "From "+s.startDate+" to "+s.endDate+
                    "\nOdometer "+f(s.startOdometer,0)+" → "+f(s.endOdometer,0)+
                    "\n"+f(s.miles,0)+" miles / "+f(s.gallons,3)+" total gallons"))
        }
        reportBody.addView(TrackerStyle.text(this,"Fuel purchases and fill levels",19f,true).apply{
            setPadding(0,dp(15),0,dp(4))})
        reportBody.addView(text("Tap a receipt to classify the fill as Full, Partial or Unknown. This is a mileage annotation only; already-submitted PDFs remain unchanged."))
        val history=entries.filter{it.vehicle==selectedVehicle}
            .sortedWith(compareByDescending<MileageAnalytics.Stop>{it.date}.thenByDescending{it.odometer})
        for(stop in history.take(60)){
            val category=when(stop.fillType){
                "full"->"Full tank";"partial"->"Partial";else->"Unknown"
            }
            reportBody.addView(TrackerStyle.button(this,
                (stop.date.ifBlank{"Undated"})+" | "+f(stop.odometer,0)+" mi | "+
                    f(stop.gallons,3)+" gal | "+category){
                classify(stop)
            })
        }
        if(history.size>60)reportBody.addView(text("Showing the latest 60 purchases. All validated records are included in MPG calculations.",12f))
    }
    private fun classify(stop:MileageAnalytics.Stop){
        val labels=arrayOf("Full tank (filled completely)","Partial fill","Unknown / not sure")
        val options=listOf("full","partial","unknown")
        val initial=options.indexOf(stop.fillType).coerceAtLeast(2)
        var selected=initial
        AlertDialog.Builder(this).setTitle("Vehicle "+stop.vehicle+"  •  "+stop.date)
            .setMessage("Classify this purchase using the actual pump fill. Wrong full-tank assumptions produce misleading MPG readings.")
            .setSingleChoiceItems(labels,initial){_,which->selected=which}
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Save fill level"){_,_->
                try{
                    RequestWorkflow(this).setFillType(stop.requestId,stop.id,options[selected])
                    refresh()
                    message("Fill level saved for mileage tracking")
                }catch(e:Exception){message("Could not update mileage: "+e.message)}
            }.show()
    }
    private fun buildCsv(report:MileageAnalytics.Report):String{
        val head="Vehicle,From receipt date,To receipt date,Start odometer,End odometer,Miles,Total gallons,MPG,Previous 3 average,Drop percent,Possible maintenance flag\n"
        return head+report.samples.joinToString("\n"){s->
            listOf(report.vehicle,s.startDate,s.endDate,
                f(s.startOdometer,0),f(s.endOdometer,0),f(s.miles,1),f(s.gallons,3),f(s.mpg,2),
                s.baseline?.let{f(it,2)}.orEmpty(),s.declinePercent?.let{f(it,1)}.orEmpty(),
                s.maintenanceFlag.toString()).joinToString(",")
        }+"\n"
    }
    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=241)return
        if(resultCode!=RESULT_OK){csvToExport="";return}
        try{
            val uri=data?.data?:error("No export location selected")
            contentResolver.openOutputStream(uri,"wt").use{out->
                requireNotNull(out){"Cannot write CSV"}
                out.write(csvToExport.toByteArray(Charsets.UTF_8))
            }
            message("Vehicle MPG report exported")
        }catch(e:Exception){message("Could not export MPG CSV: "+e.message)}
        finally{csvToExport=""}
    }
}
