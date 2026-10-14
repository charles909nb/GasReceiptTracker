package org.karuk.gasreceipttracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale

/** Small native line chart. Data is derived from completed full-to-full
 * fill cycles; red markers are possible maintenance checks, not diagnoses.
 */
class VehicleMpgChart(context:Context):View(context) {
    var samples:List<MileageAnalytics.Sample> = emptyList()
        set(value){field=value.takeLast(16);invalidate();requestLayout()}
    private val d=resources.displayMetrics.density
    private val ink=Paint(Paint.ANTI_ALIAS_FLAG).apply{textSize=12*d;color=TrackerStyle.ink}
    private val line=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=TrackerStyle.green;strokeWidth=2.8f*d;style=Paint.Style.STROKE
        strokeJoin=Paint.Join.ROUND;strokeCap=Paint.Cap.ROUND
    }
    private val guide=Paint(Paint.ANTI_ALIAS_FLAG).apply{
        color=Color.rgb(222,225,232);strokeWidth=1f*d;style=Paint.Style.STROKE
    }
    private val dot=Paint(Paint.ANTI_ALIAS_FLAG).apply{style=Paint.Style.FILL}
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            (255*d).toInt())
    }
    override fun onDraw(canvas:Canvas){
        super.onDraw(canvas)
        canvas.drawColor(Color.WHITE)
        val left=52*d;val right=width-18*d
        val top=18*d;val bottom=height-46*d
        if(samples.isEmpty()){
            ink.textSize=14*d;ink.color=TrackerStyle.muted
            canvas.drawText("No verified full-to-full MPG points yet",left,bottom/2,ink)
            return
        }
        val maxY=(samples.maxOf{it.mpg}*1.2).coerceAtLeast(10.0)
        val ticks=4
        ink.textSize=11*d
        for(k in 0..ticks){
            val y=bottom-(bottom-top)*k/ticks
            canvas.drawLine(left,y,right,y,guide)
            ink.color=TrackerStyle.muted
            canvas.drawText(String.format(Locale.US,"%.0f",maxY*k/ticks),
                7*d,y+4*d,ink)
        }
        ink.color=TrackerStyle.muted;ink.textSize=11*d
        canvas.drawText("MPG",7*d,top+5*d,ink)
        val xStep=if(samples.size==1)0f else (right-left)/(samples.size-1)
        val path=Path()
        for(i in samples.indices){
            val x=if(samples.size==1)(left+right)/2 else left+xStep*i
            val y=bottom-((samples[i].mpg/maxY).toFloat()*(bottom-top))
            if(i==0)path.moveTo(x,y)else path.lineTo(x,y)
        }
        canvas.drawPath(path,line)
        for((i,s)in samples.withIndex()){
            val x=if(samples.size==1)(left+right)/2 else left+xStep*i
            val y=bottom-(s.mpg/maxY).toFloat()*(bottom-top)
            dot.color=if(s.maintenanceFlag)TrackerStyle.red else TrackerStyle.green
            canvas.drawCircle(x,y,5*d,dot)
            if(samples.size<=8 || i==0 || i==samples.lastIndex || i%3==0){
                ink.color=TrackerStyle.muted
                val number=(i+1).toString()
                canvas.drawText(number,x-ink.measureText(number)/2,bottom+17*d,ink)
            }
        }
        ink.textSize=11*d
        ink.color=TrackerStyle.muted
        canvas.drawText("Full-tank cycles (oldest to newest)",left,bottom+36*d,ink)
        dot.color=TrackerStyle.red
        canvas.drawCircle(right-75*d,top+9*d,4*d,dot)
        canvas.drawText("Low MPG",right-66*d,top+13*d,ink)
    }
}
