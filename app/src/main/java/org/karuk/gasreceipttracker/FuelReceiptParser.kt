package org.karuk.gasreceipttracker

import java.util.Locale
import java.math.BigDecimal
import org.json.JSONObject
import org.json.JSONArray

/** Uses the label/value relationship, not the last decimal anywhere on a receipt. */
object FuelReceiptParser {
    data class Line(val text:String,val x:Float,val y:Float,val width:Float,val height:Float)
    data class Parsed(val station:String,val date:String,val gallons:String,val price:String,val total:String,
        val cardLast4:String,val time:String,val warnings:List<String>,val raw:String,val rows:List<String>) {
        fun json()=JSONObject().put("station",station).put("date",date).put("receiptDate",date).put("gallons",gallons)
            .put("price",price).put("cost",total).put("cardLast4",cardLast4).put("receiptTime",time)
            .put("warnings",JSONArray(warnings)).put("rawText",raw).put("rows",JSONArray(rows))
    }
    private val numeric="([0-9]{1,6}(?:,[0-9]{3})*[.,][0-9]{2,4})"
    private val number=Regex(numeric)
    private val totalLabel=Regex("\\b(?:FUEL\\s+TOTAL|GRAND\\s+TOTAL|TOTAL\\s+DUE|AMOUNT\\s+DUE|SALE\\s+AMOUNT|TOTAL)\\b",RegexOption.IGNORE_CASE)
    private val priceLabel=Regex("(?:PRICE\\s*(?:/\\s*GAL(?:LON)?|PER\\s+GAL(?:LON)?)?|UNIT\\s*(?:PRICE)?|P\\s*/\\s*G|RATE)",RegexOption.IGNORE_CASE)
    private val gallonSuffix=Regex("$numeric\\s*(?:GALLONS?|GALS?|G)(?![A-Z])",RegexOption.IGNORE_CASE)
    private val gallonPrefix=Regex("(?:GALLONS?|GALS|VOLUME)\\s*[:=]?\\s*\\$?\\s*$numeric",RegexOption.IGNORE_CASE)
    private fun decimal(s:String):String {
        val normalized=if(s.contains('.')&&s.contains(','))s.replace(",","")else s.replace(',','.')
        return runCatching{BigDecimal(normalized).toPlainString()}.getOrDefault("")
    }
    fun spatialRows(lines:List<Line>):List<String>{
        val rows=mutableListOf<MutableList<Line>>()
        for(line in lines.sortedBy{it.y+it.height/2}){
            val row=rows.lastOrNull()
            val same=row!=null&&kotlin.math.abs(row.map{it.y+it.height/2}.average()-(line.y+line.height/2))<=maxOf(5f,minOf(line.height,row.map{it.height}.average().toFloat())*.55f)
            if(same)row!!.add(line)else rows.add(mutableListOf(line))
        }
        return rows.map{it.sortedBy{l->l.x}.joinToString(" "){l->l.text.trim()}}
    }
    fun parse(raw:String, spatial:List<Line> = emptyList()):Parsed {
        val rows=(if(spatial.isEmpty())raw.lines()else spatialRows(spatial)).map{it.trim().replace(Regex("[\\t ]+")," ")}.filter{it.isNotBlank()}
        fun valueAfter(index:Int,label:Regex):String {
            val line=rows[index];val labelMatch=label.find(line)?:return ""
            val tail=line.substring(labelMatch.range.last+1)
            val same=number.findAll(tail).map{decimal(it.groupValues[1])}.lastOrNull()
            if(same!=null)return same
            // OCR may put the numeric cell on the following physical row. Never jump across labels.
            val next=rows.getOrNull(index+1).orEmpty()
            return if(next.matches(Regex("[\\$ =:]*$numeric[\\$ ]*")))number.find(next)?.groupValues?.get(1)?.let{decimal(it)}.orEmpty() else ""
        }
        var price="";var gallons=""
        val totalCandidates=mutableListOf<Pair<Int,String>>()
        for((i,line)in rows.withIndex()){
            val upper=line.uppercase(Locale.US)
            if(priceLabel.containsMatchIn(line)){
                val p=valueAfter(i,priceLabel)
                if(p.toDoubleOrNull()?.let{it>0&&it<100}==true)price=p
            }
            if(!upper.contains("PRICE")&&!upper.contains("/GAL")&&!upper.contains("PER GAL")){
                val qty=gallonSuffix.find(line)?.groupValues?.get(1)?:gallonPrefix.find(line)?.groupValues?.get(1)
                if(qty!=null)gallons=decimal(qty)
                else if(Regex("^(?:GALLONS?|GALS|VOLUME)\\b",RegexOption.IGNORE_CASE).containsMatchIn(line))
                    gallons=valueAfter(i,Regex("GALLONS?|GALS|VOLUME",RegexOption.IGNORE_CASE))
            }
            if(totalLabel.containsMatchIn(line)&&!Regex("SUB\\s*TOTAL|TAX|GALLONS|SAVINGS|DISCOUNT",RegexOption.IGNORE_CASE).containsMatchIn(line)){
                val v=valueAfter(i,totalLabel)
                if(v.toDoubleOrNull()?.let{it>0}==true)totalCandidates.add((if(upper.contains("FUEL TOTAL"))100 else if(upper.contains("GRAND")||upper.contains("DUE"))95 else 90)to v)
            }
        }
        val total=totalCandidates.maxByOrNull{it.first}?.second.orEmpty()
        val header=rows.takeWhile{!Regex("PUMP|UNLEAD|PRICE/|FUEL TOTAL",RegexOption.IGNORE_CASE).containsMatchIn(it)}
        val station=header.firstOrNull{line->
            line.any{it.isLetter()}&&!Regex("^[0-9]|RECEIPT|WELCOME|THANK|CUSTOMER|VISA|MASTERCARD|INVOICE|AUTH|^X{2,}|\\b(?:CA|OR)\\b|^\\d{1,2}:",RegexOption.IGNORE_CASE).containsMatchIn(line)
        }.orEmpty()
        val date=Regex("\\b[0-9]{1,2}[/\\-][0-9]{1,2}[/\\-][0-9]{2,4}\\b").find(rows.joinToString("\n"))?.value.orEmpty()
        val time=Regex("\\b[0-9]{1,2}:[0-9]{2}(?::[0-9]{2})?\\s*(?:AM|PM)?\\b",RegexOption.IGNORE_CASE).find(raw)?.value.orEmpty()
        val card=Regex("(?:[Xx*]{4,}\\s*|(?:ENDING|LAST 4)\\s*[:#]?\\s*)([0-9]{4})\\b").find(raw)?.groupValues?.get(1).orEmpty()
        val warnings=mutableListOf<String>()
        if(gallons.isBlank())warnings.add("Gallons were not recognized")
        if(price.isBlank())warnings.add("Price per gallon was not recognized")
        if(total.isBlank())warnings.add("Total amount was not recognized")
        if(date.isBlank())warnings.add("Receipt date was not recognized; enter the actual purchase date")
        val g=gallons.toDoubleOrNull();val p=price.toDoubleOrNull();val t=total.toDoubleOrNull()
        if(g!=null&&p!=null&&t!=null&&kotlin.math.abs(g*p-t)>maxOf(.03,t*.005))warnings.add("Gallons times price differs from total. Check the receipt; no amount was substituted.")
        if(totalCandidates.map{it.second}.distinct().size>1)warnings.add("More than one total was detected. Confirm the fuel total.")
        return Parsed(station,date,gallons,price,total,card,time,warnings,raw,rows)
    }
}
