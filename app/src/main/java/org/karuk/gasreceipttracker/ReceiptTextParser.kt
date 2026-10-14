package org.karuk.gasreceipttracker

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Parses OCR output without overwriting the driver's corrections.
 * Supports pump receipts where gallons end in a single "G" and PRICE/GAL
 * includes four fractional digits. Never silently substitutes a guessed price.
 */
object ReceiptTextParser {
    private val normalizeSpaces = Regex("""[\\t ]+""")
    private val quantityAfter = Regex("""(?<!\\d)(\\d{1,4}[.,]\\d{1,3})\\s*(?:GALLONS?|GALS?|GAL|G)\\b""", RegexOption.IGNORE_CASE)
    private val quantityBefore = Regex("""\\b(?:GALLONS?|GALS?|QTY|QUANTITY|VOLUME)\\s*[:=#-]?\\s*(\\d{1,4}[.,]\\d{1,3})\\b""", RegexOption.IGNORE_CASE)
    private val pricePerGallon = Regex("""(?:\\bPRICE\\s*(?:/|PER\\s+)\\s*GAL(?:LON)?\\b|\\bUNIT\\s*PRICE\\b|\\bP\\s*/\\s*G\\b|\\bCOST\\s*/\\s*GAL\\b)\\s*[:=$-]?\\s*\\$?\\s*(\\d{1,3}[.,]\\d{2,4})\\b""", RegexOption.IGNORE_CASE)
    private val money = Regex("""(?<![\\d.])\\$?\\s*(\\d{1,6}[.,]\\d{2})(?!\\d)""")
    private val date = Regex("""(?<!\\d)(\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4})(?!\\d)""")
    private val time = Regex("""(?<!\\d)(\\d{1,2}:\\d{2}(?::\\d{2})?\\s*(?:AM|PM)?)(?!\\d)""", RegexOption.IGNORE_CASE)
    private val maskedCard = Regex("""(?:[X*#]{4,}[-\\s]*)(\\d{4})\\b""", RegexOption.IGNORE_CASE)
    private val totalLabel = Regex("""\\b(?:FUEL\\s*TOTAL|GRAND\\s*TOTAL|TOTAL\\s*DUE|AMOUNT\\s*DUE|TOTAL|SALE\\s*AMOUNT)\\b""", RegexOption.IGNORE_CASE)
    private val creditLabel = Regex("""\\b(?:CREDIT|CHARGE|VISA|MASTERCARD)\\b""", RegexOption.IGNORE_CASE)
    private val fuelType = Regex("""\\b(?:UNLEAD\\s*REG|REGULAR\s*UNLEADED|UNLEADED|PREMIUM|DIESEL|MIDGRADE)\\b""", RegexOption.IGNORE_CASE)

    private fun cleanNumber(s: String) = s.trim().replace(',', '.')
    private fun lines(s: String) = s.lineSequence().map { it.trim().replace(normalizeSpaces, " ") }
        .filter { it.isNotBlank() }.toList()
    private fun amountNear(lines: List<String>, label: Regex): String {
        val found = mutableListOf<String>()
        for (i in lines.indices) {
            val line = lines[i]
            if (!label.containsMatchIn(line) || line.contains("SUBTOTAL", true) || line.contains("TOTAL GALLONS", true) || line.contains("TOTAL SAVINGS", true)) continue
            val sameLine = money.findAll(line).lastOrNull()?.groupValues?.get(1)
            val nextLine = if (i + 1 < lines.size) money.find(lines[i + 1])?.groupValues?.get(1) else null
            val value = sameLine ?: nextLine
            if (value != null) found.add(cleanNumber(value))
        }
        return found.lastOrNull().orEmpty()
    }
    fun parse(rawText: String): FuelScan {
        val rows = lines(rawText)
        val flat = rows.joinToString(" ")
        val quantity = (quantityAfter.find(flat)?.groupValues?.get(1)
            ?: quantityBefore.find(flat)?.groupValues?.get(1)).orEmpty().let(::cleanNumber)
        val price = pricePerGallon.find(flat)?.groupValues?.get(1).orEmpty().let(::cleanNumber)
        val total = amountNear(rows, totalLabel).ifBlank { amountNear(rows, creditLabel) }
        val vendor = when {
            Regex("""\\bCHEVR(?:ON)?\\b""", RegexOption.IGNORE_CASE).containsMatchIn(flat) -> "Chevron"
            Regex("""\\bSHELL\\b""", RegexOption.IGNORE_CASE).containsMatchIn(flat) -> "Shell"
            Regex("""\\bARCO\\b""", RegexOption.IGNORE_CASE).containsMatchIn(flat) -> "ARCO"
            Regex("""\\bCOSTCO\\b""", RegexOption.IGNORE_CASE).containsMatchIn(flat) -> "Costco"
            else -> rows.take(7).firstOrNull {
                it.any(Char::isLetter) && !Regex("""RECEIPT|WELCOME|THANK|INVOICE|AUTH|CUSTOMER|COPY""", RegexOption.IGNORE_CASE).containsMatchIn(it)
            }.orEmpty()
        }
        val warnings = mutableListOf<String>()
        if (quantity.isBlank()) warnings.add("Gallons not detected")
        if (price.isBlank()) warnings.add("Unit price not detected")
        if (total.isBlank()) warnings.add("Fuel total not detected")
        if (total.isNotBlank() && quantity.isNotBlank() && price.isNotBlank()) {
            try {
                val computed = BigDecimal(quantity).multiply(BigDecimal(price)).setScale(2, RoundingMode.HALF_UP)
                val printed = BigDecimal(total).setScale(2, RoundingMode.HALF_UP)
                if (computed.subtract(printed).abs() > BigDecimal("0.03")) {
                    warnings.add("Printed total differs from gallons × price; verify the receipt")
                }
            } catch (_: NumberFormatException) { warnings.add("Verify the amount, gallons and unit price") }
        }
        return FuelScan(
            vendor, date.find(rawText)?.groupValues?.get(1).orEmpty(), quantity, price, total, rawText,
            fuelType.find(flat)?.value?.uppercase(Locale.US).orEmpty(),
            maskedCard.find(flat)?.groupValues?.get(1).orEmpty(),
            time.find(rawText)?.groupValues?.get(1).orEmpty(),
            warnings
        )
    }
}
