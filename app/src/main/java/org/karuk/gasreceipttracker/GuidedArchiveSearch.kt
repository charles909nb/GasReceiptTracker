package org.karuk.gasreceipttracker

import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

/** Values here are taken only from records already saved on the phone. */
data class ArchiveSearchRow(
    val id: String,
    val date: String,
    val amount: Double,
    val vehicle: String,
    val reference: String,
    val status: String,
    val title: String,
    val station: String = "",
    val sentDate: String = ""
)

data class ArchiveSearchCriteria(
    val field: String = "Purchase date",
    val match: String = "Any date",
    val first: String = "",
    val second: String = "",
    val sort: String = "Newest purchase first"
)

/** A reusable query engine for Finance packets and historical receipt records.
 * The suggestion list can NEVER contain a value that is not in these rows.
 */
object GuidedArchiveSearch {
    val fields = listOf("Purchase date", "Amount", "Vehicle", "PR number", "Status",
        "Gas station", "Request title", "Sent date", "All records")
    val sorts = listOf("Newest purchase first", "Oldest purchase first",
        "Amount: highest first", "Amount: lowest first")
    val dateModes = listOf("Any date", "Exact recorded date", "Between dates", "Near date (7 days)")
    val amountModes = listOf("Any amount", "Near amount (5 dollars)", "Exact recorded amount", "Between amounts")
    const val nearAmountDollars = 5.0
    const val nearDateDays: Long = 7L

    fun parseDate(s: String): Long? {
        val raw=s.trim()
        if(raw.isEmpty())return null
        for(pattern in listOf("MMM d, yyyy h:mm a", "MMM d, yyyy", "MMMM d, yyyy",
            "MM/dd/yyyy", "M/d/yyyy", "MM/dd/yy", "M/d/yy", "yyyy-MM-dd",
            "MM-dd-yyyy", "M-d-yyyy")) {
            try {
                val f=SimpleDateFormat(pattern, Locale.US).apply { isLenient=false }
                val p=java.text.ParsePosition(0)
                val parsed=f.parse(raw,p)
                if(parsed!=null && p.index==raw.length) {
                    // Treat all dates at the calendar-day granularity, not time of day.
                    return java.time.Instant.ofEpochMilli(parsed.time)
                        .atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay()
                }
            }catch(_:Exception) {}
        }
        return null
    }
    fun parseAmount(s: String): Double? = s.replace("$", "")
        .replace(",", "").trim().toDoubleOrNull()?.takeIf { it.isFinite() && it>=0.0 }
    fun money(v: Double) = String.format(Locale.US, "$%.2f", v)

    fun choices(rows: List<ArchiveSearchRow>, field: String): List<String> = when(field) {
        "Purchase date" -> rows.map { it.date }.filter { parseDate(it)!=null }
            .distinct().sortedByDescending { parseDate(it) }
        "Amount" -> rows.map { money(it.amount) }.distinct()
            .sortedBy { parseAmount(it) }
        "Sent date" -> rows.map { it.sentDate }.filter { parseDate(it)!=null }
            .distinct().sortedByDescending { parseDate(it) }
        "Gas station" -> rows.map { it.station.trim() }.filter { it.isNotBlank() }
            .distinct().sorted()
        "Request title" -> rows.map { it.title.trim() }.filter { it.isNotBlank() }
            .distinct().sorted()
        "Vehicle" -> rows.map { it.vehicle.trim() }.filter { it.isNotBlank() }
            .distinct().sorted()
        "PR number" -> rows.map { it.reference.trim() }.filter { it.isNotBlank() }
            .distinct().sorted()
        "Status" -> rows.map { it.status.trim() }.filter { it.isNotBlank() }
            .distinct().sorted()
        else -> emptyList()
    }

