package org.karuk.gasreceipttracker

import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executors

/** Offline, in-app review. It never opens email or transmits a document. */
class PdfPreviewActivity : Activity() {
    private val worker=Executors.newSingleThreadExecutor()
    private var renderer:PdfRenderer?=null
    private lateinit var image:ImageView
    private lateinit var status:TextView
    private var bitmap:Bitmap?=null
    private var index=0
    private var zoom=1f
    private var furthestPage=-1
    private var requireAllPages=false
    private var formStage=-1
    @Volatile private var closing=false
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        val margin=(12*resources.displayMetrics.density).toInt()
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(margin,margin,margin,margin)}
        ViewCompat.setOnApplyWindowInsetsListener(root){view,insets->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(margin+bars.left,margin+bars.top,margin+bars.right,margin+bars.bottom)
            insets
        }
        requireAllPages=intent.getBooleanExtra("requireAllPages",false)
        formStage=intent.getIntExtra("formStage",-1)
        val title=TrackerStyle.text(this,"Review PDF - not sent",20f,true,TrackerStyle.red);root.addView(title)
        status=TextView(this).apply{text="Opening document..."};root.addView(status)
        root.setBackgroundColor(TrackerStyle.background)
        val controls=LinearLayout(this)
        fun button(name:String,action:()->Unit){controls.addView(TrackerStyle.button(this,name,false,action).apply{textSize=12f;setPadding(4,4,4,4)},LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(2,4,2,4)})}
        button(if(formStage>=0) "Exit" else "Done"){
            val count=renderer?.pageCount?:0
            if(requireAllPages&&(count==0||furthestPage<count-1)){
                Toast.makeText(this,"Review all pages before marking this document complete.",Toast.LENGTH_LONG).show()
            }else{setResult(RESULT_OK);finish()}
        };button("Previous"){if(index>0){index--;render()}}
        button("Next"){val n=renderer?.pageCount?:0;if(index+1<n){index++;render()}else if(formStage>=0){
            if(furthestPage<n-1||n==0)Toast.makeText(this,"Review all pages before continuing.",Toast.LENGTH_LONG).show()
            else{setResult(RESULT_OK,android.content.Intent().putExtra("formDirection",1));finish()}
        }}

        root.addView(controls)
        if(formStage>0){
            val previous=TrackerStyle.button(this,"Previous form",false,{
                setResult(RESULT_OK,android.content.Intent().putExtra("formDirection",-1));finish()
            })
            root.addView(previous)
        }
        if(formStage>=0)root.addView(TrackerStyle.text(this,"Form ${formStage+1} of 3. Next moves to the next form after all pages are viewed; on the last form, Next opens the reimbursement email.",13f,false,TrackerStyle.ink))
        val zoomRow=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL}
        val zoomText=TrackerStyle.text(this,"Zoom 100%",12f,true,TrackerStyle.ink)
        zoomRow.addView(zoomText)
        val zoomSlider=SeekBar(this).apply{
            max=300
            progress=0
            contentDescription="PDF zoom slider, 100 to 400 percent"
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
                override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean){
                    if(fromUser)zoomText.text="Zoom ${100+progress}%"
                }
                override fun onStartTrackingTouch(bar:SeekBar?){}
                override fun onStopTrackingTouch(bar:SeekBar?){
                    zoom=1f+(bar?.progress?:0)/100f
                    render()
                }
            })
        }
        zoomRow.addView(zoomSlider,LinearLayout.LayoutParams(0,(44*resources.displayMetrics.density).toInt(),1f))
        root.addView(zoomRow)
        image=ImageView(this).apply{setBackgroundColor(Color.WHITE);adjustViewBounds=true}
        val vertical=ScrollView(this);val horizontal=HorizontalScrollView(this)
        horizontal.addView(image);vertical.addView(horizontal);root.addView(vertical,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root);TrackerStyle.systemBars(this,root);ViewCompat.requestApplyInsets(root)
        val uri=intent.data
        if(uri==null){status.text="No PDF was selected";return}
        worker.execute{
            try{
                val fd=contentResolver.openFileDescriptor(uri,"r")?:error("Cannot open PDF")
                renderer=try{PdfRenderer(fd)}catch(e:Exception){fd.close();throw e}
                runOnUiThread{if(!closing)render()}
            }catch(e:Exception){runOnUiThread{if(!closing)status.text="Preview could not open: "+e.message}}
        }
    }
    private fun render(){
        if(closing)return
        val selected=index;val requestedZoom=zoom
        worker.execute{
            if(closing)return@execute
            try{
                val pdf=renderer?:return@execute
                val new=pdf.openPage(selected).use { page ->
                    val desired=(resources.displayMetrics.widthPixels*requestedZoom).coerceIn(400f,4000f)
                    val factor=minOf(desired/page.width,6000f/page.height)
                    val width=(page.width*factor).toInt().coerceAtLeast(1)
                    val height=(page.height*factor).toInt().coerceAtLeast(1)
                    Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888).also{it.eraseColor(Color.WHITE);page.render(it,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)}
                }
                runOnUiThread{
                    if(closing){new.recycle();return@runOnUiThread}
                    val old=bitmap;bitmap=new;image.setImageBitmap(new);old?.recycle()
                    furthestPage=maxOf(furthestPage,selected)
                    status.text="Page ${selected+1} of ${pdf.pageCount} - review before emailing"
                }
            }catch(e:Exception){runOnUiThread{if(!closing)status.text="Preview failed: "+e.message}}
        }
    }
    override fun onDestroy(){
        closing=true
        worker.execute{renderer?.close();renderer=null};worker.shutdown()
        if(::image.isInitialized)image.setImageDrawable(null)
        bitmap?.recycle();super.onDestroy()
    }
}
