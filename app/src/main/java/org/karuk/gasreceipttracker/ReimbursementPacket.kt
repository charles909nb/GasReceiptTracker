package org.karuk.gasreceipttracker

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** The first PDF page is rendered from the same original XLS that is exported. */
object ReimbursementPacket {
    fun hasTemplate(context: Context): Boolean = OfficialPurchaseRequisition.hasTemplate(context)
    fun create(context: Context, trips: JSONArray, receipts: JSONArray,
               driver: String, card: String, signaturePath: String): Uri {
        require(receipts.length()==1){"Select one fuel receipt for this reimbursement packet"}
        require(trips.length()>0){"Record at least one trip for the Vehicle Usage Sheet"}
        val receipt=receipts.getJSONObject(0)
        for(i in 0 until trips.length()){
            val trip=trips.getJSONObject(i)
            require(trip.has("endOdometer")){"Finish the open trip before finalizing reimbursement"}
            require(trip.optString("vehicle")==receipt.optString("vehicle")){"The receipt and trips must belong to the same vehicle"}
        }
        require(signaturePath.isNotBlank() && File(signaturePath).isFile){"Sign the requisition before preparing the final packet"}
        val prepared=OfficialPurchaseRequisition.prepare(context,receipt,driver,card,File(signaturePath))
        require(prepared.signed){"A signature is required for the final reimbursement packet"}
        val identity=prepared.excel.name+trips.toString()+receipts.toString()+"packet-v2"
        val key=MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString(""){"%02x".format(it)}.take(24)
        val destination=File(context.filesDir,"exports/Karuk_Reimbursement_$key.pdf")
        fun remember(){
            val state=org.json.JSONObject(LedgerStore.prefs(context).getString(RequestWorkflow.STATE,"{}").orEmpty())
            val record=org.json.JSONObject().put("requestDate",prepared.requestDate).put("receiptDate",receipt.optString("date"))
                .put("requestId",state.optString("activeId")).put("format","pdf").put("documentType","Reimbursement packet")
                .put("path",destination.absolutePath).put("receipt",receipt).put("trips",trips)
                .put("vehicle",receipt.optString("vehicle")).put("station",receipt.optString("station")).put("cost",receipt.optString("cost"))
            state.optJSONArray("orders")?.let{a->for(i in 0 until a.length())if(a.getJSONObject(i).optString("id")==state.optString("activeId"))record.put("reference",a.getJSONObject(i).optString("reference"))}
            LedgerStore.rememberDocument(context,destination.name,record)
        }
        if(destination.isFile){remember();return OfficialPurchaseRequisition.uri(context,destination)}
        val part=File(destination.parentFile,destination.name+".part")
        try{
            val doc = PdfDocument()
            try {
                GasSheetPdf.append(doc,prepared.excel.readBytes(),1)
                val imageUri=Uri.parse(receipt.getString("image"))
                val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                fun decode()=if(imageUri.scheme=="file")BitmapFactory.decodeFile(imageUri.path,options)
                    else context.contentResolver.openInputStream(imageUri).use{BitmapFactory.decodeStream(it,null,options)}
                decode();require(options.outWidth>0&&options.outHeight>0){"Receipt image cannot be read"}
                var sample=1;while(options.outWidth/sample>1800||options.outHeight/sample>2400)sample*=2
                options.inJustDecodeBounds=false;options.inSampleSize=sample
                val image=decode()?:error("Receipt image cannot be decoded")
                val page=doc.startPage(PdfDocument.PageInfo.Builder(612,792,2).create())
                val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;isFilterBitmap=true}
                val scale=minOf(540f/image.width,720f/image.height)
                val x=(612-image.width*scale)/2;val y=(792-image.height*scale)/2
                page.canvas.drawBitmap(image,null,RectF(x,y,x+image.width*scale,y+image.height*scale),paint)
                image.recycle();doc.finishPage(page)
                VehicleUsagePdf.append(doc,trips,receipts,3)
                FileOutputStream(part).use{doc.writeTo(it);it.fd.sync()}
            } finally { doc.close() }
            check(part.renameTo(destination)){"Could not save the reimbursement packet"}
            remember()
            return OfficialPurchaseRequisition.uri(context,destination)
        }catch(e:Exception){part.delete();throw e}
    }
}