    /** Typing 60 only offers saved amounts in [55,65]; 70/80 never appear.
     * A date entered as 10/07/2026 suggests only records within seven days.
     */
    fun suggestions(rows: List<ArchiveSearchRow>, field: String, typed: String): List<String> {
        val saved = choices(rows,field)
        val query=typed.trim()
        if(query.isEmpty())return saved.take(50)
        val close = when(field) {
            "Amount" -> {
                val target=parseAmount(query)
                if(target==null)emptyList() else saved.filter {
                    abs((parseAmount(it)?:Double.MAX_VALUE)-target) <= nearAmountDollars + .00001
                }.sortedBy { abs((parseAmount(it)?:0.0)-target) }
            }
            "Purchase date","Sent date" -> {
                val target=parseDate(query)
                if(target!=null) saved.filter {
                    abs((parseDate(it)?:Long.MAX_VALUE)-target)<=nearDateDays
                }.sortedBy { abs((parseDate(it)?:0L)-target) }
                else {
                    val digits=query.replace(Regex("[^0-9]"),"")
                    saved.filter { value ->
                        val day=parseDate(value)
                        val numeric=day?.let {
                            java.time.LocalDate.ofEpochDay(it).format(
                                java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy",Locale.US))
                        }.orEmpty()
                        val normalized=numeric.replace(Regex("[^0-9]"),"")
                        value.contains(query,true) || numeric.contains(query,true) ||
                            (digits.length>=2 && normalized.startsWith(digits))
                    }
                }
            }
            else -> saved.filter { it.contains(query,true) }
        }
        return close.take(50)
    }

    fun results(rows: List<ArchiveSearchRow>, criteria: ArchiveSearchCriteria): List<ArchiveSearchRow> {
        val q=criteria.first.trim()
        val end=criteria.second.trim()
        val matches=rows.filter { r ->
            when(criteria.field) {
                "Purchase date", "Sent date" -> {
                    if(criteria.match=="Any date") true
                    else {
                        val d=parseDate(if(criteria.field=="Sent date")r.sentDate else r.date)
                        val first=if(q.isBlank())null else parseDate(q)
                        val last=if(end.isBlank())null else parseDate(end)
                        when(criteria.match) {
                            "Exact recorded date" -> first!=null && d!=null && first==d
                            "Near date (7 days)" -> first!=null && d!=null && abs(first-d)<=nearDateDays
                            "Between dates" -> d!=null && (q.isBlank() || first!=null && d>=first) &&
                                (end.isBlank() || last!=null && d<=last)
                            else -> true
                        }
                    }
                }
                "Amount" -> {
                    if(criteria.match=="Any amount")true
                    else {
                        val first=if(q.isBlank())null else parseAmount(q)
                        val last=if(end.isBlank())null else parseAmount(end)
                        when(criteria.match) {
                            "Exact recorded amount" -> first!=null && abs(r.amount-first)<0.005
                            "Near amount (5 dollars)" -> first!=null && abs(r.amount-first)<=nearAmountDollars+0.00001
                            "Between amounts" -> (q.isBlank() || first!=null && r.amount>=first-.005) &&
                                (end.isBlank() || last!=null && r.amount<=last+.005)
                            else -> true
                        }
                    }
                }
                "Vehicle" -> q.isEmpty() || r.vehicle.equals(q,true)
                "Gas station" -> q.isEmpty() || r.station.contains(q,true)
                "Request title" -> q.isEmpty() || r.title.contains(q,true)
                "PR number" -> q.isEmpty() || r.reference.contains(q,true)
                "Status" -> q.isEmpty() || r.status.equals(q,true)
                else -> true
            }
        }
        return when(criteria.sort) {
            "Oldest purchase first" -> matches.sortedBy { parseDate(it.date)?:Long.MIN_VALUE }
            "Amount: highest first" -> matches.sortedWith(compareByDescending<ArchiveSearchRow>{it.amount}
                .thenByDescending { parseDate(it.date)?:Long.MIN_VALUE })
            "Amount: lowest first" -> matches.sortedWith(compareBy<ArchiveSearchRow>{it.amount}
                .thenByDescending { parseDate(it.date)?:Long.MIN_VALUE })
            else -> matches.sortedWith(compareByDescending<ArchiveSearchRow>{parseDate(it.date)?:Long.MIN_VALUE}
                .thenByDescending { it.id })
        }
    }
}