package org.karuk.gasreceipttracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale

/**
 * Search and follow up exact, previously submitted packets.
 * Date default is the issue date of the original PR; the receipt purchase date
 * is searchable separately, as is the exact transaction amount.
 */
class FinanceArchiveActivity:Activity(){
    private lateinit var search:EditText
    private lateinit var statuses:Spinner
    private lateinit var vehicles:Spinner
    private lateinit var sorts:Spinner
    private lateinit var results:LinearLayout
    private var exporting:FinancePacketRecord?=null
    private fun dp(n:Int)=TrackerStyle.dp(this,n)
    private fun note(text:String,size:Float=14f)=TrackerStyle.text(this,text,size,false,TrackerStyle.muted)
    private fun alert(text:String)=Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(TrackerStyle.background)
        }
        val header=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(12))
            setBackgroundColor(TrackerStyle.red)
        }
        header.addView(TrackerStyle.text(this,"Finance packet archive",20f,true,android.graphics.Color.WHITE))
        header.addView(TrackerStyle.text(this,"Complete submitted PDFs • searchable and kept offline",
            13f,false,android.graphics.Color.WHITE))
        root.addView(header)
        val scroll=ScrollView(this)
        val body=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(12),dp(18),dp(25))
        }
        body.addView(note("Find by PR date, purchase date, amount, PR reference, vehicle, station, recipient or status."))
        body.addView(TrackerStyle.text(this,"Search",13f,true))
        search=EditText(this).apply{
            hint="Example: 2026-10-07, 61.35, PR-00001, 309"
            setSingleLine(true)
        }
        TrackerStyle.input(this,search);body.addView(search)
        fun selector(label:String,options:List<String>):Spinner {
            body.addView(TrackerStyle.text(this,label,13f,true))
            val spinner=Spinner(this)
            spinner.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,options)
            spinner.background=TrackerStyle.shape(this,android.graphics.Color.WHITE,TrackerStyle.line)
            body.addView(spinner,LinearLayout.LayoutParams(-1,dp(50)).apply{bottomMargin=dp(10)})
            return spinner
        }
        statuses=selector("Finance status",listOf("All")+FinancePackets.STATUSES)
        vehicles=selector("Vehicle",listOf("All")+FinancePackets.vehicles(this))
        sorts=selector("Sort by",FinancePackets.SORTS)
        body.addView(note("Default: newest purchase requisition date first. Status is updated by you; it is not connected to Finance.",12f))
        results=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        body.addView(results)
        scroll.addView(body)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val close=TrackerStyle.button(this,"Back to requests"){finish()}
        root.addView(close,LinearLayout.LayoutParams(-1,-2).apply{
            setMargins(dp(18),dp(4),dp(18),dp(8))
        })
        setContentView(root);TrackerStyle.insets(this,root)
        search.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){refresh()}
            override fun afterTextChanged(s:Editable?){}
        })
        val listener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,position:Int,id:Long){refresh()}
        }
        statuses.onItemSelectedListener=listener
        vehicles.onItemSelectedListener=listener
        sorts.onItemSelectedListener=listener
        refresh()
    }
    private fun statusColor(status:String)=when(status){
        FinancePackets.FOLLOW_UP,FinancePackets.RETURNED->TrackerStyle.red
        FinancePackets.COMPLETED->TrackerStyle.green
        else->TrackerStyle.ink
    }
    private fun refresh(){
        if(!::results.isInitialized || !::search.isInitialized || !::sorts.isInitialized)return
        results.removeAllViews()
        val packets=try {
            FinancePackets.list(this,search.text.toString(),
                statuses.selectedItem?.toString()?:"All",
                vehicles.selectedItem?.toString()?:"All",
                sorts.selectedItem?.toString()?:FinancePackets.SORTS[0])
        }catch(e:Exception){
            results.addView(TrackerStyle.text(this,"Could not search archived packets: "+e.message,14f))
            return
        }
        results.addView(TrackerStyle.text(this,"Saved Finance packets: "+packets.size,
            16f,true).apply{setPadding(0,dp(12),0,dp(7))})
        if(packets.isEmpty()){
            results.addView(note("No matching sent packets. Once you confirm an email was sent, its exact PDF and transaction details appear here."))
            return
        }
        for(packet in packets){
            val pr=packet.prDate.ifBlank{"Not recorded (older PR)"}
            val purchase=packet.purchaseDate.ifBlank{packet.purchaseDateRaw.ifBlank{"Unknown"}}
            val card=TrackerStyle.card(this,packet.reference+"   •   "+FinancePackets.money(packet.cents),
                "PR date: "+pr+"   |   Purchased: "+purchase+
                    "\nVehicle "+packet.vehicle+"   •   "+packet.station+
                    "\n"+packet.title+"\nSent: "+packet.sentAt)
            card.addView(TrackerStyle.text(this,packet.financeStatus,15f,true,
                statusColor(packet.financeStatus)))
            if(packet.notes.isNotBlank())card.addView(note("Finance notes: "+packet.notes,13f))
            results.addView(card)
            results.addView(TrackerStyle.button(this,"Open complete PDF • "+packet.reference){
                try{
                    val uri=FinancePackets.pdf(this,packet)
                    startActivity(Intent(this,PdfPreviewActivity::class.java).setData(uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }catch(e:Exception){alert("Cannot open saved packet: "+e.message)}
            })
            results.addView(TrackerStyle.button(this,"Export or download PDF"){
                try{
                    FinancePackets.pdf(this,packet)
                    exporting=packet
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("application/pdf")
                        .putExtra(Intent.EXTRA_TITLE,packet.reference+"_Finance_Packet.pdf"),240)
                }catch(e:Exception){alert("Cannot export saved packet: "+e.message)}
            })
            results.addView(TrackerStyle.button(this,"Resend saved packet by email"){
                emailExisting(packet)
            })
            results.addView(TrackerStyle.button(this,"Update Finance status / notes"){
                editStatus(packet)
            })
        }
    }
    private fun editStatus(packet:FinancePacketRecord){
        val box=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(14),dp(22),dp(6))
        }
        box.addView(note("This only updates your follow-up tracking. The sent PDF stays unchanged."))
        val spinner=Spinner(this).apply{
            adapter=ArrayAdapter(this@FinanceArchiveActivity,
                android.R.layout.simple_spinner_dropdown_item,FinancePackets.STATUSES)
            setSelection(FinancePackets.STATUSES.indexOf(packet.financeStatus).coerceAtLeast(0))
        }
        box.addView(spinner)
        val notes=EditText(this).apply{
            hint="Finance contact, reason for hold, date of follow-up, action needed..."
            setText(packet.notes);minLines=3;maxLines=6;setSingleLine(false)
        }
        TrackerStyle.input(this,notes);box.addView(notes)
        AlertDialog.Builder(this).setTitle(packet.reference+" • Finance follow-up").setView(box)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("Save status"){_,_->
                try{
                    FinancePackets.updateStatus(this,packet.id,spinner.selectedItem.toString(),notes.text.toString())
                    refresh();alert("Finance status saved")
                }catch(e:Exception){alert("Status not saved: "+e.message)}
            }.show()
    }
    private fun emailExisting(packet:FinancePacketRecord){
        val attachment=try{FinancePackets.pdf(this,packet)}
            catch(e:Exception){alert("Saved PDF unavailable: "+e.message);return}
        val form=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(10),dp(18),dp(8))
        }
        fun input(label:String,value:String,multi:Boolean=false):EditText{
            form.addView(TrackerStyle.text(this,label,13f,true))
            val e=EditText(this).apply{setText(value);setSingleLine(!multi);if(multi)minLines=4}
            TrackerStyle.input(this,e);form.addView(e);return e
        }
        val to=input("To",packet.to)
        val cc=input("CC",packet.cc)
        val subject=input("Subject",packet.subject.ifBlank{packet.reference+" - Resent Finance packet"})
        val message=input("Message",
            "Following up on "+packet.reference+". Attached is the original Finance packet, "+
                "purchased "+packet.purchaseDateRaw+" for "+FinancePackets.money(packet.cents)+
                ".\n\nPlease advise if further documentation is needed.",true)
        form.addView(note("This resends the original PDF. It does not change the original sent date, create a new PR or claim Finance received it.",12f))
        val scroll=ScrollView(this);scroll.addView(form)
        val dialog=AlertDialog.Builder(this).setTitle("Resend archived "+packet.reference)
            .setView(scroll).setNegativeButton("Cancel",null)
            .setPositiveButton("Open email",null).create()
        dialog.setOnShowListener{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener{
                val recipients=to.text.toString().split(",",";").map{it.trim()}.filter{it.isNotEmpty()}
                val copies=cc.text.toString().split(",",";").map{it.trim()}.filter{it.isNotEmpty()}
                if(recipients.isEmpty() || (recipients+copies).any{
                        !android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches()
                    }){to.error="Enter valid email recipients";return@setOnClickListener}
                try{
                    val email=Intent(Intent.ACTION_SEND).apply{
                        type="application/pdf"
                        putExtra(Intent.EXTRA_EMAIL,recipients.toTypedArray())
                        putExtra(Intent.EXTRA_CC,copies.toTypedArray())
                        putExtra(Intent.EXTRA_SUBJECT,subject.text.toString())
                        putExtra(Intent.EXTRA_TEXT,message.text.toString())
                        putExtra(Intent.EXTRA_STREAM,attachment)
                        clipData=android.content.ClipData.newUri(contentResolver,"Finance PDF",attachment)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(email,"Resend via email"))
                    TrackerDatabase(this).use{db->
                        db.log(packet.id,"finance_resend_opened",
                            "Mail chooser opened (sending not verified). To: "+recipients.joinToString(","))
                    }
                    dialog.dismiss()
                }catch(e:Exception){alert("Could not open email: "+e.message)}
            }
        }
        dialog.show()
    }
    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=240)return
        val packet=exporting;exporting=null
        if(resultCode!=RESULT_OK || packet==null)return
        val destination=data?.data?:return
        try{
            val original=FinancePackets.pdf(this,packet)
            contentResolver.openInputStream(original).use{source->
                requireNotNull(source){"Original PDF cannot be opened"}
                contentResolver.openOutputStream(destination,"wt").use{out->
                    requireNotNull(out){"Destination cannot be opened"}
                    source.copyTo(out)
                }
            }
            alert("Complete original Finance packet exported")
        }catch(e:Exception){alert("PDF export failed: "+e.message)}
    }
}
