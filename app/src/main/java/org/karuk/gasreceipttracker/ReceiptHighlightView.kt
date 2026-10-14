package org.karuk.gasreceipttracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/** The receipt photograph remains unaltered. Highlights are a separate,
 * interactive drawing layer created from actual ML Kit word coordinates. */
class ReceiptHighlightView(context:Context,private val bitmap:Bitmap,
    private val marks:List<ReceiptEvidence>,private val onSelect:(String)->Unit):View(context) {

    var activeField:String="gallons"
        set(value){field=value;invalidate()}
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val density=resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        val w=MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
        val h=(w.toFloat()*bitmap.height/bitmap.width).roundToInt().coerceAtLeast(1)
        setMeasuredDimension(w,h)
    }

    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        paint.style=Paint.Style.FILL;paint.color=Color.WHITE
        canvas.drawRect(0f,0f,width.toFloat(),height.toFloat(),paint)
        paint.color=Color.WHITE
        canvas.drawBitmap(bitmap,null,RectF(0f,0f,width.toFloat(),height.toFloat()),paint)
        val active=marks.filter{it.field==activeField}
        for(mark in marks.filter{it.field!=activeField}+active){
            val rectangle=toScreen(mark)
            val focused=mark.field==activeField
            paint.style=Paint.Style.FILL
            paint.color=if(focused)Color.argb(80,220,32,40)else Color.argb(62,252,190,55)
            canvas.drawRoundRect(rectangle,4*density,4*density,paint)
            paint.style=Paint.Style.STROKE
            paint.strokeWidth=if(focused)3*density else 1.5f*density
            paint.color=if(focused)Color.rgb(161,18,26) else Color.rgb(168,109,0)
            canvas.drawRoundRect(rectangle,4*density,4*density,paint)
            paint.style=Paint.Style.FILL
        }
        val selected=active.firstOrNull()
        if(selected!=null){
            val r=toScreen(selected)
            val label=activeField.replaceFirstChar{it.uppercaseChar()}
            paint.typeface=android.graphics.Typeface.DEFAULT_BOLD
            paint.textSize=12*density
            val tWidth=paint.measureText(label)
            val chipLeft=r.left.coerceIn(0f,(width-tWidth-16*density).coerceAtLeast(0f))
            val chipTop=(r.top-26*density).coerceAtLeast(0f)
            val chip=RectF(chipLeft,chipTop,chipLeft+tWidth+15*density,chipTop+22*density)
            paint.color=Color.rgb(161,18,26)
            canvas.drawRoundRect(chip,5*density,5*density,paint)
            paint.color=Color.WHITE
            canvas.drawText(label,chip.left+7*density,chip.bottom-6*density,paint)
        }
    }
    private fun toScreen(e:ReceiptEvidence)=RectF(
        e.left*width,e.top*height,e.right*width,e.bottom*height)
    fun selectedRect():RectF?=marks.firstOrNull{it.field==activeField}?.let{toScreen(it)}
    fun hasHighlight(field:String)=marks.any{it.field==field}
    override fun onTouchEvent(event:MotionEvent):Boolean {
        if(event.actionMasked!=MotionEvent.ACTION_UP)return true
        val x=event.x/width.coerceAtLeast(1)
        val y=event.y/height.coerceAtLeast(1)
        val exact=marks.filter{it.left-.02f<=x && it.right+.02f>=x
            && it.top-.012f<=y && it.bottom+.012f>=y}
            .minByOrNull{(it.right-it.left)*(it.bottom-it.top)}
        if(exact!=null){onSelect(exact.field);performClick()}
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
