package org.karuk.gasreceipttracker

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Date

/** Offline lookup for immutable, confirmed-sent reimbursement PDFs.
 * The original PDF is kept under the app's private files/exports folder and
 * is already included in TrackerBackup. Nothing is deleted after email.
 */
object FinancePacketArchive {
    data class Packet(val requestId:String,val reference:String,val title:String,
        val vehicle:String,val purchaseDate:String,val amount:Double,val sentDate:String,
        val pdf:Uri,val receipt:JSONObject,val status:String)
    private fun amount(j:JSONObject):Double? =
        j.optString("cost").replace("$","").replace(",","").trim().toDoubleOrNull()
    fun entries(context:Context):List<Packet>{
        val orders=RequestWorkflow(context).all()
        val out=mutableListOf<Packet>()
        for(i in 0 until orders.length()){
            val r=orders.getJSONObject(i)
            if(r.optString("status")!="sent")continue
            val path=r.optString("sentPdfUri")
            if(path.isBlank())continue
            val receipts=r.optJSONArray("receipts")?:JSONArray()
            for(j in 0 until receipts.length()){
                val receipt=receipts.getJSONObject(j)
                val uri=Uri.parse(path)
                out.add(Packet(r.optString("id"),r.optString("reference"),
                    r.optString("title"),receipt.optString("vehicle"),
                    receipt.optString("date"),amount(receipt)?:0.0,r.optString("sentAt"),
                    uri,receipt,r.optString("status")))
            }
        }
        return out
    }
    fun filter(entries:List<Packet>,query:String,sort:String):List<Packet>{
        val term=query.trim().lowercase(Locale.US)
        val matches=entries.filter{p->
            term.isBlank() || listOf(p.purchaseDate,p.amount.toString(),
                String.format(Locale.US,"%.2f",p.amount),
                "$"+String.format(Locale.US,"%.2f",p.amount),
                p.reference,p.title,p.vehicle,p.sentDate,p.status)
                .any{it.lowercase(Locale.US).contains(term)}
        }
        return when(sort){
            "Amount: highest first"->matches.sortedWith(compareByDescending<Packet>{it.amount}.thenByDescending{dateMillis(it.purchaseDate)})
            "Amount: lowest first"->matches.sortedWith(compareBy<Packet>{it.amount}.thenByDescending{dateMillis(it.purchaseDate)})
            "Oldest purchase first"->matches.sortedBy{dateMillis(it.purchaseDate)}
            else->matches.sortedWith(compareByDescending<Packet>{dateMillis(it.purchaseDate)}.thenByDescending{it.sentDate})
        }
    }
    fun dateMillis(value:String):Long {
        val formats=listOf("MMM d, yyyy h:mm a","MMM d, yyyy","MM/dd/yyyy","M/d/yyyy",
            "yyyy-MM-dd","MM-dd-yyyy","M-d-yyyy","MM/dd/yy")
        for(pattern in formats)try{
            val f=SimpleDateFormat(pattern,Locale.US).apply{isLenient=false}
            val parsed=f.parse(value.trim())?:continue
            return parsed.time
        }catch(_:Exception){}
        return 0L
    }
    fun savedFile(context:Context,p:Packet):File? {
        val name=p.pdf.lastPathSegment.orEmpty()
        if(!name.matches(Regex("[A-Za-z0-9_-]+\\.pdf")))return null
        val file=File(context.filesDir,"exports/"+name)
        return file.takeIf{it.isFile && it.length()>0}
    }
    fun stableCopy(context:Context,requestId:String,source:Uri):Uri{
        val safe=requestId.replace(Regex("[^a-zA-Z0-9_-]"),"_")
        require(safe.isNotBlank())
        val target=File(context.filesDir,"exports/Finance_Sent_$safe.pdf").apply{parentFile?.mkdirs()}
        if(target.isFile&&target.length()>0)return OfficialPurchaseRequisition.uri(context,target)
        val temp=File(target.parentFile,target.name+".part")
        try{
            context.contentResolver.openInputStream(source)?.use{input->
                temp.outputStream().use{output->input.copyTo(output)}
            }?:error("Could not open the original finance packet")
            require(temp.length()>100){"The reimbursement PDF is empty"}
            check(temp.renameTo(target)){"Could not archive the sent PDF"}
            return OfficialPurchaseRequisition.uri(context,target)
        }finally{temp.delete()}
    }
}
