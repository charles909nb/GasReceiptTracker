package org.karuk.gasreceipttracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptTextParserTest {
    @Test fun sampleChevronWithSingleGAndFourDecimalPrice() {
        // Synthetic fixture transcribed from the user's sample; never store the
        // customer's original receipt photograph in the public repository.
        val text = """
            1801 Ft Jones Rd.
            Legend 295 Inc Chevr
            YREKA, CA
            10/07/2026 843651442
            04:22:54 PM
            XXXXXXXXXXXX5530
            VISA
            PUMP# 5
            UNLEAD REG    8.765G
            PRICE/GAL    $6.9993
            FUEL TOTAL   $ 61.35
            ------------
            TOTAL = $ 61.35
            CREDIT $ 61.35
        """.trimIndent()
        val result=ReceiptTextParser.parse(text)
        assertEquals("Chevron",result.station)
        assertEquals("10/07/2026",result.date)
        assertEquals("8.765",result.gallons)
        assertEquals("6.9993",result.price)
        assertEquals("61.35",result.total)
        assertEquals("5530",result.cardLast4)
        assertEquals("UNLEAD REG",result.fuelType)
        assertTrue(result.warnings.toString(),result.warnings.isEmpty())
    }
    @Test fun OCRMaySplitPriceAndAmountOntoSeparateLines() {
        val result=ReceiptTextParser.parse("""
            CHEVRON
            10/07/2026
            UNLEAD REG
            8.765G
            PRICE/GAL
            $6.9993
            FUEL TOTAL
            $61.35
            TOTAL =
            $61.35
        """.trimIndent())
        assertEquals("8.765",result.gallons)
        assertEquals("6.9993",result.price)
        assertEquals("61.35",result.total)
    }
    @Test fun acceptsFullGallonUnitAndStandardThreeDecimalUnitPrice() {
        val result=ReceiptTextParser.parse("SHELL\n8.765 GALLONS\nPRICE / GAL $6.999\nTOTAL $61.35")
        assertEquals("8.765",result.gallons)
        assertEquals("6.999",result.price)
        assertEquals("61.35",result.total)
    }
    @Test fun ignoresSubtotalAndDoesNotTreatFourDecimalPriceAsTotal() {
        val result=ReceiptTextParser.parse("UNLEAD REG 8.765G\nPRICE/GAL $6.9993\nSUBTOTAL $44.00\nTAX $5.25")
        assertEquals("",result.total)
        assertTrue(result.warnings.any{it.contains("total",true)})
    }
    @Test fun flagsQuantityPriceVsTotalDisagreementForDriverReview() {
        val result=ReceiptTextParser.parse("GALLONS 8.765\nPRICE/GAL 6.9993\nTOTAL $71.35")
        assertEquals("71.35",result.total)
        assertTrue(result.warnings.any{it.contains("differs")})
    }
    @Test fun emptyOCRMustNotInventReceiptNumbers() {
        val result=ReceiptTextParser.parse("CUSTOMER COPY")
        assertTrue(result.gallons.isBlank()&&result.price.isBlank()&&result.total.isBlank())
        assertEquals(3,result.warnings.size)
    }
}
