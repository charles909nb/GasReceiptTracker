package org.karuk.gasreceipttracker

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The original image remains unchanged. Red highlights show the selected OCR
 * field, yellow highlights show other recognized fields. Corrections never move
 * those highlights: the saved coordinates document what OCR actually saw.
 */
class ReceiptReviewActivity:Activity() {
    companion object {
        const val EXTRA_REQUEST_ID="requestId"
        const val EXTRA_HISTORY="readOnlyHistory"
        const val EXTRA_RECEIPT="savedReceipt"
        const val EXTRA_EDIT_HISTORY="editSavedReceipt"
    }
    private data class Field(val key:String,val title:String,val pref:String,val record:String)
    private val fields=listOf(
        Field("station","Gas station","ocr_station","station"),
        Field("date","Receipt purchase date","ocr_date","date"),
        Field("time","Receipt purchase time","ocr_time","receiptTime"),
        Field("fuelType","Fuel type","ocr_fuel_type","fuelType"),
        Field("gallons","Gallons","ocr_gallons","gallons"),
        Field("price","Price per gallon","ocr_price","price"),
        Field("total","Fuel total paid","ocr_total","cost"),
        Field("cardType","Payment card type","ocr_card_type","cardType"),
        Field("cardLast4","Payment card last four","ocr_card_last4","receiptCardLast4"),
        Field("pump","Pump number","ocr_pump","pumpNumber")
    )
    private val prefs by lazy{getSharedPreferences("usage_data",MODE_PRIVATE)}
    private lateinit var scroller:ScrollView
    private lateinit var receiptHorizontal:HorizontalScrollView
    private lateinit var zoomLabel:TextView
    private var receiptZoom=1f
    private var receiptBaseWidth=0
    private lateinit var overlay:ReceiptHighlightView
    private lateinit var prompt:TextView
    private lateinit var fieldPicker:Spinner
    private lateinit var suggestionText:TextView
    private lateinit var suggestionAccept:Button
    private lateinit var suggestionDiscard:Button
    private data class ScanProposal(val field:String,val value:String,val box:ReceiptEvidence?)
    private var suggestion:ScanProposal?=null
    private lateinit var count:TextView
    private lateinit var evidenceText:TextView
    private lateinit var cardStatus:TextView
    private lateinit var acknowledgement:CheckBox
    private lateinit var entry:EditText
    private lateinit var savedReceipt:JSONObject
    private var expectedRequestId=""
    private var readonly=false
    private var editingSaved=false
    private var position=4
    private var ignoreChange=false
    private var saved=false
    private var image:android.graphics.Bitmap?=null
    private var evidence:List<ReceiptEvidence> = emptyList()
    private val values=linkedMapOf<String,String>()
    private val tappedLocations=mutableMapOf<String,ReceiptEvidence>()
    private var scanning=false
    private lateinit var original:Map<String,String>

