package org.karuk.gasreceipttracker

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackerDatabaseTest {
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = ctx.getSharedPreferences("usage_data",Context.MODE_PRIVATE)
    @Before fun clean() { prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db") }
    @After fun reset() { prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db") }
    private fun seed() {
        val receipt = JSONObject().put("id","test-receipt-1").put("date","10/07/2026")
            .put("station","Chevron Yreka").put("vehicle","309").put("gallons","8.765")
            .put("price","6.9993").put("cost","61.35").put("fuelType","UNLEAD REG")
            .put("ocrRawText","UNLEAD REG 8.765G\nPRICE/GAL $6.9993\nTOTAL $61.35")
            .put("image","file:///private/receipt_123.jpg").put("receiptCardLast4","5530")
        val trip=JSONObject().put("vehicle","309").put("startDate","October 7, 2026")
            .put("endDate","October 7, 2026").put("destination","Happy Camp").put("purpose","Elders outreach")
            .put("startOdometer","9000").put("endOdometer","9050")
        prefs.edit().putString("driver","Test User").putString("vehicle","309")
            .putString("department","ELDERS").putString("cardLast4","1234")
            .putString("myEmail","test@example.com").putString("vehicles","309,310")
            .putString("trips",JSONArray().put(trip).toString())
            .putString("receipts",JSONArray().put(receipt).toString()).commit()
    }
    @Test fun migratesSavedDataAndSearchesByStationDateVehicleAmount() {
        seed(); val workflow=RequestWorkflow(ctx);workflow.initialize()
        TrackerDatabase(ctx).use { db ->
            for (term in listOf("Chevron", "10/07/2026", "309", "61.35", "PR-00001", "Happy Camp")) {
                val results=db.receiptSearch(term)
                assertEquals("Query "+term,1,results.size)
                assertEquals("8.765",results[0].gallons)
                assertEquals("6.9993",results[0].price)
            }
            val receipt=db.receiptSearch("Chevron").single()
            assertEquals("UNLEAD REG",receipt.fuelType)
            assertEquals("5530",receipt.payload.optString("receiptCardLast4"))
            assertEquals("1234",workflow.current().getJSONObject("profile").getString("cardLast4"))
        }
    }
    @Test fun retainsRawOcrOriginalImageAndCorrectedValuesAcrossSync() {
        seed();val workflow=RequestWorkflow(ctx);workflow.initialize()
        workflow.sync();workflow.sync()
        TrackerDatabase(ctx).use { db ->
            val r=db.receiptSearch("Chevron").single()
            assertTrue(r.rawText.contains("PRICE/GAL $6.9993"))
            assertEquals("file:///private/receipt_123.jpg",r.imageUri)
            assertEquals("61.35",r.total)
            assertEquals("6.9993",r.price)
        }
    }
    @Test fun restoresRequestHistoryWhenOldPreferencesAreLost() {
        seed();RequestWorkflow(ctx).initialize()
        prefs.edit().clear().commit()
        val recovered=RequestWorkflow(ctx);recovered.initialize()
        assertEquals("Test User",prefs.getString("driver",""))
        assertEquals("309",prefs.getString("vehicle",""))
        assertEquals(1,recovered.current().getJSONArray("receipts").length())
        TrackerDatabase(ctx).use{assertEquals(1,it.receiptSearch("Chevron").size)}
    }
    @Test fun roundTripDatabaseSnapshotRetainsAllSearchableHistory() {
        seed();RequestWorkflow(ctx).initialize()
        val snapshot=TrackerDatabase(ctx).use{db->
            db.log(RequestWorkflow(ctx).current().getString("id"),"receipt_saved","verified")
            db.snapshot()
        }
        ctx.deleteDatabase("fuel_records.db")
        TrackerDatabase(ctx).use{db->
            db.restoreSnapshot(snapshot)
            assertEquals(1,db.receiptSearch("61.35").size)
            assertEquals(1,db.snapshot().getJSONArray("activity_log").length())
        }
    }
}
