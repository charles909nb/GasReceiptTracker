package org.karuk.gasreceipttracker

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
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
        Field("cardLast4","Payment card last four","ocr_card_last4","receiptCardLast4")
    )
    private val prefs by lazy{getSharedPreferences("usage_data",MODE_PRIVATE)}
    private lateinit var scroller:ScrollView
    private lateinit var overlay:ReceiptHighlightView
    private lateinit var prompt:TextView
    private lateinit var count:TextView
    private lateinit var evidenceText:TextView
    private lateinit var cardStatus:TextView
    private lateinit var acknowledgement:CheckBox
    private lateinit var entry:EditText
    private lateinit var savedReceipt:JSONObject
    private var expectedRequestId=""
    private var readonly=false
    private var position=4
    private var ignoreChange=false
    private var saved=false
    private var image:android.graphics.Bitmap?=null
    private var evidence:List<ReceiptEvidence> = emptyList()
    private val values=linkedMapOf<String,String>()
    private lateinit var original:Map<String,String>

    private fun dp(n:Int)=TrackerStyle.dp(this,n)
    private fun pref(k:String)=prefs.getString(k,"").orEmpty()
    private fun timestamp()=SimpleDateFormat("MMM d, yyyy h:mm a",Locale.US).format(Date())
    private fun currentRequestId()=runCatching{
        JSONObject(pref(RequestWorkflow.STATE)).optString("activeId")
    }.getOrDefault("")
    private fun cardCheck():CardCheck{
        val suffix=if(readonly)savedReceipt.optString("profileCardLast4AtReview").ifBlank{pref("cardLast4")}
            else pref("cardLast4")
        val brand=if(readonly)savedReceipt.optString("profileCardBrandAtReview").ifBlank{pref("cardBrand")}
            else pref("cardBrand")
        return ReceiptCardVerifier.verify(suffix,brand,
            values["cardLast4"].orEmpty(),values["cardType"].orEmpty())
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        readonly=intent.getBooleanExtra(EXTRA_HISTORY,false)
        expectedRequestId=intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        savedReceipt=runCatching{JSONObject(intent.getStringExtra(EXTRA_RECEIPT)?:"{}")}.getOrDefault(JSONObject())
        val path=if(readonly)savedReceipt.optString("image")else pref("pending_receipt")
        if(path.isBlank()){errorScreen("No receipt image is attached.");return}
        image=runCatching{ReceiptImage.loadUpright(this,Uri.parse(path))}.getOrNull()
        if(image==null){errorScreen("The receipt photograph could not be opened. Your saved records were not modified.");return}
        evidence=ReceiptEvidence.fromArray(if(readonly)
            savedReceipt.optJSONArray("ocrEvidence")?.toString()?:"[]" else pref("ocr_evidence"))
        for(field in fields)values[field.key]=if(readonly)savedReceipt.optString(field.record)else pref(field.pref)
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
        header.addView(TrackerStyle.text(this,if(readonly)"Saved receipt verification"else "Check your receipt",
            20f,true,Color.WHITE))
        header.addView(TrackerStyle.text(this,"Tap highlighted text or use Previous / Next to examine each field.",
            13f,false,Color.WHITE))
        root.addView(header)
        scroller=ScrollView(this).apply{setBackgroundColor(Color.WHITE)}
        overlay=ReceiptHighlightView(this,requireNotNull(image),evidence){selected->
            val i=fields.indexOfFirst{it.key==selected}
            if(i>=0){position=i;renderField()}
        }
        scroller.addView(overlay)
        root.addView(scroller,LinearLayout.LayoutParams(-1,0,1f))
        val controls=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL
            setPadding(dp(18),dp(8),dp(18),dp(10));setBackgroundColor(Color.WHITE)}
        count=TrackerStyle.text(this,"",12f,true,TrackerStyle.red)
        controls.addView(count)
        prompt=TrackerStyle.text(this,"",18f,true)
        controls.addView(prompt)
        evidenceText=TrackerStyle.text(this,"",12f,false,TrackerStyle.muted).apply{
            setPadding(0,dp(2),0,dp(7))}
        controls.addView(evidenceText)
        entry=EditText(this).apply{
            hint="Check this against the receipt"
            contentDescription="Correct current receipt field"
            setSingleLine(true)
        }
        TrackerStyle.input(this,entry);controls.addView(entry)
        cardStatus=TrackerStyle.text(this,"",13f,false).apply{
            setPadding(dp(8),dp(5),dp(8),dp(5))}
        controls.addView(cardStatus)
        acknowledgement=CheckBox(this).apply{
            text="I checked the receipt and confirm a different card was used for this purchase."
            buttonTintList=ColorStateList.valueOf(TrackerStyle.red)
        }
        controls.addView(acknowledgement)
        val nav=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        nav.addView(TrackerStyle.button(this,"◀ Previous"){step(-1)},
            LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(6)})
        nav.addView(TrackerStyle.button(this,"Next ▶"){step(1)},LinearLayout.LayoutParams(0,-2,1f))
        controls.addView(nav)
        controls.addView(TrackerStyle.button(this,
            if(readonly)"Close receipt"else "Save checked fields and return",true){
            if(readonly)finish()else saveReview()
        })
        root.addView(controls)
        setContentView(root);TrackerStyle.insets(this,root)
        entry.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){
                if(ignoreChange||readonly)return
                val key=fields[position].key
                val value=s?.toString().orEmpty()
                if(values[key]!=value){
                    values[key]=value
                    if(key=="cardLast4"||key=="cardType")acknowledgement.isChecked=false
                    renderCard()
                }
            }
            override fun afterTextChanged(s:Editable?){}
        })
        renderField()
    }
    private fun step(direction:Int){
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(entry.windowToken,0)
        entry.clearFocus()
        position=(position+direction+fields.size)%fields.size
        renderField()
    }
    private fun renderField(){
        val field=fields[position]
        overlay.activeField=field.key
        count.text="FIELD "+(position+1)+" OF "+fields.size+"   •   RED: SELECTED   YELLOW: OTHER FIELDS"
        prompt.text=field.title
        val target=evidence.firstOrNull{it.field==field.key}
        evidenceText.text=if(target==null)"Not located by the scanner; check the full photograph."
            else "Found in "+target.source+" OCR: "+target.recognized.take(140)
        ignoreChange=true
        entry.inputType=when(field.key){
            "cardLast4"->InputType.TYPE_CLASS_NUMBER
            "gallons","price","total"->InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else->InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        entry.filters=if(field.key=="cardLast4")arrayOf(InputFilter.LengthFilter(4))else emptyArray()
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
        acknowledgement.visibility=if(!readonly&&card.mismatch)View.VISIBLE else View.GONE
    }
    private fun saveDraft(){
        if(readonly||saved)return
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
    private fun saveReview(){
        if(currentRequestId()!=expectedRequestId){
            Toast.makeText(this,"The active PR changed. Return to Fuel to reopen this receipt.",Toast.LENGTH_LONG).show()
            return
        }
        val suffix=values["cardLast4"].orEmpty()
        if(suffix.isNotBlank()&&ReceiptPrivacy.last4(suffix).isBlank()){
            position=8;renderField()
            entry.error="Enter the last four digits only, or leave blank when unreadable"
            return
        }
        val card=cardCheck()
        if(card.mismatch&&!acknowledgement.isChecked){
            position=8;renderField()
            android.app.AlertDialog.Builder(this).setTitle("Different card found")
                .setMessage(card.message+"\nCheck the highlighted digits on the receipt. " +
                    "If the other card was actually used, check the acknowledgment before saving.")
                .setPositiveButton("Review card",null).show()
            return
        }
        val corrections=JSONArray()
        for(field in fields)if(original[field.key].orEmpty()!=values[field.key].orEmpty().trim()){
            corrections.put(JSONObject().put("field",field.key)
                .put("readByScanner",ReceiptPrivacy.redact(original[field.key].orEmpty()))
                .put("confirmedValue",ReceiptPrivacy.redact(values[field.key].orEmpty().trim()))
                .put("time",timestamp()))
        }
        val editor=prefs.edit()
        for(field in fields)editor.putString(field.pref,values[field.key].orEmpty().trim())
        editor.putString("ocr_card_verification",if(card.mismatch)"MISMATCH_ACKNOWLEDGED"else card.status)
            .putBoolean("ocr_card_mismatch_ack",card.mismatch&&acknowledgement.isChecked)
            .putString("ocr_profile_card_last4_at_review",pref("cardLast4"))
            .putString("ocr_profile_card_brand_at_review",pref("cardBrand"))
            .putString("ocr_reviewed_at",timestamp())
            .putString("ocr_corrections",corrections.toString())
        if(!editor.commit()){
            Toast.makeText(this,"Could not save verified receipt fields.",Toast.LENGTH_LONG).show()
            return
        }
        runCatching{RequestWorkflow(this).sync()}
        saved=true
        setResult(RESULT_OK);finish()
    }
}
