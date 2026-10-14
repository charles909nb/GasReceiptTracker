package org.karuk.gasreceipttracker

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Bounded portable ZIP backup. A restore never treats an unfinished email as sent. */
object TrackerBackup {
    private val keys = (RequestWorkflow.DRAFT_KEYS + RequestWorkflow.PROFILE_KEYS + listOf(
        "trips","receipts","archives","signatureFile","emailTo","emailSubject","emailBody","myEmail","emailCc",
        "vehicles","ccSelf","setupDone",RequestWorkflow.STATE)).distinct()
    private fun safeName(name:String)=name.matches(Regex("(?:receipt_[0-9]+\\.jpg|reimbursement_signature\\.png|signature_[a-zA-Z0-9_-]+\\.png)"))
    private fun safeExport(name:String)=name.matches(Regex("[A-Za-z0-9_-]+\\.(?:pdf|xls)"))
    fun export(context:Context,destination:Uri){
        RequestWorkflow(context).sync()
        val prefs=context.getSharedPreferences("usage_data",Context.MODE_PRIVATE)
        val data=JSONObject().put("format",3)
            .put("databaseSnapshot",TrackerDatabase(context).use{it.snapshot()})
        prefs.all.forEach{(k,v)->if(k in keys || k.startsWith("lastOdometer_"))data.put(k,v)}
        context.contentResolver.openOutputStream(destination,"wt")?.use{stream->ZipOutputStream(stream).use{zip->
            zip.putNextEntry(ZipEntry("data.json"));zip.write(data.toString().toByteArray());zip.closeEntry()
            fun add(file:File,path:String){zip.putNextEntry(ZipEntry(path));file.inputStream().use{it.copyTo(zip)};zip.closeEntry()}
            context.filesDir.listFiles()?.filter{it.isFile&&safeName(it.name)}?.forEach{add(it,"files/"+it.name)}
            File(context.filesDir,"exports").listFiles()?.filter{it.isFile&&safeExport(it.name)}?.forEach{add(it,"exports/"+it.name)}
        }}?:error("Cannot write backup")
    }
    fun restore(context:Context,source:Uri){
        val stage=File(context.cacheDir,"restore_"+UUID.randomUUID()).apply{mkdirs()}
        try {
            var total=0L;var count=0
            val seen=mutableSetOf<String>()
            context.contentResolver.openInputStream(source)?.use{stream->ZipInputStream(stream).use{zip->
                var entry=zip.nextEntry
                val buffer=ByteArray(16384)
                while(entry!=null){
                    val name=entry.name
                    require(++count<=2000 && seen.add(name)){"Backup has too many or duplicate entries"}
                    val accepted=name=="data.json" || (name.startsWith("files/") && safeName(name.removePrefix("files/"))) ||
                        (name.startsWith("exports/") && safeExport(name.removePrefix("exports/")))
                    require(!name.contains("..") && !name.startsWith("/")){"Unsafe backup path"}
                    if(accepted){
                        val out=File(stage,name).apply{parentFile?.mkdirs()};var size=0L
                        out.outputStream().use{target->
                            var n=zip.read(buffer)
                            while(n>=0){size+=n;total+=n;require(size<=90_000_000 && total<=1_000_000_000){"Backup exceeds safe restore size"}
                                target.write(buffer,0,n);n=zip.read(buffer)}
                        }
                    }
                    zip.closeEntry();entry=zip.nextEntry
                }
            }}?:error("Cannot open backup")
            val sourceData=File(stage,"data.json");require(sourceData.isFile && sourceData.length()<50_000_000){"Backup data missing or too large"}
            val data=JSONObject(sourceData.readText());require(data.optInt("format") in 1..3){"Unsupported backup format"}
            for(k in listOf("trips","receipts","archives"))JSONArray(data.optString(k,"[]").ifBlank{"[]"})
            if(data.has(RequestWorkflow.STATE)){
                val state=JSONObject(data.getString(RequestWorkflow.STATE));require(state.optInt("schema")==1)
                val orders=state.getJSONArray("orders");require((0 until orders.length()).any{orders.getJSONObject(it).optString("id")==state.optString("activeId")}){"Active request not found in backup"}
            }
            fun relink(value:Any?):Any?{
                if(value is JSONObject){val names=value.keys().asSequence().toList();names.forEach{k->value.put(k,relink(value.opt(k)))};return value}
                if(value is JSONArray){for(i in 0 until value.length())value.put(i,relink(value.opt(i)));return value}
                if(value is String){
                    val name=Uri.parse(value).lastPathSegment.orEmpty()
                    if(safeName(name) && File(stage,"files/"+name).isFile){val f=File(context.filesDir,name);return if(value.startsWith("file:"))Uri.fromFile(f).toString()else f.absolutePath}
                    if(value.startsWith("content:") && safeExport(name) && File(stage,"exports/"+name).isFile)
                        return OfficialPurchaseRequisition.uri(context,File(context.filesDir,"exports/"+name)).toString()
                }
                return value
            }
            for(k in listOf("trips","receipts","archives",RequestWorkflow.STATE))if(data.has(k)){
                val raw=data.getString(k);val value=if(k==RequestWorkflow.STATE)JSONObject(raw)else JSONArray(raw.ifBlank{"[]"})
                data.put(k,relink(value).toString())
            }
            relink(data)
            val rollback=File(stage,"rollback");val installed=mutableListOf<Pair<File,File?>>()
            try{
                for(folder in listOf("files","exports"))File(stage,folder).listFiles()?.forEach{file->
                    val target=File(context.filesDir,if(folder=="files")file.name else "exports/"+file.name).apply{parentFile?.mkdirs()}
                    val old=if(target.exists())File(rollback,folder+"/"+file.name).also{it.parentFile?.mkdirs();target.copyTo(it,true)}else null
                    installed.add(target to old);file.copyTo(target,true)
                }
                val prefs=context.getSharedPreferences("usage_data",Context.MODE_PRIVATE)
                val previousPrefs=prefs.all.toMap()
                val db=TrackerDatabase(context)
                val previousDatabase=db.snapshot()
                try {
                    val edit=prefs.edit()
                    (keys+listOf(RequestWorkflow.PENDING_EMAIL,"requestEmailLeftApp","pendingSignatureAction",
                        "pendingSignatureRequest","lastConfirmedEmailToken","reviewedRequestFingerprint")).forEach{edit.remove(it)}
                    prefs.all.keys.filter{it.startsWith("lastOdometer_")}.forEach{edit.remove(it)}
                    data.keys().forEach{k->if(k in keys||k.startsWith("lastOdometer_"))when(val v=data.opt(k)){
                        is Boolean->edit.putBoolean(k,v)
                        else->edit.putString(k,v.toString())
                    }}
                    check(edit.commit()){"Could not save restored records"}
                    val snapshot=data.optJSONObject("databaseSnapshot") ?: JSONObject().put("schema",1)
                    db.restoreSnapshot(snapshot)
                    // Older backup formats have no database; rebuild the index
                    // from their saved workflow without losing existing receipts.
                    RequestWorkflow(context).initialize()
                }catch(e:Exception){
                    db.restoreSnapshot(previousDatabase)
                    val rollbackPrefs=prefs.edit().clear()
                    for((key,v) in previousPrefs)when(v){
                        is String->rollbackPrefs.putString(key,v)
                        is Boolean->rollbackPrefs.putBoolean(key,v)
                        is Int->rollbackPrefs.putInt(key,v)
                        is Long->rollbackPrefs.putLong(key,v)
                        is Float->rollbackPrefs.putFloat(key,v)
                    }
                    rollbackPrefs.commit()
                    throw e
                }finally{db.close()}
            }catch(e:Exception){installed.reversed().forEach{(f,old)->if(old!=null)old.copyTo(f,true)else f.delete()};throw e}
        }finally{stage.deleteRecursively()}
    }
}