    private fun dp(n:Int)=TrackerStyle.dp(this,n)
    private fun pref(k:String)=prefs.getString(k,"").orEmpty()
    private fun timestamp()=SimpleDateFormat("MMM d, yyyy h:mm a",Locale.US).format(Date())
    private fun currentRequestId()=runCatching{
        JSONObject(pref(RequestWorkflow.STATE)).optString("activeId")
    }.getOrDefault("")
    private fun cardCheck():CardCheck{
        val suffix=if(readonly||editingSaved)savedReceipt.optString("profileCardLast4AtReview").ifBlank{pref("cardLast4")}
            else pref("cardLast4")
        val brand=if(readonly||editingSaved)savedReceipt.optString("profileCardBrandAtReview").ifBlank{pref("cardBrand")}
            else pref("cardBrand")
        return ReceiptCardVerifier.verify(suffix,brand,
            values["cardLast4"].orEmpty(),values["cardType"].orEmpty())
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        editingSaved=intent.getBooleanExtra(EXTRA_EDIT_HISTORY,false)
        readonly=intent.getBooleanExtra(EXTRA_HISTORY,false) && !editingSaved
        expectedRequestId=intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        savedReceipt=runCatching{JSONObject(intent.getStringExtra(EXTRA_RECEIPT)?:"{}")}.getOrDefault(JSONObject())
        val path=if(readonly||editingSaved)savedReceipt.optString("image")else pref("pending_receipt")
        if(path.isBlank()){errorScreen("No receipt image is attached.");return}
        image=runCatching{ReceiptImage.loadUpright(this,Uri.parse(path))}.getOrNull()
        if(image==null){errorScreen("The receipt photograph could not be opened. Your saved records were not modified.");return}
        evidence=ReceiptEvidence.fromArray(if(readonly||editingSaved)
            savedReceipt.optJSONArray("ocrEvidence")?.toString()?:"[]" else pref("ocr_evidence"))
        for(field in fields)values[field.key]=if(readonly||editingSaved)savedReceipt.optString(field.record)else pref(field.pref)
        original=values.toMap()
        if(readonly)position=8
        buildScreen()
    }
    private fun errorScreen(message:String){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL
            setPadding(dp(22),dp(24),dp(22),dp(20))}
        root.addView(TrackerStyle.text(this,"Review receipt",23f,true,TrackerStyle.red))
        root.addView(TrackerStyle.text(this,message,16f))
        root.addView(TrackerStyle.button(this,"Return"){finish()})
        setContentView(root);TrackerStyle.insets(this,root)
    }
    private fun buildScreen(){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL
            setBackgroundColor(TrackerStyle.background)}
        val header=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(12),dp(18),dp(12));setBackgroundColor(TrackerStyle.red)}
        header.addView(TrackerStyle.text(this,if(readonly)"Saved receipt verification"else if(editingSaved)"Correct saved receipt"else "Check your receipt",
            20f,true,Color.WHITE))
        header.addView(TrackerStyle.text(this,"Choose a field from the list, then correct its value. Tapping the photo will suggest a value, but nothing changes until you accept it.",
            13f,false,Color.WHITE))
        if(editingSaved) header.addView(TrackerStyle.text(this,
            "Corrections update the searchable record. An already-sent Finance packet stays unchanged.",12f,false,Color.WHITE))
        root.addView(header)
        scroller=ScrollView(this).apply{setBackgroundColor(Color.WHITE)}
        overlay=ReceiptHighlightView(this,requireNotNull(image),evidence,
            { selected ->
                val i=fields.indexOfFirst{it.key==selected}
                if(i>=0 && readonly)selectField(i)
                else if(i>=0){
                    // Critical: photo taps must NEVER silently switch editable fields.
                    val name=fields[i].title
                    val message="That is the $name area. Choose $name from the list first; your current value was not changed."
                    evidenceText.text=message
                    Toast.makeText(this,message,Toast.LENGTH_LONG).show()
                }
            },
            { x,y -> rescanAt(x,y) })
        receiptBaseWidth=resources.displayMetrics.widthPixels.coerceAtLeast(dp(250))
        overlay.setDisplayWidth(receiptBaseWidth)
        receiptHorizontal=HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=true
            isFillViewport=false
            addView(this@ReceiptReviewActivity.overlay,android.widget.FrameLayout.LayoutParams(receiptBaseWidth,-2))
        }
        scroller.addView(receiptHorizontal)
        root.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        val zoomRow=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16),dp(2),dp(16),dp(2))
            setBackgroundColor(Color.WHITE)
        }
        zoomLabel=TrackerStyle.text(this,"Receipt zoom 100%",12f,true,TrackerStyle.ink)
        zoomRow.addView(zoomLabel)
        val zoomSlider=SeekBar(this).apply {
            max=300
            progress=0
            contentDescription="Receipt zoom slider, 100 to 400 percent"
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean){
                    if(!fromUser)return
                    receiptZoom=1f+value/100f
                    zoomLabel.text="Receipt zoom ${(receiptZoom*100).toInt()}%"
                    this@ReceiptReviewActivity.overlay.setDisplayWidth((receiptBaseWidth*receiptZoom).toInt())
                }
                override fun onStartTrackingTouch(bar:SeekBar?){}
                override fun onStopTrackingTouch(bar:SeekBar?){}
            })
        }
        zoomRow.addView(zoomSlider,LinearLayout.LayoutParams(0,dp(42),1f))
        root.addView(zoomRow)
        val controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(8),dp(18),dp(10));setBackgroundColor(Color.WHITE)}
        controls.addView(TrackerStyle.text(this,"1. Choose the ONE field to fix",15f,true,TrackerStyle.ink))
        fieldPicker=Spinner(this).apply{
            contentDescription="Choose receipt field to correct"
            adapter=ArrayAdapter<String>(this@ReceiptReviewActivity,
                android.R.layout.simple_spinner_dropdown_item, fields.map{it.title})
            background=TrackerStyle.shape(this@ReceiptReviewActivity,Color.WHITE,TrackerStyle.line,10)
            setPadding(dp(12),dp(8),dp(12),dp(8))
            minimumHeight=dp(54)
        }
        controls.addView(fieldPicker,LinearLayout.LayoutParams(-1,dp(54)))
        count=TrackerStyle.text(this,"",12f,true,TrackerStyle.muted)
        controls.addView(count)
        prompt=TrackerStyle.text(this,"",14f,true)
        controls.addView(prompt)
        evidenceText=TrackerStyle.text(this,"",12f,false,TrackerStyle.muted).apply{
            setPadding(0,dp(2),0,dp(7))}
        controls.addView(evidenceText)
        controls.addView(TrackerStyle.text(this,
            "2. Type the correct value below, OR tap its location in the photo above to get a suggestion.",
            13f,false,TrackerStyle.muted))
        entry=EditText(this).apply{
            hint="Check this against the receipt"
            contentDescription="Correct current receipt field"
            setSingleLine(true)
        }
        TrackerStyle.input(this,entry);controls.addView(entry)
        suggestionText=TrackerStyle.text(this,"",14f,true,TrackerStyle.ink).apply{visibility=View.GONE}
        controls.addView(suggestionText)
        suggestionAccept=TrackerStyle.button(this,"Use scanned suggestion",true){
            val proposed=suggestion?:return@button
            if(readonly || proposed.field!=fields[position].key)return@button
            values[proposed.field]=proposed.value
            proposed.box?.let{tappedLocations[proposed.field]=it;overlay.addHighlight(it)}
            entry.setText(proposed.value)
            entry.setSelection(entry.text.length)
            clearSuggestion()
            evidenceText.text="Applied to ${fields[position].title} only. Other fields remain unchanged."
        }
        suggestionAccept.visibility=View.GONE
        controls.addView(suggestionAccept)
        suggestionDiscard=TrackerStyle.button(this,"Ignore suggestion"){
            clearSuggestion()
        }
        suggestionDiscard.visibility=View.GONE
        controls.addView(suggestionDiscard)
        cardStatus=TrackerStyle.text(this,"",13f,false).apply{
            setPadding(dp(8),dp(5),dp(8),dp(5))}
        controls.addView(cardStatus)
        acknowledgement=CheckBox(this).apply{
            text="I checked the receipt and confirm a different card was used for this purchase."
            buttonTintList=ColorStateList.valueOf(TrackerStyle.red)
        }
        controls.addView(acknowledgement)
        controls.addView(TrackerStyle.button(this,
            if(readonly)"Close receipt"else if(editingSaved)"Save corrected receipt"else "Save checked fields and return",true){
            if(readonly)finish()else saveReview()
        })
        root.addView(controls)
        setContentView(root);TrackerStyle.insets(this,root)
        fieldPicker.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:View?,index:Int,id:Long){
                if(index!=position)selectField(index)
            }
        }
        entry.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                if(ignoreChange||readonly)return
                val key=fields[position].key
                val value=s?.toString().orEmpty()
                if(values[key]!=value){
                    clearSuggestion()
                    values[key]=value
                    if(key=="cardLast4"||key=="cardType")acknowledgement.isChecked=false
                    renderCard()
                }
            }
            override fun afterTextChanged(s:Editable?){}
        })
        renderField()
    }
    private fun clearSuggestion(){
        suggestion=null
        if(!::suggestionText.isInitialized)return
        suggestionText.visibility=View.GONE
        suggestionAccept.visibility=View.GONE
        suggestionDiscard.visibility=View.GONE
    }
    private fun selectField(index:Int){
        if(index !in fields.indices)return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(entry.windowToken,0)
        entry.clearFocus()
        clearSuggestion()
        position=index
        renderField()
    }
    /** Crop around the tapped text, upscale, then rerun ML Kit entirely on device. */
    private fun rescanAt(x:Float,y:Float){
        if(readonly||scanning)return
        val bitmap=image?:return
        val field=fields[position]
        clearSuggestion()
        val w=bitmap.width;val h=bitmap.height
        val cropW=(w*0.34f).toInt().coerceAtLeast(80).coerceAtMost(w)
        val cropH=(h*0.075f).toInt().coerceAtLeast(48).coerceAtMost(h)
        val left=(x*w-cropW/2).toInt().coerceIn(0,w-cropW)
        val top=(y*h-cropH/2).toInt().coerceIn(0,h-cropH)
        val region=Bitmap.createBitmap(bitmap,left,top,cropW,cropH)
        val factor=if(cropW<950)2 else 1
        val enlarged=if(factor>1)Bitmap.createScaledBitmap(region,cropW*factor,cropH*factor,true)else region
        scanning=true
        evidenceText.text="Reading the selected area on this phone..."
        val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(enlarged,0)).addOnSuccessListener{result->
            val best=result.textBlocks.flatMap{it.lines}
                .map{line->line to ReceiptLocalLearning.extract(field.key,line.text)}
                .filter{it.second.isNotBlank()}
                .minByOrNull{pair->
                    val rect=pair.first.boundingBox
                    if(rect==null)Double.MAX_VALUE else
                        kotlin.math.abs(rect.centerX()-enlarged.width/2).toDouble()/enlarged.width+
                        kotlin.math.abs(rect.centerY()-enlarged.height/2).toDouble()/enlarged.height
                }
            if(best==null){
                evidenceText.text="No valid "+field.title.lowercase()+" found there. Try another spot or enter it manually."
            }else{
                val (line,value)=best
                val rect=line.boundingBox
                val box=if(rect!=null){
                    ReceiptEvidence(field.key,ReceiptPrivacy.redact(line.text),
                        ((left+rect.left.toFloat()/factor)/w).coerceIn(0f,1f),
                        ((top+rect.top.toFloat()/factor)/h).coerceIn(0f,1f),
                        ((left+rect.right.toFloat()/factor)/w).coerceIn(0f,1f),
                        ((top+rect.bottom.toFloat()/factor)/h).coerceIn(0f,1f),"tap-rescan")
                }else null
                if(fields[position].key!=field.key){
                    evidenceText.text="You changed fields during the scan; the old result was ignored."
                }else{
                    // OCR must never change another field or overwrite the current value.
                    suggestion=ScanProposal(field.key,value,box)
                    suggestionText.text="Suggested value: $value. Current value: ${values[field.key].orEmpty().ifBlank{"blank"}}."
                    suggestionText.visibility=View.VISIBLE
                    suggestionAccept.visibility=View.VISIBLE
                    suggestionDiscard.visibility=View.VISIBLE
                    evidenceText.text="This is a suggestion for ${field.title} ONLY. Accept it or ignore it."
                }
            }
        }.addOnFailureListener{
            evidenceText.text="Could not read that area. Try again or type the value."
        }.addOnCompleteListener{
            scanning=false
            recognizer.close()
            if(enlarged!==region)enlarged.recycle()
            region.recycle()
        }
    }
    private fun step(direction:Int){
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(entry.windowToken,0)
        entry.clearFocus()
        selectField((position+direction+fields.size)%fields.size)
    }
    private fun renderField(){
        val field=fields[position]
        overlay.activeField=field.key
        if(fieldPicker.selectedItemPosition!=position)fieldPicker.setSelection(position)
        count.text="Field "+(position+1)+" of "+fields.size+" • Red: selected area; yellow: other fields"
        prompt.text=if(readonly)"Recorded field (read only)" else "Current value (only this field is editable)"
        val target=evidence.firstOrNull{it.field==field.key}
        evidenceText.text=if(target==null)"Not located by the scanner; check the full photograph."
            else "Found in "+target.source+" OCR: "+target.recognized.take(140)
        ignoreChange=true
        entry.inputType=when(field.key){
            "cardLast4","pump"->InputType.TYPE_CLASS_NUMBER
            "gallons","price","total"->InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else->InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        entry.filters=if(field.key=="cardLast4")arrayOf(InputFilter.LengthFilter(4))else emptyArray()
        entry.contentDescription="Edit ${field.title} only"
        entry.setText(values[field.key].orEmpty())
        entry.setSelection(entry.text.length)
        entry.isEnabled=!readonly
        ignoreChange=false
        renderCard()
        scroller.post{
            overlay.selectedRect()?.let{rect->
                scroller.smoothScrollTo(0,(rect.centerY()-scroller.height/2).toInt().coerceAtLeast(0))
            }
        }
    }
    private fun renderCard(){
        val card=cardCheck()
        val historic=if(readonly)savedReceipt.optString("cardVerificationStatus") else ""
        cardStatus.text=if(readonly)
            "Stored card check: "+historic.ifBlank{"Not verified"}+"\n"+card.message
            else if(card.mismatch)"CARD MISMATCH: "+card.message
            else "CARD CHECK: "+card.message
        cardStatus.setTextColor(if(card.mismatch)TrackerStyle.red else
            if(card.status=="MATCH")TrackerStyle.green else TrackerStyle.muted)
        cardStatus.background=TrackerStyle.shape(this,
            if(card.mismatch)TrackerStyle.blush else TrackerStyle.background)
        val reviewingCard=fields[position].key=="cardLast4"||fields[position].key=="cardType"
        cardStatus.visibility=if(reviewingCard)View.VISIBLE else View.GONE
        acknowledgement.visibility=if(!readonly&&card.mismatch&&reviewingCard)View.VISIBLE else View.GONE
    }
    private fun saveDraft(){
        if(readonly||editingSaved||saved)return
        if(currentRequestId()!=expectedRequestId)return
        val editor=prefs.edit()
        for(field in fields){
            val value=values[field.key].orEmpty()
            if(field.key=="cardLast4"&&value.isNotBlank()&&ReceiptPrivacy.last4(value).isBlank())continue
            editor.putString(field.pref,value)
        }
        editor.commit()
    }
    override fun onPause(){saveDraft();super.onPause()}
    override fun onDestroy(){image?.recycle();image=null;super.onDestroy()}
    private fun learnConfirmedLocations(changedKeys:JSONArray) {
        val station=values["station"].orEmpty()
        for(field in fields){
            if(values[field.key].orEmpty().isBlank())continue
            // Only explicitly accepted tapped locations teach layout positions.
            // A manually corrected value alone cannot prove that the scanner
            // associated the original OCR box with the right field.
            val location=tappedLocations[field.key]
            if(location!=null)runCatching{ReceiptLocalLearning.learn(this,station,field.key,location)}
        }
    }
    private fun saveReview(){
        if(!editingSaved && currentRequestId()!=expectedRequestId){
            Toast.makeText(this,"The active PR changed. Return to Fuel to reopen this receipt.",Toast.LENGTH_LONG).show()
            return
        }
        val suffix=values["cardLast4"].orEmpty()
        if(suffix.isNotBlank()&&ReceiptPrivacy.last4(suffix).isBlank()){
            selectField(8)
            entry.error="Enter the last four digits only, or leave blank when unreadable"
            return
        }
        val card=cardCheck()
        if(card.mismatch&&!acknowledgement.isChecked){
            selectField(8)
            android.app.AlertDialog.Builder(this).setTitle("Different card found")
                .setMessage(card.message+"\nCheck the highlighted digits on the receipt. " +
                    "If the other card was actually used, check the acknowledgment before saving.")
                .setPositiveButton("Review card",null).show()
            return
        }
        if(editingSaved && (values["date"].orEmpty().isBlank() || values["station"].orEmpty().isBlank() ||
            (values["gallons"].orEmpty().toDoubleOrNull() ?: 0.0) <= 0.0 ||
            (values["total"].orEmpty().toDoubleOrNull() ?: 0.0) <= 0.0 ||
            (values["price"].orEmpty().isNotBlank() &&
                (values["price"].orEmpty().toDoubleOrNull() ?: 0.0) <= 0.0))) {
            Toast.makeText(this,"Check the station, date, gallons, unit price and total before saving.",
                Toast.LENGTH_LONG).show()
            return
        }
        val evidenceAtConfirmation=evidence.filter{old->tappedLocations[old.field]==null}+tappedLocations.values
        val corrections=if(editingSaved) JSONArray(savedReceipt.optJSONArray("ocrCorrections")?.toString()?:"[]") else JSONArray()
        val changedKeys=JSONArray()
        for(field in fields) if(original[field.key].orEmpty().trim()!=values[field.key].orEmpty().trim())
            changedKeys.put(field.key)
        for(field in fields)if(original[field.key].orEmpty()!=values[field.key].orEmpty().trim()){
            corrections.put(JSONObject().put("field",field.key)
                .put("readByScanner",ReceiptPrivacy.redact(original[field.key].orEmpty()))
                .put("confirmedValue",ReceiptPrivacy.redact(values[field.key].orEmpty().trim()))
                .put("time",timestamp()))
        }
        if(editingSaved){
            val updates=JSONObject()
            for(field in fields) if(original[field.key].orEmpty().trim()!=values[field.key].orEmpty().trim())
                updates.put(field.record, values[field.key].orEmpty().trim())
            updates.put("changedFields",changedKeys)
                .put("ocrCorrections",corrections)
                .put("ocrEvidence",ReceiptEvidence.asJson(evidenceAtConfirmation))
                .put("reviewedAt",timestamp())
                .put("cardVerificationStatus",if(card.mismatch)"MISMATCH_ACKNOWLEDGED" else card.status)
                .put("cardMismatchAcknowledged",card.mismatch&&acknowledgement.isChecked)
            if(updates.has("receiptCardLast4"))
                updates.put("cardLast4",ReceiptPrivacy.last4(values["cardLast4"].orEmpty()))
            try{
                RequestWorkflow(this).correctSavedReceipt(expectedRequestId,
                    savedReceipt.getString("id"),updates)
            }catch(e:Exception){
                Toast.makeText(this,e.message?:"Could not save the corrected receipt.",
                    Toast.LENGTH_LONG).show()
                return
            }
            learnConfirmedLocations(changedKeys)
            saved=true
            setResult(RESULT_OK);finish()
            return
        }
        val editor=prefs.edit()
        for(field in fields)editor.putString(field.pref,values[field.key].orEmpty().trim())
        editor.putString("ocr_evidence",ReceiptEvidence.asJson(evidenceAtConfirmation).toString())
        editor.putString("ocr_card_verification",if(card.mismatch)"MISMATCH_ACKNOWLEDGED"else card.status)
            .putString("ocr_card_mismatch_ack",(card.mismatch&&acknowledgement.isChecked).toString())
            .putString("ocr_profile_card_last4_at_review",pref("cardLast4"))
            .putString("ocr_profile_card_brand_at_review",pref("cardBrand"))
            .putString("ocr_reviewed_at",timestamp())
            .putString("ocr_corrections",corrections.toString())
        if(!editor.commit()){
            Toast.makeText(this,"Could not save verified receipt fields.",Toast.LENGTH_LONG).show()
            return
        }
        learnConfirmedLocations(changedKeys)
        runCatching{RequestWorkflow(this).sync()}
        saved=true
        setResult(RESULT_OK);finish()
    }
}