package org.karuk.gasreceipttracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GuidedArchiveSearchTest {
    private val rows=listOf(
        ArchiveSearchRow("1","Oct 7, 2026",61.35,"309","PR-00021","Sent","Yreka trip","Chevron","Oct 8, 2026 10:10 AM"),
        ArchiveSearchRow("2","Oct 9, 2026",62.75,"309","PR-00022","Sent","Happy Camp","Shell","Oct 10, 2026 9:20 AM"),
        ArchiveSearchRow("3","Oct 13, 2026",70.00,"410","PR-00023","Draft","Orleans","Chevron",""),
        ArchiveSearchRow("4","Oct 25, 2026",81.50,"410","PR-00024","Archived","Service","Chevron",""))
    private fun lookup(field:String,match:String,first:String,second:String="",sort:String="Newest purchase first") =
        GuidedArchiveSearch.results(rows,ArchiveSearchCriteria(field,match,first,second,sort))

    @Test fun suggestionsAreAlwaysRealRecordedAmounts(){
        assertEquals(listOf("$61.35","$62.75"), GuidedArchiveSearch.suggestions(rows,"Amount","60"))
        assertFalse(GuidedArchiveSearch.suggestions(rows,"Amount","60").contains("$70.00"))
        assertFalse(GuidedArchiveSearch.suggestions(rows,"Amount","60").contains("$81.50"))
    }
    @Test fun selectingAnExactRecordedAmountExcludesNeighbors(){
        assertEquals(listOf("1"),lookup("Amount","Exact recorded amount","61.35").map{it.id})
    }
    @Test fun nearAmountHasFiveDollarBoundedSearch(){
        assertEquals(listOf("2","1"),lookup("Amount","Near amount (5 dollars)","60").map{it.id})
    }
    @Test fun inclusiveAmountRangeOnlyShowsSavedTransactions(){
        assertEquals(listOf("3","2"),lookup("Amount","Between amounts","62","75").map{it.id})
    }
    @Test fun dateSuggestionsAroundOctoberSeventhOmitLateMonth(){
        val actual=GuidedArchiveSearch.suggestions(rows,"Purchase date","10/07/2026")
        assertEquals(listOf("Oct 7, 2026","Oct 9, 2026","Oct 13, 2026"),actual)
        assertFalse(actual.contains("Oct 25, 2026"))
    }
    @Test fun exactRecordedDateMatchesAnyRecognizedDisplayFormat(){
        assertEquals(listOf("1"),lookup("Purchase date","Exact recorded date","10/07/2026").map{it.id})
    }
    @Test fun dateBetweenEndpointsIsInclusive(){
        assertEquals(listOf("3","2"),lookup("Purchase date","Between dates","10/09/2026","10/13/2026").map{it.id})
    }
    @Test fun nearDateLimitedToSevenDays(){
        assertEquals(listOf("3","2","1"),lookup("Purchase date","Near date (7 days)","10/09/2026").map{it.id})
    }
    @Test fun typedMonthDayOnlySuggestsExistingNearbyDates(){
        val values=GuidedArchiveSearch.suggestions(rows,"Purchase date","10/0")
        assertEquals(listOf("Oct 9, 2026","Oct 7, 2026"),values)
    }
    @Test fun vehicleSelectionExcludesOtherVehicle(){
        assertEquals(listOf("2","1"),lookup("Vehicle","Exact","309").map{it.id})
    }
    @Test fun matchingRequestNumberFiltersExistingReferencesOnly(){
        assertEquals(listOf("3"),lookup("PR number","Exact","PR-00023").map{it.id})
        assertEquals(listOf("PR-00021","PR-00022"),GuidedArchiveSearch.suggestions(rows,"PR number","PR-0002").take(2))
    }
    @Test fun statusDropdownUsesOnlyStatusesInRecords(){
        assertEquals(listOf("Archived","Draft","Sent"),GuidedArchiveSearch.choices(rows,"Status"))
        assertEquals(listOf("4"),lookup("Status","Exact","Archived").map{it.id})
    }
    @Test fun invalidDateAndAmountDoNotSilentlyShowAllRecords(){
        assertTrue(lookup("Purchase date","Exact recorded date","not-a-date").isEmpty())
        assertTrue(lookup("Amount","Near amount (5 dollars)","fake").isEmpty())
    }
    @Test fun allRecordsDefaultSortNewestPurchaseFirst(){
        assertEquals(listOf("4","3","2","1"),
            GuidedArchiveSearch.results(rows,ArchiveSearchCriteria()).map{it.id})
    }
    @Test fun highestAndLowestAmountSortingAreNumeric(){
        assertEquals("4",lookup("Purchase date","Any date","",sort="Amount: highest first").first().id)
        assertEquals("1",lookup("Purchase date","Any date","",sort="Amount: lowest first").first().id)
    }
    @Test fun previousStationSearchIsPreservedAsActualDropdownChoices(){
        assertEquals(listOf("Chevron","Shell"),GuidedArchiveSearch.choices(rows,"Gas station"))
        assertEquals(listOf("2"),lookup("Gas station","Exact","Shell").map{it.id})
    }
    @Test fun previousTitleLookupStillWorks(){
        assertEquals(listOf("2"),lookup("Request title","Exact","Happy Camp").map{it.id})
    }
    @Test fun sentDateSearchSupportsExactDatesAndRanges(){
        assertEquals(listOf("2"),lookup("Sent date","Exact recorded date","Oct 10, 2026").map{it.id})
        assertEquals(listOf("2","1"),lookup("Sent date","Between dates","10/08/2026","10/10/2026").map{it.id})
    }
    @Test fun dateParsedAsCalendarDayAcrossDisplayFormats(){
        val a=GuidedArchiveSearch.parseDate("10/07/2026")
        assertNotNull(a)
        assertEquals(a,GuidedArchiveSearch.parseDate("Oct 7, 2026"))
        assertEquals(a,GuidedArchiveSearch.parseDate("2026-10-07"))
    }
}