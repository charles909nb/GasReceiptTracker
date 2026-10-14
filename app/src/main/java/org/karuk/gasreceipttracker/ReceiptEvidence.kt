package org.karuk.gasreceipttracker

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** The OCR rectangle is normalized to the displayed, upright receipt image (0..1).
 *  Coordinates are OCR observations, never inferred from a manually edited value.
 */
data class ReceiptEvidence(
    val field: String, val recognized: String, val left: Float, val top: Float,
    val right: Float, val bottom: Float, val source: String = "original"
) {
    fun toJson() = JSONObject().put("field",field).put("recognized",ReceiptPrivacy.redact(recognized))
        .put("left",left.toDouble()).put("top",top.toDouble())
        .put("right",right.toDouble()).put("bottom",bottom.toDouble())
        .put("source",source)
    companion object {
        fun fromJson(j:JSONObject) = ReceiptEvidence(
            j.optString("field"),j.optString("recognized"),
            j.optDouble("left").toFloat(),j.optDouble("top").toFloat(),
            j.optDouble("right").toFloat(),j.optDouble("bottom").toFloat(),
            j.optString("source","original"))
        fun asJson(list:List<ReceiptEvidence>)=JSONArray().apply{list.forEach { put(it.toJson()) }}
        fun fromArray(raw:String):List<ReceiptEvidence> = runCatching{
            val a=JSONArray(raw); (0 until a.length()).map{fromJson(a.getJSONObject(it))}
                .filter { it.left>=0f && it.right<=1f && it.top>=0f && it.bottom<=1f
                        && it.right>it.left && it.bottom>it.top }
        }.getOrDefault(emptyList())
    }
}

data class ReceiptOcrWord(val text:String,val box:ReceiptEvidence)
data class ReceiptOcrLine(val text:String,val box:ReceiptEvidence,val words:List<ReceiptOcrWord>)

/** Only the masked card suffix is stored or compared, never a full PAN. */
object ReceiptPrivacy {
    private val potentialPan=Regex("""(?<![0-9])(?:[0-9][ -]?){13,19}(?![0-9])""")
    fun redact(text:String):String = potentialPan.replace(text,"[CARD NUMBER REDACTED]")
    fun last4(value:String)=value.trim().takeIf{it.matches(Regex("[0-9]{4}"))}.orEmpty()
    fun brand(value:String)=when(value.trim().uppercase(Locale.US).replace(" ","")) {
        "VISA"->"Visa"
        "MASTERCARD"->"Mastercard"
        "AMERICANEXPRESS","AMEX"->"American Express"
        "DISCOVER"->"Discover"
        "OTHER"->"Other"
        else->""
    }
}

data class CardCheck(val status:String,val message:String,val mismatch:Boolean)

/** A mismatch needs explicit user confirmation; the profile is never overwritten. */
object ReceiptCardVerifier {
    fun verify(profileLast4:String,profileBrand:String,receiptLast4:String,receiptBrand:String):CardCheck {
        val saved=ReceiptPrivacy.last4(profileLast4)
        val found=ReceiptPrivacy.last4(receiptLast4)
        val originalBrand=ReceiptPrivacy.brand(profileBrand)
        val foundBrand=ReceiptPrivacy.brand(receiptBrand)
        val mismatchNumber=saved.isNotBlank() && found.isNotBlank() && saved!=found
        val mismatchBrand=originalBrand.isNotBlank() && foundBrand.isNotBlank() && originalBrand!=foundBrand
        if(mismatchNumber || mismatchBrand){
            val expected=listOfNotNull(originalBrand.takeIf{it.isNotBlank()},saved.takeIf{it.isNotBlank()}?.let{"••••"+it}).joinToString(" ")
            val actual=listOfNotNull(foundBrand.takeIf{it.isNotBlank()},found.takeIf{it.isNotBlank()}?.let{"••••"+it}).joinToString(" ")
            return CardCheck("MISMATCH","Different card: saved $expected; receipt $actual. Verify before using this card on the PR.",true)
        }
        if(saved.isNotBlank() && found.isNotBlank() &&
            (originalBrand.isBlank() || foundBrand.isBlank() || originalBrand==foundBrand)) {
            return CardCheck("MATCH","Receipt card ending $found matches your saved card.",false)
        }
        return CardCheck("UNVERIFIED",when {
            found.isBlank()->"Card ending was not found. Inspect the photo or enter the printed last four."
            saved.isBlank()->"Receipt shows ••••$found; no default card number is saved for comparison."
            else->"Check the receipt card details before saving."
        },false)
    }
}

/** Assign OCR element boxes to actually recognized fields. A field without a box
 * is shown as "Not located" instead of highlighting a guessed part of the photo.
 */
