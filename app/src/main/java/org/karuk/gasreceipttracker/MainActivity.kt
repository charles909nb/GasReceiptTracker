package org.karuk.gasreceipttracker

import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.content.ContentValues
import android.provider.MediaStore
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.widget.*
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("usage_data", MODE_PRIVATE) }
    private val workflow by lazy { RequestWorkflow(this) }
    private lateinit var body: LinearLayout
    private var currentScreen = "home"
    private var showingEmailConfirmation = false
    private var documentBusy = false
    private var cameraUri: Uri? = null
    private val now get() = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(Date())
    private fun data(key: String): JSONArray = JSONArray(prefs.getString(key, "[]").orEmpty().ifBlank{"[]"})
    private fun store(key: String, value: JSONArray) { check(prefs.edit().putString(key, value.toString()).commit()); workflow.sync() }
    private fun pref(key: String) = prefs.getString(key, "") ?: ""
    private fun dp(v:Int)=TrackerStyle.dp(this,v)
    private fun label(text:String,size:Float=18f){
        body.addView(TrackerStyle.text(this,text,if(size==18f)16f else size,size>=20f,
            if(size>=20f)TrackerStyle.ink else TrackerStyle.muted).apply{setPadding(0,dp(9),0,dp(7))})
    }
    private fun edit(hint:String,value:String="",number:Boolean=false):EditText{
        body.addView(TrackerStyle.text(this,hint,13f,true).apply{setPadding(0,dp(7),0,dp(5))})
        val e=EditText(this);e.hint=hint;e.contentDescription=hint;e.setText(value);e.setSingleLine(true)
        if(number)e.inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        else if(hint.contains("email",true))e.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        TrackerStyle.input(this,e);body.addView(e);return e
    }
    private fun action(text:String,run:()->Unit){
        val primary=text in setOf("Start trip","End trip","Save fuel receipt","Save signature","Save signature and continue",
            "Review filled Gas requisition (PDF)","Save setup and start","Scan and crop receipt automatically","Email complete reimbursement packet")
        body.addView(TrackerStyle.button(this,text,primary){try{run()}catch(e:Exception){alert(e.message?:"The operation could not be completed")}})
    }
    private fun alert(text:String){Toast.makeText(this,text,Toast.LENGTH_LONG).show()}
    private fun draw(title:String){
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(TrackerStyle.background)}
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(20),dp(15),dp(20),dp(15));setBackgroundColor(TrackerStyle.red)}
        val icon=ImageView(this).apply{setImageDrawable(applicationInfo.loadIcon(packageManager));contentDescription="Gas Receipt Tracker"}
        header.addView(icon,LinearLayout.LayoutParams(dp(40),dp(40)).apply{rightMargin=dp(12)})
        val brand=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        brand.addView(TrackerStyle.text(this,"Gas Receipt Tracker",20f,true,Color.WHITE))
        brand.addView(TrackerStyle.text(this,"Travel. Record. Review.",12f,false,Color.rgb(255,222,222)))
        header.addView(brand);root.addView(header)
        val scroll=ScrollView(this).apply{isFillViewport=true}
        body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(24))}
        scroll.addView(body);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));label(title,24f)
        val nav=LinearLayout(this).apply{setBackgroundColor(Color.WHITE);setPadding(dp(4),dp(5),dp(4),dp(5))}
        for((caption,dest)in listOf("Trips" to "home","Fuel" to "fuel","Docs" to "sheets","Requests" to "requests","More" to "more")){
            val selected=currentScreen==dest||(dest=="more"&&currentScreen in listOf("email","sign","backup","setup"))||(dest=="requests"&&currentScreen in listOf("finance","history","mileage"))
            val button=TrackerStyle.button(this,caption,false){show(dest)}.apply{
                textSize=12f;setPadding(0,dp(10),0,dp(10));minimumWidth=0;minWidth=0
                background=TrackerStyle.shape(this@MainActivity,if(selected)TrackerStyle.blush else Color.WHITE,null,10)
                setTextColor(if(selected)TrackerStyle.red else TrackerStyle.muted);isSelected=selected
            }
            nav.addView(button,LinearLayout.LayoutParams(0,dp(52),1f).apply{setMargins(dp(2),0,dp(2),0)})
        }
        root.addView(nav);setContentView(root);TrackerStyle.insets(this,root)
    }
    private fun requestBanner(){
        val r=workflow.current();val trips=r.getJSONArray("trips");val receipts=r.getJSONArray("receipts")
        val card=TrackerStyle.card(this,r.getString("reference")+"  /  "+r.getString("title"),
            "${r.getString("kind")}  |  ${trips.length()} trips  |  ${receipts.length()} receipts",true)
        val stages=listOf("Trip" to(trips.length()>0),"Receipt" to(receipts.length()>0),"Sign" to workflow.signed(),
            "Review" to(pref("reviewedRequestFingerprint")==workflow.fingerprint(r)))
        val row=LinearLayout(this)
        stages.forEach{(name,done)->row.addView(TrackerStyle.text(this,(if(done)"✓ " else "○ ")+name,11f,done,
            if(done)TrackerStyle.green else TrackerStyle.muted),LinearLayout.LayoutParams(0,-2,1f))}
        card.addView(row);body.addView(card)
        if(workflow.pendingEmail()!=null)action("Confirm whether the last email was sent"){offerEmailConfirmation(true)}
    }
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState);cameraUri=savedInstanceState?.getString("camera")?.let{Uri.parse(it)}
        try{
            workflow.initialize()
            if(!prefs.getBoolean("setupDone",false))show("setup")
            else if(pref("pendingSignatureAction").isNotBlank())show("sign")
            else show(savedInstanceState?.getString("screen")?:"home")
        }catch(e:Exception){draw("Records need attention");label(e.message.orEmpty());action("Backup and restore"){backupScreen()}}
    }
    private fun vehiclePicker(selected:String,draftKey:String="vehicle"):Spinner{
        label("Vehicle number",13f)
        val choices=(pref("vehicles").split(",")+selected).map{it.trim()}.filter{it.isNotBlank()}.distinct()
        val spinner=Spinner(this)
        val adapter=object:ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,
            if(choices.isEmpty())listOf("Choose a vehicle in Profile")else choices){
            override fun getView(position:Int,convertView:android.view.View?,parent:android.view.ViewGroup):android.view.View{
                return super.getView(position,convertView,parent).also{v->(v as? TextView)?.apply{
                    setTextColor(TrackerStyle.ink);textSize=16f;setPadding(dp(14),dp(14),dp(8),dp(14))}}
            }
        }
        spinner.adapter=adapter;spinner.background=TrackerStyle.shape(this,Color.WHITE,TrackerStyle.line,10)
        spinner.layoutParams=LinearLayout.LayoutParams(-1,dp(56)).apply{bottomMargin=dp(12)}
        if(selected in choices)spinner.setSelection(choices.indexOf(selected))
        spinner.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent:AdapterView<*>?){}
            override fun onItemSelected(parent:AdapterView<*>?,view:android.view.View?,position:Int,id:Long){
                val choice=spinner.selectedItem.toString()
                if(!choice.startsWith("Choose"))prefs.edit().putString(draftKey,choice).apply()
            }
        }
        body.addView(spinner);return spinner
    }
    private fun setupScreen(){
        draw("Profile & vehicles")
        label("Set up once. You can change these details later. Optional fields may be left blank.")
        val name=edit("Your name",pref("driver"));val department=edit("Department / program",pref("department"))
        val email=edit("Your email address (optional)",pref("myEmail"))
        val card=edit("Credit card last four digits (optional)",pref("cardLast4"))
        val cardBrand=edit("Default payment card type (Visa, Mastercard, etc.)",pref("cardBrand"))
        val vehicles=edit("Vehicle numbers separated by commas (e.g. 309, 310)",pref("vehicles"))
        val recipient=edit("Default recipient email (optional)",pref("emailTo"))
        val ccSelf=CheckBox(this).apply{text="CC my email on every reimbursement";isChecked=prefs.getBoolean("ccSelf",true)
            buttonTintList=android.content.res.ColorStateList.valueOf(TrackerStyle.red)};body.addView(ccSelf)
        action("Save setup and start"){
            if(name.text.isBlank()){name.error="Enter your name";return@action}
            if(card.text.isNotBlank()&&!card.text.toString().matches(Regex("[0-9]{4}"))){card.error="Enter only the last four digits";return@action}
            val numbers=vehicles.text.toString().split(",").map{it.trim()}.filter{it.isNotBlank()}.distinct()
            prefs.edit().putString("driver",name.text.toString().trim()).putString("department",department.text.toString().trim())
                .putString("myEmail",email.text.toString().trim()).putString("cardLast4",card.text.toString().trim())
                .putString("cardBrand",ReceiptPrivacy.brand(cardBrand.text.toString()))
                .putString("vehicles",numbers.joinToString(",")).putString("vehicle",numbers.firstOrNull().orEmpty())
                .putString("emailTo",recipient.text.toString().trim()).putBoolean("ccSelf",ccSelf.isChecked).putBoolean("setupDone",true).commit()
            workflow.sync();show("home")
        }
    }
    private fun show(where:String){
        try{
            if(documentBusy){alert("Please wait for the document to finish.");return}
            currentScreen=where
            when(where){"fuel"->fuel();"sheets"->sheets();"email"->emailSettings();"sign"->signatureScreen();"backup"->backupScreen()
                "setup"->setupScreen();"history"->receiptHistory();"requests"->requestList();"finance"->financePackets();"mileage"->vehicleMileage();"more"->moreScreen();else->trips()}
        }catch(e:Exception){alert(e.message.orEmpty())}
    }
    override fun onSaveInstanceState(outState:Bundle){outState.putString("screen",currentScreen);outState.putString("camera",cameraUri?.toString());super.onSaveInstanceState(outState)}
    override fun onPause(){
        super.onPause();runCatching{workflow.sync()}
        if(workflow.pendingEmail()!=null)prefs.edit().putBoolean("requestEmailLeftApp",true).commit()
    }
    override fun onResume(){super.onResume();window.decorView.post{offerEmailConfirmation()}}
    override fun onWindowFocusChanged(hasFocus:Boolean){
        super.onWindowFocusChanged(hasFocus)
        // Email apps can return focus without causing another onResume.
        // Prompt only if a pending email exists; user must explicitly confirm Send.
        if(hasFocus)window.decorView.post{offerEmailConfirmation()}
    }
    private fun moreScreen(){
        draw("Settings & tools")
        val version=runCatching{packageManager.getPackageInfo(packageName,0).versionName}.getOrNull()?:"current"
        body.addView(TrackerStyle.card(this,"Your workspace","Version "+version+"  •  Original Karuk Gas worksheet included"))
        action("Profile and vehicles"){show("setup")};action("Email templates and CC"){show("email")}
        action("Sign current request"){show("sign")};action("Backup and restore"){show("backup")}
    }
    private fun trips(){
        draw("Your travel workspace");requestBanner();action("New travel / PR"){newRequestDialog(false)}
        val driverName=pref("driver");val department=pref("department");label(driverName+"  /  "+department,14f)
        val vehicle=vehiclePicker(pref("vehicle"));val entries=data("trips")
        var active=-1
        for(i in 0 until entries.length())if(!entries.getJSONObject(i).has("endOdometer"))active=i
        if(active>=0){
            val trip=entries.getJSONObject(active)
            label("Trip in progress: "+trip.optString("destination")+"\n"+trip.optString("purpose")+"\nStarting odometer: "+trip.optString("startOdometer"))
            val end=edit("Ending odometer",pref("draftEndOdometer"),true);autoSaveField(end,"draftEndOdometer")
            action("End trip"){
                val od=end.text.toString().toDoubleOrNull()
                if(od==null||!od.isFinite()||od<trip.optDouble("startOdometer")){end.error="Enter a valid ending odometer";return@action}
                trip.put("endOdometer",end.text.toString()).put("endDate",now)
                prefs.edit().remove("draftEndOdometer").putString("lastOdometer_"+trip.optString("vehicle"),end.text.toString()).commit()
                store("trips",entries);show("home")
            }
        }else{
            label("Start a trip")
            val start=edit("Starting odometer",pref("draftStartOdometer").ifBlank{pref("lastOdometer_"+pref("vehicle"))},true)
            val destination=edit("Destination",pref("draftDestination"));val purpose=edit("Purpose of travel",pref("draftPurpose"))
            autoSaveField(start,"draftStartOdometer");autoSaveField(destination,"draftDestination");autoSaveField(purpose,"draftPurpose")
            action("Start trip"){
                if(workflow.pendingEmail()!=null){offerEmailConfirmation(true);return@action}
                if(data("receipts").length()>0){alert("Finish the fueled request, or use New travel / PR to keep the next trip separate.");return@action}
                val startValue=start.text.toString().toDoubleOrNull()
                if(startValue==null||!startValue.isFinite()||startValue<0||destination.text.isBlank()||purpose.text.isBlank()||driverName.isBlank()||vehicle.selectedItem.toString().startsWith("Choose")){
                    alert("Enter driver, vehicle, odometer, destination and purpose");return@action
                }
                prefs.edit().putString("vehicle",vehicle.selectedItem.toString()).commit()
                entries.put(JSONObject().put("driver",driverName).put("vehicle",vehicle.selectedItem.toString()).put("department",department)
                    .put("startDate",now).put("startOdometer",start.text.toString()).put("destination",destination.text.toString()).put("purpose",purpose.text.toString()))
                store("trips",entries);prefs.edit().remove("draftStartOdometer").remove("draftDestination").remove("draftPurpose").commit()
                workflow.sync();show("home")
            }
        }
        label("Current sheet - "+entries.length()+" trips",20f)
        for(i in 0 until entries.length()){
            val j=entries.getJSONObject(i)
            label((i+1).toString()+". "+j.optString("destination")+" - "+j.optString("purpose")+"\n"+j.optString("startOdometer")+" to "+j.optString("endOdometer","OPEN"))
        }
        action("Record fuel purchase"){show("fuel")};action("Preview / export vehicle usage sheet"){show("sheets")}
    }
    private fun autoSaveField(field:EditText,key:String){
        field.addTextChangedListener(object:TextWatcher{
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){prefs.edit().putString(key,s?.toString().orEmpty()).apply()}
            override fun afterTextChanged(s:Editable?){}
        })
    }
    /** The complete result, including source highlights, belongs to this PR's fuel draft. */
    private fun saveOcrResult(scan:FuelScan?){
        val editor=prefs.edit()
        if(scan==null){
            editor.putString("ocr_warn","Text not recognized; check the photo and enter fields manually.")
                .putString("ocr_evidence","[]").putString("ocr_reviewed_at","")
        }else{
            editor.putString("ocr_station",scan.station).putString("ocr_date",scan.date)
                .putString("ocr_time",scan.time).putString("ocr_fuel_type",scan.fuelType)
                .putString("ocr_gallons",scan.gallons).putString("ocr_price",scan.price)
                .putString("ocr_total",scan.total).putString("ocr_card_type",scan.cardType)
                .putString("ocr_card_last4",scan.cardLast4)
                .putString("ocr_raw",ReceiptPrivacy.redact(scan.rawText).take(35000))
                .putString("ocr_evidence",ReceiptEvidence.asJson(scan.evidence).toString())
                .putString("ocr_warn",scan.warnings.joinToString("; "))
                .remove("ocr_reviewed_at").remove("ocr_corrections")
                .remove("ocr_card_verification").remove("ocr_card_mismatch_ack")
        }
        check(editor.commit()){"Could not save the scan on this phone."}
        workflow.sync()
    }
    private fun checkReceipt(saved:JSONObject?=null){
        if(saved==null && pref("pending_receipt").isBlank()){
            alert("Scan or photograph a receipt first.");return
        }
        val intent=Intent(this,ReceiptReviewActivity::class.java)
        if(saved==null){
            intent.putExtra(ReceiptReviewActivity.EXTRA_REQUEST_ID,workflow.current().getString("id"))
        }else{
            intent.putExtra(ReceiptReviewActivity.EXTRA_HISTORY,true)
            intent.putExtra(ReceiptReviewActivity.EXTRA_RECEIPT,saved.toString())
        }
        prefs.edit().putString("receiptReviewReturn",if(saved==null)"fuel"else "history").commit()
        startActivityForResult(intent,211)
    }
    private fun fuel(){
        draw("Fuel & receipts");requestBanner()
        val receipts=data("receipts");label("Attach a receipt photograph and check the fueling information.")
        val station=edit("Gas station",pref("ocr_station"));val date=edit("Receipt date (as printed)",pref("ocr_date"))
        val vehicle=vehiclePicker(pref("draftFuelVehicle").ifBlank{pref("vehicle")},"draftFuelVehicle")
        val odometer=edit("Odometer at fueling",pref("draftOdometer"),true)
        val gallons=edit("Total gallons",pref("ocr_gallons"),true);val price=edit("Price per gallon",pref("ocr_price"),true)
        val cost=edit("Total cost",pref("ocr_total"),true)
        autoSaveField(station,"ocr_station");autoSaveField(date,"ocr_date");autoSaveField(odometer,"draftOdometer")
        autoSaveField(gallons,"ocr_gallons");autoSaveField(price,"ocr_price");autoSaveField(cost,"ocr_total")
        label("The original receipt date stays with the purchase. The PR will use today\u0027s date when generated.",14f)
        label("Entries are saved automatically as you type.",14f)
        if(pref("ocr_warn").isNotBlank())label("Check OCR: "+pref("ocr_warn"),14f)
        if(pref("ocr_card_last4").isNotBlank()||pref("ocr_card_type").isNotBlank()){
            val cardCheck=ReceiptCardVerifier.verify(pref("cardLast4"),pref("cardBrand"),
                pref("ocr_card_last4"),pref("ocr_card_type"))
            label((if(cardCheck.mismatch)"CARD MISMATCH: "else "Receipt payment: ")+cardCheck.message,14f)
        }
        label(if(pref("pending_receipt").isBlank())"No receipt image attached" else "Receipt image attached")
        label("After scanning, the receipt opens with highlighted numbers. Tap one or step through each field to correct it.",14f)
        if(pref("pending_receipt").isNotBlank())
            action("Check receipt with highlighted numbers"){checkReceipt()}
        action("Scan and crop receipt automatically"){
            val options=GmsDocumentScannerOptions.Builder().setGalleryImportAllowed(true).setPageLimit(1)
                .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG).setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL).build()
            GmsDocumentScanning.getClient(options).getStartScanIntent(this).addOnSuccessListener{sender->
                try{startIntentSenderForResult(sender,203,null,0,0,0)}catch(e:Exception){alert("Scanner could not open: "+e.message)}
            }.addOnFailureListener{alert("Scanner unavailable. Use camera or gallery.")}
        }
        action("Take receipt photo"){
            try{
                val values=ContentValues().apply{
                    put(MediaStore.Images.Media.DISPLAY_NAME,"fuel_"+System.currentTimeMillis()+".jpg");put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg")
                    if(Build.VERSION.SDK_INT>=29)put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/GasReceiptTracker")
                }
                cameraUri=contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values)
                val intent=Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT,cameraUri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                startActivityForResult(intent,201)
            }catch(e:Exception){alert("Camera unavailable: "+e.message)}
        }
        action("Choose existing receipt photo"){
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE),202)
        }
        action("Read receipt photo (OCR)"){
            val photo=pref("pending_receipt")
            if(photo.isBlank()){alert("Photograph or choose a receipt first");return@action}
            val requestId=workflow.current().getString("id")
            ReceiptReader.scan(this,Uri.parse(photo)){scan->runOnUiThread{
                if(workflow.current().getString("id")!=requestId)return@runOnUiThread
                try{saveOcrResult(scan);checkReceipt()}
                catch(e:Exception){alert("Receipt review could not open: "+e.message)}
            }}
        }
        action("Save fuel draft"){
            prefs.edit().putString("ocr_station",station.text.toString()).putString("ocr_date",date.text.toString())
                .putString("ocr_gallons",gallons.text.toString()).putString("ocr_price",price.text.toString()).putString("ocr_total",cost.text.toString())
                .putString("draftOdometer",odometer.text.toString()).commit();workflow.sync();alert("Fuel draft saved on this phone")
        }
        action("Save fuel receipt"){
            if(workflow.pendingEmail()!=null){offerEmailConfirmation(true);return@action}
            if(receipts.length()>0){alert("This request already has a receipt. Complete it or start a new travel / PR in Requests.");return@action}
            val selected=pref("pending_receipt");val od=odometer.text.toString().toDoubleOrNull();val gal=gallons.text.toString().toDoubleOrNull();val total=cost.text.toString().toDoubleOrNull()
            if(selected.isBlank()||date.text.isBlank()||vehicle.selectedItem.toString().startsWith("Choose")||od==null||!od.isFinite()||od<0||gal==null||!gal.isFinite()||gal<=0||total==null||!total.isFinite()||total<=0){
                alert("Attach receipt and enter printed receipt date, vehicle, odometer, gallons and total.");return@action
            }
            val check=ReceiptCardVerifier.verify(pref("cardLast4"),pref("cardBrand"),
                pref("ocr_card_last4"),pref("ocr_card_type"))
            if(check.mismatch&&pref("ocr_card_mismatch_ack")!="true"){
                alert("Card mismatch. Check the highlighted card number before saving.")
                checkReceipt();return@action
            }
            val actualCard=ReceiptPrivacy.last4(pref("ocr_card_last4")).ifBlank{pref("cardLast4")}
            val checkStatus=if(check.mismatch)"MISMATCH_ACKNOWLEDGED"else check.status
            receipts.put(JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("savedAt",now)
                .put("fuelType",pref("ocr_fuel_type")).put("receiptCardLast4",pref("ocr_card_last4"))
                .put("cardType",ReceiptPrivacy.brand(pref("ocr_card_type")))
                .put("cardVerificationStatus",checkStatus)
                .put("cardMismatchAcknowledged",check.mismatch&&pref("ocr_card_mismatch_ack")=="true")
                .put("profileCardLast4AtReview",pref("ocr_profile_card_last4_at_review").ifBlank{pref("cardLast4")})
                .put("profileCardBrandAtReview",pref("ocr_profile_card_brand_at_review").ifBlank{pref("cardBrand")})
                .put("reviewedAt",pref("ocr_reviewed_at"))
                .put("ocrEvidence",JSONArray(pref("ocr_evidence").ifBlank{"[]"}))
                .put("ocrCorrections",JSONArray(pref("ocr_corrections").ifBlank{"[]"}))
                .put("receiptTime",pref("ocr_time")).put("ocrRawText",pref("ocr_raw"))
                .put("ocrWarnings",pref("ocr_warn"))
                .put("station",station.text.toString()).put("date",date.text.toString()).put("vehicle",vehicle.selectedItem.toString())
                .put("odometer",odometer.text.toString()).put("driver",pref("driver")).put("cardLast4",actualCard)
                .put("gallons",gallons.text.toString()).put("price",price.text.toString()).put("cost",cost.text.toString()).put("image",selected))
            store("receipts",receipts)
            val clearing=prefs.edit().remove("pending_receipt").remove("draftOdometer")
                .putString("vehicle",vehicle.selectedItem.toString())
                .putString("lastOdometer_"+vehicle.selectedItem.toString(),odometer.text.toString())
            RequestWorkflow.DRAFT_KEYS.filter{it.startsWith("ocr_")}.forEach{clearing.remove(it)}
            check(clearing.commit()){"Could not clear saved receipt draft"}
            workflow.sync()
            TrackerDatabase(this).use { it.log(workflow.current().getString("id"), "fuel_receipt_saved",
                date.text.toString()+" / $"+cost.text.toString()+" / vehicle "+vehicle.selectedItem.toString()) }
            alert("Receipt saved for later retrieval: "+workflow.current().getString("reference"))
            show("sheets")
        }
        label("Saved receipts ("+receipts.length()+")",20f)
        for(i in receipts.length()-1 downTo 0){
            val r=receipts.getJSONObject(i)
            label(r.optString("date")+" - "+r.optString("station")+"\n"+r.optString("gallons")+" gallons, $"+r.optString("cost")+", Vehicle "+r.optString("vehicle"))
            action("Review receipt highlights "+(i+1)){checkReceipt(r)}
            action("View receipt "+(i+1)){
                try{
                    val stored=Uri.parse(r.getString("image"))
                    val viewUri=if(stored.scheme=="file"){
                        val source=java.io.File(requireNotNull(stored.path));val copy=java.io.File(filesDir,"exports/"+source.name).apply{parentFile?.mkdirs()}
                        source.copyTo(copy,true);OfficialPurchaseRequisition.uri(this,copy)
                    }else stored
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(viewUri,"image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }catch(e:Exception){alert("Receipt image unavailable: "+e.message)}
            }
        }
    }
    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?){
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==211){
            val back=pref("receiptReviewReturn").ifBlank{"fuel"}
            prefs.edit().remove("receiptReviewReturn").commit()
            show(back);return
        }
        if(requestCode==209){offerEmailConfirmation();return}
        if(requestCode==210){
            if(resultCode==RESULT_OK&&pref("reviewPendingFingerprint")==workflow.fingerprint(workflow.current()))
                prefs.edit().putString("reviewedRequestFingerprint",pref("reviewPendingFingerprint")).commit()
            val back=pref("pdfPreviewReturn").ifBlank{"sheets"}
            prefs.edit().remove("reviewPendingFingerprint").remove("pdfPreviewReturn").commit();show(back);return
        }
        if(resultCode!=RESULT_OK)return
        if(requestCode==207){
            try{TrackerBackup.restore(this,requireNotNull(data?.data));workflow.initialize();show("home");alert("Backup restored")}
            catch(e:Exception){alert("Restore failed: "+e.message)};return
        }
        if(requestCode==206){
            try{TrackerBackup.export(this,requireNotNull(data?.data));alert("Backup saved")}
            catch(e:Exception){alert("Backup failed: "+e.message)};return
        }
        if(requestCode==212){
            try{
                val path=pref("pendingFinancePdfExport")
                val file=java.io.File(path)
                require(file.isFile && file.canonicalFile.parentFile==java.io.File(filesDir,"exports").canonicalFile){"Saved packet unavailable"}
                val destination=requireNotNull(data?.data){"Choose a save location"}
                contentResolver.openOutputStream(destination,"wt").use{out->
                    requireNotNull(out){"Cannot open destination"}
                    file.inputStream().use{it.copyTo(out)}
                }
                prefs.edit().remove("pendingFinancePdfExport").remove("pendingFinancePdfName").commit()
                alert("Complete finance PDF exported")
            }catch(e:Exception){alert("Finance PDF export failed: "+e.message)}
            return
        }
        if(requestCode==208){
            val selected=data?.data?:return
            try{
                val source=java.io.File(pref("pendingExcelExport"))
                require(source.isFile&&source.parentFile?.canonicalPath==java.io.File(filesDir,"exports").canonicalPath){"Generate the Excel requisition again"}
                contentResolver.openOutputStream(selected,"wt").use{output->requireNotNull(output){"Could not open the selected save location"};source.inputStream().use{it.copyTo(output)}}
                prefs.edit().remove("pendingExcelExport").commit();alert("Original Gas Excel requisition saved")
            }catch(e:Exception){alert("Excel save failed: "+e.message)};return
        }
        val uri=when(requestCode){201->cameraUri;202->data?.data;203->GmsDocumentScanningResult.fromActivityResultIntent(data)?.pages?.firstOrNull()?.imageUri;else->null}
        if(uri!=null){
            if(requestCode==202)try{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}catch(_:Exception){}
            try{
                val requestId=workflow.current().getString("id")
                val target=java.io.File(filesDir,"receipt_"+System.currentTimeMillis()+".jpg")
                contentResolver.openInputStream(uri).use{input->requireNotNull(input){"Receipt image cannot be opened"};target.outputStream().use{input.copyTo(it)}}
                val saved=Uri.fromFile(target)
                val clearing=prefs.edit().putString("pending_receipt",saved.toString())
                RequestWorkflow.DRAFT_KEYS.filter{it.startsWith("ocr_")}.forEach{clearing.remove(it)}
                check(clearing.commit()){"Receipt draft could not be saved"}
                workflow.sync()
                ReceiptReader.scan(this,saved){scan->runOnUiThread{
                    if(workflow.current().getString("id")!=requestId)return@runOnUiThread
                    try{
                        saveOcrResult(scan)
                        checkReceipt()
                    }catch(e:Exception){alert("Receipt verification could not open: "+e.message)}
                }}
            }catch(e:Exception){alert("Could not save receipt: "+e.message)}
        }
    }
    private fun sheets(){
        draw("Documents & review");val entries=data("trips");val receipts=data("receipts")
        label("Original Karuk Gas worksheet included - no import needed.",14f)
        label("The signature is placed directly above Signature of Individual Requesting P.O.",14f);requestBanner()
        action("Review filled Gas requisition (PDF)"){continueDocument("requisition")}
        action("Save original Gas requisition as Excel (.xls)"){continueDocument("excel")}
        action("Generate and review complete reimbursement PDF"){continueDocument("packet")}
        action("Email complete reimbursement packet"){continueDocument("email")}
        action("Review usage PDF without emailing"){exportPdf(entries,receipts,true)}
        action("Create and email usage PDF"){exportPdf(entries,receipts,false)}
        label("Review the PDF here first; the separate Email button opens your email draft.",14f)
        action("Archive without sending"){
            android.app.AlertDialog.Builder(this).setTitle("Archive without sending?")
                .setMessage("Keep this request in History and move to the next request. It will not be marked sent.")
                .setNegativeButton("Keep working",null).setPositiveButton("Archive"){_,_->
                    try{workflow.archiveCurrent();show("home")}catch(e:Exception){alert(e.message.orEmpty())}
                }.show()
        }
        label("Current usage sheet",20f)
        for(i in 0 until entries.length()){
            val t=entries.getJSONObject(i)
            label((i+1).toString()+". "+t.optString("startDate")+" to "+t.optString("endDate","OPEN")+"\n"+t.optString("destination")+" - "+t.optString("purpose")+"\n"+t.optString("startOdometer")+" to "+t.optString("endOdometer","OPEN"))
        }
        val archives=data("archives");label("Archived sheets: "+archives.length())
        for(i in archives.length()-1 downTo 0){
            val archive=archives.optJSONObject(i)?:continue
            label("Archived "+archive.optString("archivedAt")+" - "+archive.optJSONArray("trips")?.length()+" trips")
            action("Export archived sheet "+(i+1)){exportPdf(archive.optJSONArray("trips")?:JSONArray(),archive.optJSONArray("receipts")?:JSONArray(),true)}
        }
    }
    private fun runDocumentWork(work:()->Unit){
        if(documentBusy)return;documentBusy=true
        val progress=android.app.ProgressDialog.show(this,"Preparing documents","Filling the original worksheet...",true,false)
        Thread{
            try{work()}catch(e:Exception){runOnUiThread{alert("Document generation failed: "+e.message)}}
            finally{runOnUiThread{documentBusy=false;if(!isFinishing)progress.dismiss()}}
        }.start()
    }
    private fun previewPdf(uri:Uri,track:Boolean=false){
        if(track)prefs.edit().putString("reviewPendingFingerprint",workflow.fingerprint(workflow.current())).commit()
        prefs.edit().putString("pdfPreviewReturn",currentScreen).commit()
        startActivityForResult(Intent(this,PdfPreviewActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),210)
    }
    private fun exportPdf(entries:JSONArray,receipts:JSONArray,previewOnly:Boolean=false){
        if(entries.length()==0&&receipts.length()==0){alert("No records to export");return}
        val doc=PdfDocument();val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=12f};var pageNo=0
        try{
            for(i in 0 until receipts.length()){
                val r=receipts.getJSONObject(i);val imageUri=Uri.parse(r.getString("image"))
                val options=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                if(imageUri.scheme=="file")BitmapFactory.decodeFile(imageUri.path,options)
                else contentResolver.openInputStream(imageUri).use{BitmapFactory.decodeStream(it,null,options)}
                var sample=1;while(options.outWidth/sample>1800||options.outHeight/sample>2400)sample*=2
                options.inJustDecodeBounds=false;options.inSampleSize=sample
                val bitmap=if(imageUri.scheme=="file")BitmapFactory.decodeFile(imageUri.path,options)
                    else contentResolver.openInputStream(imageUri).use{BitmapFactory.decodeStream(it,null,options)}
                requireNotNull(bitmap){"A receipt image is unavailable; restore or replace it before export."}
                val page=doc.startPage(PdfDocument.PageInfo.Builder(612,792,++pageNo).create())
                page.canvas.drawText("ORIGINAL RECEIPT IMAGE "+(i+1)+" - "+r.optString("date"),35f,36f,paint)
                val scale=minOf(540f/bitmap.width,715f/bitmap.height)
                page.canvas.drawBitmap(bitmap,null,android.graphics.RectF(35f,55f,35f+bitmap.width*scale,55f+bitmap.height*scale),paint)
                doc.finishPage(page);bitmap.recycle()
            }
            VehicleUsagePdf.append(doc,entries,receipts,pageNo+1)
            val file=java.io.File(filesDir,"exports/Vehicle_Usage_"+System.currentTimeMillis()+".pdf").apply{parentFile?.mkdirs()}
            file.outputStream().use{doc.writeTo(it)}
            val uri=OfficialPurchaseRequisition.uri(this,file)
            if(previewOnly)previewPdf(uri)else sendPacket(uri,receipts)
        }catch(e:Exception){alert("PDF export failed: "+e.message)}finally{doc.close()}
    }
    private fun backupScreen(){
        draw("Backup and restore")
        label("Save your requests, trips, receipt images, signatures, documents and email settings in one ZIP backup.")
        action("Save complete backup"){
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"GasReceiptTracker_Backup.zip"),206)
        }
        action("Restore saved backup"){
            android.app.AlertDialog.Builder(this).setTitle("Restore backup?")
                .setMessage("This replaces current saved trip and receipt records. Export a backup first to keep them.")
                .setNegativeButton("Cancel",null).setPositiveButton("Continue"){_,_->
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),207)
                }.show()
        }
    }
    private fun signatureScreen(){
        draw("Sign this request");requestBanner();val resume=pref("pendingSignatureAction")
        label(if(resume.isNotBlank())"Your signature is needed. Save it here and we will take you back to your document."
            else "Sign with your finger or stylus. Each new PR gets its own signature.")
        body.addView(TrackerStyle.card(this,"Original purchase requisition","Your signature goes directly above 'Signature of Individual Requesting P.O.'"))
        val pad=SignaturePad(this);body.addView(pad);action("Clear signature"){pad.clear()}
        action(if(resume.isBlank())"Save signature"else "Save signature and continue"){
            if(!pad.hasSignature()){alert("Please sign first");return@action}
            try{
                val requestId=workflow.current().getString("id")
                require(pref("pendingSignatureRequest").isBlank()||pref("pendingSignatureRequest")==requestId){"The active request changed. Return to Documents."}
                workflow.saveSignature(pad.save(this));prefs.edit().remove("pendingSignatureAction").remove("pendingSignatureRequest").commit()
                if(resume.isNotBlank())continueDocument(resume)else show("sheets")
            }catch(e:Exception){alert("Could not save signature: "+e.message)}
        }
        if(resume.isNotBlank())action("Cancel and return to documents"){
            prefs.edit().remove("pendingSignatureAction").remove("pendingSignatureRequest").commit();show("sheets")
        }
    }
    private fun continueDocument(operation:String){
        val receipts=data("receipts")
        if(receipts.length()!=1){alert("Save one fuel receipt for this request first.");show("fuel");return}
        if(!workflow.signed()){
            prefs.edit().putString("pendingSignatureAction",operation).putString("pendingSignatureRequest",workflow.current().getString("id")).commit()
            show("sign");return
        }
        if(operation=="email"&&workflow.pendingEmail()!=null){offerEmailConfirmation(true);return}
        val entries=data("trips");val signature=java.io.File(pref("signatureFile"));val driver=pref("driver");val card=pref("cardLast4");val req=workflow.current()
        runDocumentWork{
            when(operation){
                "requisition","excel"->{
                    val prepared=OfficialPurchaseRequisition.prepare(this,receipts.getJSONObject(0),driver,card,signature)
                    TrackerDatabase(this).use {
                        it.recordDocument(req.getString("id"), "gas_excel", OfficialPurchaseRequisition.uri(this,prepared.excel).toString())
                        it.recordDocument(req.getString("id"), "gas_pdf", OfficialPurchaseRequisition.uri(this,prepared.pdf).toString())
                    }
                    if(operation=="excel"){
                        prefs.edit().putString("pendingExcelExport",prepared.excel.absolutePath).commit()
                        runOnUiThread{startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/vnd.ms-excel").addCategory(Intent.CATEGORY_OPENABLE)
                            .putExtra(Intent.EXTRA_TITLE,req.getString("reference")+"_Karuk_Gas.xls"),208)}
                    }else runOnUiThread{previewPdf(OfficialPurchaseRequisition.uri(this,prepared.pdf),true)}
                }
                "packet","email"->{
                    val uri=ReimbursementPacket.create(this,entries,receipts,driver,card,signature.absolutePath)
                    TrackerDatabase(this).use { it.recordDocument(req.getString("id"), "reimbursement_pdf", uri.toString()) }
                    runOnUiThread{if(operation=="email")prepareEmail(uri,receipts)else previewPdf(uri,true)}
                }
            }
        }
    }
    private fun renderTemplate(template:String,receipt:JSONObject,request:JSONObject?=null):String{
        val req=request?:workflow.current()
        val values=mapOf("{prNumber}" to req.optString("reference"),"{requestTitle}" to req.optString("title"),"{requestType}" to req.optString("kind"),
            "{date}" to receipt.optString("date"),"{vehicle}" to receipt.optString("vehicle",pref("vehicle")),"{driver}" to pref("driver"),
            "{program}" to pref("department"),"{station}" to receipt.optString("station"),"{amount}" to receipt.optString("cost"),
            "{gallons}" to receipt.optString("gallons"),"{pricePerGallon}" to receipt.optString("price"),"{odometer}" to receipt.optString("odometer"),"{cardLast4}" to pref("cardLast4"))
        var result=template.replace("\\n","\n").replace("\\u0024","$")
        for((key,value)in values)result=result.replace(key,value)
        return result
    }
    private fun emailSettings(){
        draw("Email templates")
        label("Save reusable recipients, subject and message. You can also edit them for each email before sending.")
        val recipient=edit("Recipient email",pref("emailTo"));val myEmail=edit("My email (for CC)",pref("myEmail"))
        val ccOthers=edit("Additional CC emails, separated by commas",pref("emailCc"))
        val ccSelf=CheckBox(this).apply{text="CC myself on every email";isChecked=prefs.getBoolean("ccSelf",true)};body.addView(ccSelf)
        val card=edit("Credit card last 4 digits",pref("cardLast4"))
        val subject=edit("Subject template",prefs.getString("emailSubject","Gas Receipt {date} - Pay Credit Card {cardLast4}").orEmpty())
        label("Email body template (editable):")
        val message=EditText(this).apply{minLines=5;setSingleLine(false)
            setText(prefs.getString("emailBody","Please find attached the fuel reimbursement packet for vehicle {vehicle}.\nAmount: \u0024{amount}\nGallons: {gallons}\nStation: {station}\nDate: {date}\nDriver: {driver}\nProgram: {program}"))}
        TrackerStyle.input(this,message);body.addView(message)
        label("Available fields: {date}, {vehicle}, {driver}, {program}, {station}, {amount}, {gallons}, {pricePerGallon}, {odometer}, {cardLast4}, {prNumber}, {requestTitle}, {requestType}",14f)
        action("Save email template"){
            prefs.edit().putString("emailTo",recipient.text.toString().trim()).putString("cardLast4",card.text.toString().trim()).putString("myEmail",myEmail.text.toString().trim())
                .putString("emailCc",ccOthers.text.toString().trim()).putBoolean("ccSelf",ccSelf.isChecked).putString("emailSubject",subject.text.toString()).putString("emailBody",message.text.toString()).commit()
            workflow.sync();alert("Email settings saved")
        }
        action("Preview with latest fuel receipt"){
            val receipts=data("receipts");if(receipts.length()==0){alert("Save a receipt first");return@action}
            val receipt=receipts.getJSONObject(receipts.length()-1)
            label("Subject: "+renderTemplate(subject.text.toString(),receipt)+"\n\n"+renderTemplate(message.text.toString(),receipt),15f)
        }
    }
    private fun sendPacket(uri:Uri,receipts:JSONArray){prepareEmail(uri,receipts,false)}
    private fun prepareEmail(uri:Uri,receipts:JSONArray,completeRequest:Boolean=true,archive:JSONObject?=null){
        val receipt=receipts.optJSONObject(0)?:JSONObject()
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(8),dp(18),dp(8))}
        fun input(title:String,value:String,multi:Boolean=false):EditText{
            form.addView(TrackerStyle.text(this,title,13f,true))
            val e=EditText(this).apply{hint=title;setText(value);setSingleLine(!multi);if(multi)minLines=4}
            TrackerStyle.input(this,e);form.addView(e);return e
        }
        val to=input("To",pref("emailTo"));val cc=input("Additional CC",pref("emailCc"))
        val self=CheckBox(this).apply{text="CC myself"+(if(pref("myEmail").isNotBlank())" ("+pref("myEmail")+")"else " (add your email in Profile)")
            isChecked=prefs.getBoolean("ccSelf",true)&&pref("myEmail").isNotBlank()};form.addView(self)
        val subject=input("Subject",renderTemplate(prefs.getString("emailSubject","Gas Receipt {date} - Pay Credit Card {cardLast4}").orEmpty(),receipt,archive))
        val message=input("Message",renderTemplate(prefs.getString("emailBody","Attached: {prNumber}, vehicle {vehicle}. Amount: \u0024{amount}. Gallons: {gallons}.").orEmpty(),receipt,archive),true)
        form.addView(TrackerStyle.text(this,"PDF attached. Nothing is sent until you press Send in your email app.",13f,false,TrackerStyle.muted))
        val scroll=ScrollView(this);scroll.addView(form)
        val dialog=android.app.AlertDialog.Builder(this).setTitle("Review email & recipients").setView(scroll).setNegativeButton("Not now",null).setPositiveButton("Open email",null).create()
        dialog.setOnShowListener{dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            val recipients=to.text.toString().split(",",";").map{it.trim()}.filter{it.isNotBlank()}
            val ccs=(cc.text.toString().split(",",";")+if(self.isChecked)listOf(pref("myEmail"))else emptyList()).map{it.trim()}.filter{it.isNotBlank()}.distinctBy{it.lowercase(Locale.US)}
            if(recipients.isEmpty()){to.error="Enter an email recipient";return@setOnClickListener}
            if((recipients+ccs).any{!android.util.Patterns.EMAIL_ADDRESS.matcher(it).matches()}){to.error="Check the To and CC email addresses";return@setOnClickListener}
            try{
                val email=Intent(Intent.ACTION_SEND).apply{
                    type="application/pdf";putExtra(Intent.EXTRA_EMAIL,recipients.toTypedArray());putExtra(Intent.EXTRA_CC,ccs.toTypedArray())
                    putExtra(Intent.EXTRA_SUBJECT,subject.text.toString());putExtra(Intent.EXTRA_TEXT,message.text.toString());putExtra(Intent.EXTRA_STREAM,uri)
                    clipData=android.content.ClipData.newUri(contentResolver,"Reimbursement PDF",uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if(completeRequest)workflow.stageEmail(uri.toString(),subject.text.toString(),recipients.joinToString(","),ccs.joinToString(","))
                startActivityForResult(Intent.createChooser(email,"Choose your email app"),209);dialog.dismiss()
            }catch(e:Exception){if(completeRequest)workflow.cancelEmail();alert("Email could not open: "+e.message)}
        }};dialog.show()
    }
    private fun offerEmailConfirmation(force:Boolean=false){
        if(isFinishing||isDestroyed||showingEmailConfirmation)return
        val pending=workflow.pendingEmail()?:return
        if(!force&&!prefs.getBoolean("requestEmailLeftApp",false))return
        showingEmailConfirmation=true
        val dialog=android.app.AlertDialog.Builder(this).setTitle("Was "+pending.optString("reference")+" sent?")
            .setMessage("Your email app does not report whether you pressed Send. Confirm it was sent to keep a copy in History and automatically open the next request. A canceled draft stays open.")
            .setPositiveButton("Sent - next request"){_,_->
                try{val next=workflow.confirmSent(pending.getString("token"));show("home");alert("Saved in History. Now working on "+next.getString("reference"))}catch(e:Exception){alert(e.message.orEmpty())}
            }.setNegativeButton("Not sent"){_,_->workflow.cancelEmail();show("sheets")}
            .setNeutralButton("Later"){_,_->prefs.edit().putBoolean("requestEmailLeftApp",false).commit()}.create()
        dialog.setOnDismissListener{showingEmailConfirmation=false};dialog.show()
    }
    private fun newRequestDialog(queue:Boolean){
        val form=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(10),dp(20),0)}
        val title=EditText(this).apply{hint="Destination or work order title"};TrackerStyle.input(this,title);form.addView(title)
        val kind=Spinner(this).apply{adapter=ArrayAdapter(this@MainActivity,android.R.layout.simple_spinner_dropdown_item,listOf("Travel","Work order"))};form.addView(kind)
        form.addView(TrackerStyle.text(this,"An internal PR reference is created automatically. Existing requests and receipts are kept.",13f,false,TrackerStyle.muted))
        val dialog=android.app.AlertDialog.Builder(this).setTitle(if(queue)"Queue next request"else "New travel / PR")
            .setView(form).setNegativeButton("Cancel",null).setPositiveButton(if(queue)"Add to queue"else "Start new",null).create()
        dialog.setOnShowListener{dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener{
            try{workflow.create(title.text.toString(),kind.selectedItem.toString(),!queue);dialog.dismiss();show(if(queue)"requests"else "home")}catch(e:Exception){title.error=e.message}
        }};dialog.show()
    }
    private fun receiptHistory(){
        draw("Saved fuel receipts")
        label("Choose a recorded date, amount, vehicle, or PR number. Date ranges and nearby amounts update instantly.",14f)
        val saved=TrackerDatabase(this).use{it.receiptSearch("",5000)}
        val states=mutableMapOf<String,String>()
        val orders=workflow.all()
        for(i in 0 until orders.length()){
            val request=orders.getJSONObject(i)
            states[request.optString("id")]=when(request.optString("status")){
                "sent"->"Sent"
                "archived"->"Archived"
                else->"Draft"
            }
        }
        val items=saved.mapIndexed{i,r->
            ArchiveSearchRow(
                id=i.toString(),date=r.date,amount=GuidedArchiveSearch.parseAmount(r.total)?:0.0,
                vehicle=r.vehicle,reference=r.reference,status=states[r.requestId]?:"Draft",
                title=r.requestTitle
            )
        }
        val lookup=saved.indices.associate{i->i.toString() to saved[i]}
        val results=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        GuidedArchiveSearchPanel(this,body,items){found->
            results.removeAllViews()
            results.addView(TrackerStyle.text(this,
                "${found.size} matching saved receipt(s)",15f,true,TrackerStyle.ink))
            if(found.isEmpty())results.addView(TrackerStyle.text(this,
                "There are no recorded purchases matching those selections.",14f,false,TrackerStyle.muted))
            for(row in found.take(60)){
                val r=lookup[row.id]?:continue
                results.addView(TrackerStyle.card(this,
                    (r.station.ifBlank{"Fuel receipt"})+"  /  "+GuidedArchiveSearch.money(row.amount),
                    r.date+" • Vehicle "+r.vehicle+" • "+r.gallons+" gal\n"+
                        r.reference+" • "+row.status))
                results.addView(TrackerStyle.button(this,"Review saved receipt"){
                    checkReceipt(r.payload)
                })
                results.addView(TrackerStyle.button(this,"View transaction details"){
                    android.app.AlertDialog.Builder(this).setTitle(r.station+" — "+r.date)
                        .setMessage("Request: "+r.reference+"\nVehicle: "+r.vehicle+
                            "\nGallons: "+r.gallons+"\nPrice per gallon: $"+r.price+
                            "\nTotal: $"+r.total+"\nStatus: "+row.status+
                            "\nThe original receipt image and corrected values remain saved.")
                        .setPositiveButton("Close",null)
                        .setNeutralButton("Read saved OCR text"){_,_->
                            android.app.AlertDialog.Builder(this).setTitle("Saved OCR text")
                                .setMessage(r.rawText.ifBlank{"No OCR text was saved for this receipt."})
                                .setPositiveButton("Close",null).show()
                        }.show()
                })
            }
            if(found.size>60)results.addView(TrackerStyle.text(this,
                "Showing the first 60 records. Narrow the date, amount, or vehicle to see the others.",13f,false,TrackerStyle.muted))
        }
        body.addView(results)
    }
    private fun financePackets(){
        draw("Finance packet archive")
        label("Find confirmed-sent packets by an exact date, a date range, a nearby amount, a vehicle or PR number. Suggestions come from your saved transactions.",14f)
        val packetList=FinancePacketArchive.entries(this)
        val rows=packetList.mapIndexed{i,p->
            ArchiveSearchRow(i.toString(),p.purchaseDate,p.amount,p.vehicle,
                p.reference,"Sent",p.title)
        }
        val packets=packetList.indices.associate{i->i.toString() to packetList[i]}
        val results=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        GuidedArchiveSearchPanel(this,body,rows){found->
            results.removeAllViews()
            results.addView(TrackerStyle.text(this,
                "${found.size} matching complete Finance packet(s)",15f,true,TrackerStyle.ink))
            if(found.isEmpty())results.addView(TrackerStyle.text(this,
                "No saved Finance packets match that date or amount. Try widening the range.",14f,false,TrackerStyle.muted))
            for(row in found.take(45)){
                val packet=packets[row.id]?:continue
                results.addView(TrackerStyle.card(this,
                    packet.purchaseDate+"  "+GuidedArchiveSearch.money(packet.amount),
                    packet.reference+" • Vehicle "+packet.vehicle+"\n"+
                        packet.title+"\nSent to Finance: "+packet.sentDate))
                val file=FinancePacketArchive.savedFile(this,packet)
                if(file==null){
                    results.addView(TrackerStyle.text(this,
                        "The archived PDF is missing on this phone. Restore a complete app backup to recover it.",13f,false,TrackerStyle.red))
                    continue
                }
                val uri=OfficialPurchaseRequisition.uri(this,file)
                results.addView(TrackerStyle.button(this,"Open complete PDF packet"){
                    previewPdf(uri)
                })
                results.addView(TrackerStyle.button(this,"Email saved packet again"){
                    prepareEmail(uri,JSONArray().put(packet.receipt),false,
                        JSONObject().put("reference",packet.reference).put("title",packet.title).put("kind","Gas"))
                })
                results.addView(TrackerStyle.button(this,"Download / export PDF"){
                    val name=packet.reference+"_Finance_"+
                        packet.purchaseDate.replace(Regex("[^0-9A-Za-z]"),"_")+".pdf"
                    prefs.edit().putString("pendingFinancePdfExport",file.absolutePath)
                        .putString("pendingFinancePdfName",name).commit()
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .setType("application/pdf").addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_TITLE,name),212)
                })
            }
            if(found.size>45)results.addView(TrackerStyle.text(this,
                "Showing the first 45 packets. Choose a more specific recorded date or amount to see the others.",13f,false,TrackerStyle.muted))
        }
        body.addView(results)
    }
    private fun vehicleMileage(){
        draw("Vehicle fuel economy")
        label("MPG = odometer miles since previous fueling ÷ gallons purchased now.",14f)
        val fills=VehicleFuelAnalytics.fills(workflow.all())
        val vehicles=fills.map{it.vehicle}.distinct().sorted()
        if(vehicles.isEmpty()){
            label("Record a fueling odometer and gallons to begin vehicle MPG history.",14f)
            return
        }
        val picker=Spinner(this)
        picker.adapter=ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,vehicles)
        body.addView(picker)
        val results=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL}
        body.addView(results)
        fun refresh(){
            results.removeAllViews()
            val vehicle=picker.selectedItem?.toString()?:return
            val series=VehicleFuelAnalytics.points(fills,vehicle)
            results.addView(TrackerStyle.text(this,VehicleFuelAnalytics.summary(series),17f,true,TrackerStyle.ink))
            if(series.isNotEmpty()){
                results.addView(MileageGraph(this,series))
                for(point in series.asReversed()){
                    val info=String.format(Locale.US,
                        "%s • %.1f MPG • %.0f miles / %.3f gallons\nOdometer %.0f",
                        point.date,point.mpg,point.miles,point.gallons,point.odometer)
                    results.addView(TrackerStyle.card(this,
                        if(point.warning)"Possible maintenance check"else "Fueling interval",
                        info+(if(point.warning)"\nMPG fell more than 20% below the previous three intervals."else ""),
                        point.warning))
                }
            }
            results.addView(TrackerStyle.text(this,
                "Estimates assume comparable full-tank refills. Partial fills, missed fuelings, " +
                    "idling, towing, weather or driving changes can distort MPG. " +
                    "A warning suggests inspection, not a confirmed mechanical fault.",13f,false,TrackerStyle.muted))
        }
        picker.onItemSelectedListener=object:android.widget.AdapterView.OnItemSelectedListener{
            override fun onItemSelected(parent:android.widget.AdapterView<*>?,view:android.view.View?,position:Int,id:Long){refresh()}
            override fun onNothingSelected(parent:android.widget.AdapterView<*>?){}
        }
        refresh()
    }
    private fun requestList(){
        draw("Requests & history")
        if(workflow.pendingEmail()!=null)action("Confirm whether the last email was sent"){offerEmailConfirmation(true)}
        action("New travel / PR"){newRequestDialog(false)};action("Queue travel or work order"){newRequestDialog(true)}
        action("Find complete packets sent to Finance"){show("finance")}
        action("Vehicle MPG trends and maintenance flags"){show("mileage")}
        action("Search saved fuel receipts"){show("history")}
        val all=workflow.all();val activeId=workflow.current().getString("id");label("Open requests",20f)
        for(i in 0 until all.length()){
            val r=all.getJSONObject(i);if(r.optString("status")!="draft")continue
            body.addView(TrackerStyle.card(this,r.getString("reference")+"  /  "+r.getString("title"),
                (if(r.getString("id")==activeId)"Current"else "Queued")+" • "+r.optString("kind")+" • "+r.getJSONArray("trips").length()+" trips",r.getString("id")==activeId))
            action("Open "+r.getString("reference")){workflow.select(r.getString("id"));show("home")}
        }
        label("Completed requests",20f);var completed=0
        for(i in all.length()-1 downTo 0){
            val r=all.getJSONObject(i);if(r.optString("status")=="draft")continue;completed++
            body.addView(TrackerStyle.card(this,r.getString("reference")+"  /  "+r.getString("title"),
                if(r.optString("status")=="sent")"Sent - confirmed by you • "+r.optString("sentAt")else "Archived, not marked sent"))
            val saved=r.optString("sentPdfUri");if(saved.isNotBlank())action("Review saved "+r.getString("reference")){previewPdf(Uri.parse(saved))}
        }
        if(completed==0)label("Sent and archived requests will appear here. No requests are deleted when you move on.",14f)
        label("PR numbers are local tracking references, not Finance approval or official P.O. numbers.",12f)
    }
}