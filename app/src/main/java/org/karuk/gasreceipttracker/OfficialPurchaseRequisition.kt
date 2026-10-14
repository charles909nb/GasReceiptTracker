package org.karuk.gasreceipttracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Always uses the bundled original Gas worksheet. No user import is needed. */
object OfficialPurchaseRequisition {
    data class Prepared(val excel: File, val pdf: File, val signed: Boolean, val requestDate: String)
    fun hasTemplate(context: Context): Boolean = runCatching {
        context.assets.open(GasFormWorkbook.ASSET).use { it.read() >= 0 }
    }.getOrDefault(false)
    fun uri(context: Context, file: File): Uri = FileProvider.getUriForFile(context, context.packageName+".documents",file)
    private fun digest(value: ByteArray)=MessageDigest.getInstance("SHA-256").digest(value).joinToString(""){"%02x".format(it)}
    private fun signature(file: File?): Triple<ByteArray?,Int,Int> {
        if(file==null || !file.isFile) return Triple(null,0,0)
        val source=BitmapFactory.decodeFile(file.absolutePath) ?: error("Saved signature cannot be read; sign again")
        var l=source.width;var t=source.height;var r=-1;var b=-1
        for(y in 0 until source.height)for(x in 0 until source.width){
            val p=source.getPixel(x,y)
            if(android.graphics.Color.alpha(p)>32 && (p and 0x00ffffff)<0x00eeeeee){l=minOf(l,x);r=maxOf(r,x);t=minOf(t,y);b=maxOf(b,y)}
        }
        require(r>l && b>t){"Signature is blank; please sign again"}
        l=maxOf(0,l-3);t=maxOf(0,t-3);r=minOf(source.width-1,r+3);b=minOf(source.height-1,b+3)
        val trimmed=Bitmap.createBitmap(source,l,t,r-l+1,b-t+1)
        val stream=ByteArrayOutputStream();trimmed.compress(Bitmap.CompressFormat.PNG,100,stream)
        val result=Triple(stream.toByteArray(),trimmed.width,trimmed.height)
        if(trimmed !== source)trimmed.recycle();source.recycle();return result
    }
    fun prepare(context: Context, receipt: JSONObject, driver: String, card: String, signatureFile: File?, clock: java.time.Clock = java.time.Clock.systemDefaultZone()): Prepared {
        val requestDate = java.time.LocalDate.now(clock).toString()
        val sig=signature(signatureFile)
        val template=context.assets.open(GasFormWorkbook.ASSET).use { it.readBytes() }
        require(digest(template)==GasFormWorkbook.TEMPLATE_SHA256){"The built-in official worksheet is damaged"}
        val bytes=GasFormWorkbook.fill(template.inputStream(),requestDate,
            receipt.optString("driver").ifBlank{driver},receipt.optString("cardLast4").ifBlank{card},
            receipt.optString("vehicle"),receipt.optString("gallons"),receipt.optString("price"),receipt.optString("cost"),sig.first,sig.second,sig.third)
        val directory=File(context.filesDir,"exports").apply { mkdirs() }
        val key=digest(bytes).take(24)
        val excel=File(directory,"Karuk_Gas_$key.xls");val pdf=File(directory,"Karuk_Gas_$key.pdf")
        if(!excel.isFile){
            val part=File(directory,excel.name+".part")
            FileOutputStream(part).use{it.write(bytes);it.fd.sync()}
            check(part.renameTo(excel)){"Could not save the Excel requisition"}
        }
        if(!pdf.isFile){
            val part=File(directory,pdf.name+".part")
            try{GasSheetPdf.write(bytes,part);check(part.renameTo(pdf)){"Could not save PDF preview"}}
            catch(e:Exception){part.delete();throw e}
        }
        val record=org.json.JSONObject().put("requestDate",requestDate).put("receiptDate",receipt.optString("date"))
            .put("vehicle",receipt.optString("vehicle")).put("station",receipt.optString("station")).put("cost",receipt.optString("cost"))
            .put("receipt",org.json.JSONObject(receipt.toString())).put("driver",driver).put("signatureFile",signatureFile?.absolutePath.orEmpty())
        val state=org.json.JSONObject(LedgerStore.prefs(context).getString(RequestWorkflow.STATE,"{}").orEmpty())
        record.put("requestId",state.optString("activeId"))
        state.optJSONArray("orders")?.let { a -> for(i in 0 until a.length())if(a.getJSONObject(i).optString("id")==state.optString("activeId"))record.put("reference",a.getJSONObject(i).optString("reference")) }
        LedgerStore.rememberDocument(context,excel.name,org.json.JSONObject(record.toString()).put("format","xls").put("path",excel.absolutePath))
        LedgerStore.rememberDocument(context,pdf.name,org.json.JSONObject(record.toString()).put("format","pdf").put("path",pdf.absolutePath))
        return Prepared(excel,pdf,sig.first!=null,requestDate)
    }
    fun export(context: Context, receipt: JSONObject, driver: String, cardLast4: String, signature: File?): Uri =
        uri(context,prepare(context,receipt,driver,cardLast4,signature).excel)
}