object ReceiptEvidenceMatcher {
    private fun norm(rect:Rect?,width:Int,height:Int,source:String):ReceiptEvidence? {
        if(rect==null || width<=0 || height<=0)return null
        val l=(rect.left.toFloat()/width).coerceIn(0f,1f)
        val t=(rect.top.toFloat()/height).coerceIn(0f,1f)
        val r=(rect.right.toFloat()/width).coerceIn(0f,1f)
        val b=(rect.bottom.toFloat()/height).coerceIn(0f,1f)
        return if(r>l && b>t)ReceiptEvidence("", "", l,t,r,b,source)else null
    }
    fun capture(text:Text,width:Int,height:Int,source:String):List<ReceiptOcrLine> =
        text.textBlocks.flatMap { block -> block.lines.mapNotNull { line ->
            val frame=norm(line.boundingBox,width,height,source) ?: return@mapNotNull null
            val words=line.elements.mapNotNull{element->
                val rect=norm(element.boundingBox,width,height,source)?:return@mapNotNull null
                ReceiptOcrWord(element.text,rect)
            }
            ReceiptOcrLine(line.text,frame,words)
        }}
    private fun matches(value:String,line:String):Boolean{
        if(value.isBlank())return false
        val needle=value.trim().uppercase(Locale.US).replace(",",".")
        val hay=line.uppercase(Locale.US).replace(",",".")
        return hay.contains(needle) || (needle=="CHEVRON" && hay.contains("CHEVR")) ||
            (needle=="AMERICAN EXPRESS" && (hay.contains("AMEX") || hay.contains("AMERICAN EXPRESS")))
    }
    private fun preference(field:String,line:String,previous:String):Int {
        val s=line.uppercase(Locale.US);val prev=previous.uppercase(Locale.US)
        return when(field){
            "station"->if(s.contains("CHEVR")||s.contains("SHELL")||s.contains("ARCO"))15 else 2
            "date"->if(Regex("""\b\d{1,2}[/.-]\d{1,2}[/.-]\d{2,4}\b""").containsMatchIn(s))15 else 0
            "time"->5
            "gallons"->if(Regex("""\d[.,]\d+\s*(G|GAL|GALLONS)\b""").containsMatchIn(s))25
                else if(s.contains("GAL") || prev.contains("GAL"))15 else 1
            "price"->if(s.contains("PRICE/GAL") || s.contains("UNIT PRICE"))30
                else if(prev.contains("PRICE/GAL") || prev.contains("UNIT PRICE"))22
                else if(s.contains("TOTAL"))-30 else 1
            "total"->if(s.contains("FUEL TOTAL"))40
                else if(s.contains("TOTAL") && !s.contains("SUBTOTAL"))30
                else if(prev.contains("FUEL TOTAL"))23
                else if(s.contains("CREDIT"))15
                else if(s.contains("PRICE/GAL"))-30 else 1
            "fuelType"->if(s.contains("UNLEAD")||s.contains("DIESEL"))20 else 1
            "cardType"->if(s.contains("VISA")||s.contains("MASTER")||s.contains("AMEX")||s.contains("DISCOVER"))20 else 1
            "cardLast4"->if(s.contains("XXXX")||s.contains("****"))30
                else if(prev.contains("VISA")||prev.contains("MASTER"))10 else 1
            else->0
        }
    }
    private fun bestWord(field:String,value:String,line:ReceiptOcrLine):ReceiptEvidence {
        val candidates=line.words.filter{matches(value,it.text)}
        val win=when {
            candidates.isEmpty()->line.box
            field=="cardLast4"->candidates.last().box
            else->candidates.minByOrNull { it.box.right-it.box.left }!!.box
        }
        return win.copy(field=field, recognized=ReceiptPrivacy.redact(line.text))
    }
    fun locate(scan:FuelScan,ocr:List<ReceiptOcrLine>):List<ReceiptEvidence> {
        val vals=linkedMapOf(
            "station" to scan.station, "date" to scan.date, "time" to scan.time,
            "fuelType" to scan.fuelType,"gallons" to scan.gallons,
            "price" to scan.price,"total" to scan.total,
            "cardType" to scan.cardType,"cardLast4" to scan.cardLast4)
        return vals.mapNotNull{(field,value)->
            if(value.isBlank())return@mapNotNull null
            val ranked=ocr.mapIndexedNotNull{index,line->
                if(!matches(value,line.text))return@mapIndexedNotNull null
                val prev=ocr.getOrNull(index-1)?.text.orEmpty()
                // Do not highlight a different number merely because it appears elsewhere.
                val score=preference(field,line.text,prev)
                if(score<0)return@mapIndexedNotNull null
                val evidence=bestWord(field,value,line)
                score to evidence
            }
            ranked.maxByOrNull{it.first}?.second
        }
    }
}
