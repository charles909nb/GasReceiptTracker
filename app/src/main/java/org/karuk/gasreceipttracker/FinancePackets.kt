package org.karuk.gasreceipttracker

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Stores the exact PDF attached to Finance, never a later re-render with a
 * changed requisition date. "Sent" means user-confirmed; Finance statuses are
 * manually maintained, not verified by the recipient.
 */
object FinancePackets {
    const val AWAITING="Awaiting Finance"
    const val FOLLOW_UP="Needs follow-up"
    const val COMPLETED="Completed"
    const val RETURNED="Returned for correction"
    const val CANCELLED="Cancelled"
    val STATUSES=listOf(AWAITING,FOLLOW_UP,COMPLETED,RETURNED,CANCELLED)
    val SORTS=listOf("PR date (newest)","Purchase date (newest)","Amount (highest)",
        "Amount (lowest)","Vehicle number","Finance status","Sent date (newest)")
    private val safeName=Regex("""Finance_PR-[0-9]{5}_[a-zA-Z0-9-]{8}_[a-f0-9]{16}\.pdf""")
    private fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it)}
    fun normalizeDate(raw:String):String{
        val value=raw.trim()
        if(value.isBlank())return ""
        try{return LocalDate.parse(value).toString()}catch(_:Exception){}
        for(pattern in listOf("M/d/yyyy","M-d-yyyy","M.d.yyyy","MM/dd/yy","MMM d, yyyy",
            "MMMM d, yyyy","MMM d yyyy","MMMM d yyyy")){
            try{return LocalDate.parse(value,DateTimeFormatter.ofPattern(pattern,Locale.US)).toString()}
            catch(_:Exception){}
        }
        return ""
    }
    fun cents(raw:String):Long?=runCatching{
        BigDecimal(raw.trim().removePrefix("$").replace(",","")).setScale(2)
            .movePointRight(2).longValueExact()
    }.getOrNull()
    fun money(cents:Long)="$"+String.format(Locale.US,"%,.2f",cents/100.0)
    private fun sentEpoch(value:String):Long=runCatching{
        SimpleDateFormat("MMM d, yyyy h:mm a",Locale.US).parse(value)?.time?:0L
    }.getOrDefault(0L)

    /** Copy the PDF to an immutable, SHA-named app-private Finance file before
     * Android opens the email chooser. The original document stays unchanged.
     */
    fun freeze(context:Context,request:JSONObject,source:Uri):Uri{
        val ref=request.getString("reference")
        require(ref.matches(Regex("PR-[0-9]{5}"))){"Invalid PR reference"}
        val requestKey=request.getString("id").replace(Regex("[^a-zA-Z0-9-]"),"").take(8)
        require(requestKey.length==8){"Invalid request identifier"}
        val root=File(context.filesDir,"exports").apply{mkdirs()}
        val temp=File(root,"finance_copy_"+System.currentTimeMillis()+"_"+requestKey+".part")
        val digest=MessageDigest.getInstance("SHA-256")
        var length=0L
        try{
            val input=context.contentResolver.openInputStream(source)?:error("Cannot read reimbursement PDF")
            input.use{stream->
                FileOutputStream(temp).use{out->
                    val bytes=ByteArray(65536)
                    var n=stream.read(bytes)
                    while(n>=0){
                        if(n>0){
                            length+=n;require(length<=75_000_000){"The packet exceeds 75 MB"}
                            digest.update(bytes,0,n);out.write(bytes,0,n)
                        }
                        n=stream.read(bytes)
                    }
                    out.fd.sync()
                }
            }
            require(length>100){"Reimbursement PDF is incomplete"}
            val header=ByteArray(5)
            temp.inputStream().use{require(it.read(header)==5){"PDF header missing"}}
            require(String(header,Charsets.US_ASCII)=="%PDF-"){"Attachment is not a PDF"}
            val sha=hex(digest.digest()).take(16)
            val file=File(root,"Finance_"+ref+"_"+requestKey+"_"+sha+".pdf")
            if(file.exists()){
                require(file.length()==length&&sha256(file).startsWith(sha)){
                    "Saved Finance PDF is damaged. Restore a backup before sharing it"
                }
                temp.delete()
            }else check(temp.renameTo(file)){"Could not preserve the Finance packet"}
            return OfficialPurchaseRequisition.uri(context,file)
        }catch(e:Exception){temp.delete();throw e}
    }
    private fun sha256(file:File):String{
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use{stream->
            val bytes=ByteArray(65536);var n=stream.read(bytes)
            while(n>=0){if(n>0)digest.update(bytes,0,n);n=stream.read(bytes)}
        }
        return hex(digest.digest())
    }
    /** Only allow the app's own immutable PDF files to be re-shared. */
    fun ownedFile(context:Context,uriString:String):File?{
        val parsed=runCatching{Uri.parse(uriString)}.getOrNull()?:return null
        if(parsed.scheme!="content"||parsed.authority!=context.packageName+".documents")return null
        val name=parsed.lastPathSegment.orEmpty()
        if(!safeName.matches(name))return null
        val root=File(context.filesDir,"exports")
        val file=File(root,name)
        return file.takeIf{it.isFile&&it.canonicalFile.parentFile==root.canonicalFile}
    }
    fun pdf(context:Context,record:FinancePacketRecord):Uri{
        val saved=ownedFile(context,record.pdfUri)
        if(saved!=null){
            if(record.sha256.isNotBlank())require(sha256(saved)==record.sha256){
                "Archived PDF was modified. Restore a verified backup before sharing"
            }
            return OfficialPurchaseRequisition.uri(context,saved)
        }
        // Support older 2.1 packets without inventing a new PR/date.
        val parsed=Uri.parse(record.pdfUri)
        require(parsed.scheme=="content"&&parsed.authority==context.packageName+".documents"){
            "Archived PDF is not an app-owned document"
        }
        val name=parsed.lastPathSegment.orEmpty()
        require(name.matches(Regex("""Karuk_Reimbursement_[a-f0-9]{24}\.pdf"""))){
            "Archived PDF is unavailable"
        }
        val file=File(context.filesDir,"exports/"+name)
        require(file.isFile){"Saved PDF is missing. Restore a complete backup."}
        return OfficialPurchaseRequisition.uri(context,file)
    }
    fun exists(context:Context,record:FinancePacketRecord)=runCatching{pdf(context,record);true}.getOrDefault(false)

    /** INSERT OR IGNORE protects follow-up status and notes on every save/sync. */
    fun indexSent(db:SQLiteDatabase,context:Context,request:JSONObject){
        if(request.optString("status")!="sent")return
        db.rawQuery("SELECT 1 FROM finance_packets WHERE request_id=?",
            arrayOf(request.optString("id"))).use{if(it.moveToFirst())return}
        val receipt=request.optJSONArray("receipts")?.optJSONObject(0)?:JSONObject()
        val amount=cents(receipt.optString("cost"))?:0L
        val sent=request.optString("sentAt")
        val file=ownedFile(context,request.optString("sentPdfUri"))
        val v=ContentValues().apply{
            put("request_id",request.optString("id"))
            put("reference",request.optString("reference"))
            put("title",request.optString("title"))
            put("pr_date",normalizeDate(request.optString("prDate")))
            put("receipt_date",normalizeDate(receipt.optString("date")))
            put("receipt_date_raw",receipt.optString("date"))
            put("amount_cents",amount)
            put("vehicle",receipt.optString("vehicle"))
            put("station",receipt.optString("station"))
            put("sent_at",sent)
            put("sent_epoch",request.optLong("sentEpochMs",sentEpoch(sent)))
            put("recipient",request.optJSONObject("email")?.optString("to").orEmpty())
            put("cc",request.optJSONObject("email")?.optString("cc").orEmpty())
            put("subject",request.optJSONObject("email")?.optString("subject").orEmpty())
            put("pdf_uri",request.optString("sentPdfUri"))
            put("sha256",file?.let{sha256(it)}.orEmpty())
            put("pdf_bytes",file?.length()?:0L)
            put("finance_status",AWAITING)
            put("notes","")
            put("updated_epoch",System.currentTimeMillis())
        }
        db.insertWithOnConflict("finance_packets",null,v,SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun list(context:Context,query:String="",status:String="All",vehicle:String="All",
             sort:String=SORTS[0],limit:Int=150):List<FinancePacketRecord> =
        TrackerDatabase(context).use{db->
            val sql=StringBuilder("SELECT request_id,reference,title,pr_date,receipt_date,receipt_date_raw,"+
                "amount_cents,vehicle,station,sent_at,sent_epoch,recipient,cc,subject,pdf_uri,"+
                "sha256,pdf_bytes,finance_status,notes,updated_epoch FROM finance_packets WHERE 1=1")
            val args=mutableListOf<String>()
            // Each search term can match a different field: a user can enter
            // "2026-10-07 61.35" to find purchases on a date for that amount.
            val searchTerms=query.trim().split(Regex("""\s+"""))
                .filter{it.isNotBlank()}.take(8)
            for(rawTerm in searchTerms){
                val raw=rawTerm.removePrefix("$").replace(",","")
                val term="%"+raw.replace("!","!!").replace("%","!%").replace("_","!_")+"%"
                sql.append(" AND (reference LIKE ? ESCAPE '!' OR title LIKE ? ESCAPE '!'"+
                    " OR pr_date LIKE ? ESCAPE '!' OR receipt_date LIKE ? ESCAPE '!'"+
                    " OR receipt_date_raw LIKE ? ESCAPE '!' OR vehicle LIKE ? ESCAPE '!'"+
                    " OR station LIKE ? ESCAPE '!' OR recipient LIKE ? ESCAPE '!'"+
                    " OR finance_status LIKE ? ESCAPE '!'"+
                    " OR printf('%.2f',amount_cents/100.0) LIKE ? ESCAPE '!')")
                repeat(10){args.add(term)}
            }
            if(status!="All"){sql.append(" AND finance_status=?");args.add(status)}
            if(vehicle!="All"){sql.append(" AND vehicle=?");args.add(vehicle)}
            val order=when(sort){
                SORTS[1]->"receipt_date DESC,sent_epoch DESC"
                SORTS[2]->"amount_cents DESC,sent_epoch DESC"
                SORTS[3]->"amount_cents ASC,sent_epoch DESC"
                SORTS[4]->"vehicle COLLATE NOCASE ASC,sent_epoch DESC"
                SORTS[5]->"finance_status COLLATE NOCASE ASC,sent_epoch DESC"
                SORTS[6]->"sent_epoch DESC"
                else->"pr_date DESC,sent_epoch DESC"
            }
            sql.append(" ORDER BY ").append(order).append(" LIMIT ?")
            args.add(limit.coerceIn(1,1000).toString())
            val found=mutableListOf<FinancePacketRecord>()
            db.readableDatabase.rawQuery(sql.toString(),args.toTypedArray()).use{c->
                while(c.moveToNext())found.add(FinancePacketRecord(
                    c.getString(0),c.getString(1).orEmpty(),c.getString(2).orEmpty(),
                    c.getString(3).orEmpty(),c.getString(4).orEmpty(),c.getString(5).orEmpty(),
                    c.getLong(6),c.getString(7).orEmpty(),c.getString(8).orEmpty(),
                    c.getString(9).orEmpty(),c.getLong(10),c.getString(11).orEmpty(),
                    c.getString(12).orEmpty(),c.getString(13).orEmpty(),c.getString(14).orEmpty(),
                    c.getString(15).orEmpty(),c.getLong(16),c.getString(17).orEmpty(),
                    c.getString(18).orEmpty(),c.getLong(19)
                ))
            }
            found
        }
    fun vehicles(context:Context):List<String> =TrackerDatabase(context).use{db->
        val names=mutableListOf<String>()
        db.readableDatabase.rawQuery(
            "SELECT DISTINCT vehicle FROM finance_packets WHERE vehicle!='' ORDER BY vehicle COLLATE NOCASE",null
        ).use{c->while(c.moveToNext())names.add(c.getString(0))}
        names
    }
    fun updateStatus(context:Context,id:String,status:String,notes:String){
        require(status in STATUSES){"Invalid Finance follow-up status"}
        require(notes.length<=2000){"Notes are limited to 2,000 characters"}
        TrackerDatabase(context).use{db->
            val v=ContentValues().apply{
                put("finance_status",status);put("notes",notes.trim())
                put("updated_epoch",System.currentTimeMillis())
            }
            check(db.writableDatabase.update("finance_packets",v,"request_id=?",arrayOf(id))==1){
                "Finance packet not found"
            }
            db.log(id,"finance_status_changed",status+" / "+notes.trim().take(120))
        }
    }
}

data class FinancePacketRecord(
    val id:String,val reference:String,val title:String,val prDate:String,
    val purchaseDate:String,val purchaseDateRaw:String,val cents:Long,
    val vehicle:String,val station:String,val sentAt:String,val sentEpoch:Long,
    val to:String,val cc:String,val subject:String,val pdfUri:String,
    val sha256:String,val pdfBytes:Long,val financeStatus:String,
    val notes:String,val updatedEpoch:Long
)
