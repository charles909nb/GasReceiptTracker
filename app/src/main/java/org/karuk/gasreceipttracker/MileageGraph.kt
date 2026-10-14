package org.karuk.gasreceipttracker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import java.util.Locale

/** Offline MPG trend with each fuel interval; red dots are potential maintenance flags. */
class MileageGraph(context:Context,private val points:List<VehicleFuelAnalytics.Point>):View(context){
    private val density=resources.displayMetrics.density
    private val p=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onMeasure(widthMeasureSpec:Int,heightMeasureSpec:Int){
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            (260*density).toInt())
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        c.drawColor(Color.WHITE)
        if(points.isEmpty())return
        val left=54*density;val right=width-16*density
        val top=20*density;val bottom=height-42*density
        val ymax=(points.maxOf{it.mpg}*1.2).coerceAtLeast(10.0)
        p.strokeWidth=density;p.color=Color.LTGRAY;p.style=Paint.Style.STROKE
        for(i in 0..4){
            val y=bottom-(bottom-top)*i/4f
            c.drawLine(left,y,right,y,p)
            p.style=Paint.Style.FILL;p.color=Color.DKGRAY;p.textSize=11*density
            c.drawText(String.format(Locale.US,"%.0f",ymax*i/4),5*density,y+4*density,p)
            p.style=Paint.Style.STROKE;p.color=Color.LTGRAY
        }
        fun x(i:Int)=if(points.size==1)(left+right)/2 else left+(right-left)*i/(points.size-1)
        fun y(mpg:Double)=bottom-((mpg/ymax)*(bottom-top)).toFloat()
        p.strokeWidth=2*density;p.color=Color.rgb(32,99,158)
        for(i in 1 until points.size)c.drawLine(x(i-1),y(points[i-1].mpg),x(i),y(points[i].mpg),p)
        for((i,item)in points.withIndex()){
            p.color=if(item.warning)Color.rgb(186,34,39)else Color.rgb(32,99,158)
            p.style=Paint.Style.FILL
            c.drawCircle(x(i),y(item.mpg),5*density,p)
        }
        p.textSize=12*density;p.color=Color.DKGRAY
        c.drawText("MPG",5*density,12*density,p)
        c.drawText("Older → Newer",left,bottom+28*density,p)
        c.drawText("Red = check vehicle",right-118*density,bottom+28*density,p)
    }
}
