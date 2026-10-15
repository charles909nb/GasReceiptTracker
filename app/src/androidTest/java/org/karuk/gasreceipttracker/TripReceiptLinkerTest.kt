package org.karuk.gasreceipttracker

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TripReceiptLinkerTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=ins.targetContext
    private val prefs get()=ctx.getSharedPreferences("usage_data", Context.MODE_PRIVATE)
    private var activity:MainActivity?=null
    @Before fun reset(){prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db")}
    @After fun finish(){activity?.let{ins.runOnMainSync{it.finish()}}
        activity=null;ins.waitForIdleSync();prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db")}
    private fun receipt(date:String="10/15/2026", vehicle:String="309", miles:String="1050")=
        JSONObject().put("id","fuel-a").put("date",date).put("vehicle",vehicle)
            .put("odometer",miles).put("cost","45.18").put("gallons","8.123")
            .put("status","complete").put("station","Sample Gas")
            .put("image","file:///private/receipt_1.jpg")
    private fun trip(start:String="1000",end:String="1100",vehicle:String="309",day:String="10/15/2026")=
        JSONObject().put("vehicle",vehicle).put("startOdometer",start)
            .put("endOdometer",end).put("startDate",day).put("endDate",day)
            .put("destination","Yreka").put("purpose","Outreach")

    @Test fun lateEnteredTripAutoAssociatesByDateVehicleAndOdometer(){
        val all=JSONArray().put(trip().put("tripDate","10/15/2026")
            .put("recordedAt","Oct 17, 2026 11:00 AM"))
        val purchases=JSONArray().put(receipt())
        assertEquals(1,TripReceiptLinker.autoLink(all,purchases))
        assertEquals(all.getJSONObject(0).getString("id"),purchases.getJSONObject(0).getString("tripId"))
        assertEquals("auto",purchases.getJSONObject(0).getString("tripLinkSource"))
        assertEquals(0,TripReceiptLinker.autoLink(all,purchases))
        assertEquals(1,purchases.getJSONObject(0).getJSONArray("tripLinkHistory").length())
    }
    @Test fun noAutoMatchWhenVehicleDateOrMileageIsWrong(){
        val all=JSONArray().put(trip())
        for(candidate in listOf(receipt(vehicle="310"),receipt(date="10/14/2026"),
            receipt(miles="999"),receipt(miles="1101"))){
            val purchases=JSONArray().put(candidate)
            assertEquals(0,TripReceiptLinker.autoLink(all,purchases))
            assertTrue(candidate.optString("tripId").isEmpty())
        }
    }
    @Test fun ambiguousOverlappingTripsRequireManualSelection(){
        val all=JSONArray().put(trip()).put(trip("1030","1150"))
        val purchases=JSONArray().put(receipt())
        assertEquals(0,TripReceiptLinker.autoLink(all,purchases))
        assertTrue(purchases.getJSONObject(0).optString("tripId").isBlank())
        TripReceiptLinker.manualLink(all,purchases,1,"fuel-a")
        assertEquals(all.getJSONObject(1).getString("id"),purchases.getJSONObject(0).getString("tripId"))
    }
    @Test fun completedOdometerBoundsAreRequiredForAutoMatch(){
        val partial=trip();partial.remove("endOdometer")
        val all=JSONArray().put(partial);val purchases=JSONArray().put(receipt())
        assertEquals(0,TripReceiptLinker.autoLink(all,purchases))
        partial.put("endOdometer","1100")
        assertEquals(1,TripReceiptLinker.autoLink(all,purchases))
    }
    @Test fun manuallyMovedReceiptCannotBeDuplicatedOrAutoOverridden(){
        val all=JSONArray().put(trip()).put(trip("1080","1190"))
        val purchases=JSONArray().put(receipt())
        TripReceiptLinker.autoLink(all,purchases)
        assertEquals(1,TripReceiptLinker.linkedTo(all.getJSONObject(0),purchases).size)
        TripReceiptLinker.manualLink(all,purchases,1,"fuel-a")
        assertEquals(0,TripReceiptLinker.linkedTo(all.getJSONObject(0),purchases).size)
        assertEquals(1,TripReceiptLinker.linkedTo(all.getJSONObject(1),purchases).size)
        TripReceiptLinker.autoLink(all,purchases)
        assertEquals(1,purchases.length())
        assertEquals("45.18",purchases.getJSONObject(0).getString("cost"))
        assertEquals(all.getJSONObject(1).getString("id"),purchases.getJSONObject(0).getString("tripId"))
    }
    @Test fun unlinkStaysUnlinkedUntilUserLinksItAgain(){
        val all=JSONArray().put(trip());val purchases=JSONArray().put(receipt())
        TripReceiptLinker.autoLink(all,purchases)
        TripReceiptLinker.manualUnlink(purchases,"fuel-a")
        assertEquals(0,TripReceiptLinker.autoLink(all,purchases))
        assertTrue(purchases.getJSONObject(0).optString("tripId").isBlank())
        TripReceiptLinker.manualLink(all,purchases,0,"fuel-a")
        assertFalse(purchases.getJSONObject(0).optBoolean("tripLinkManuallyRemoved"))
        assertEquals(1,TripReceiptLinker.linkedTo(all.getJSONObject(0),purchases).size)
    }
    @Test fun invalidatedAutoLinkIsRemovedButManualLinkIsPreserved(){
        val all=JSONArray().put(trip());val purchases=JSONArray().put(receipt())
        TripReceiptLinker.autoLink(all,purchases)
        all.getJSONObject(0).put("endOdometer","1020")
        TripReceiptLinker.autoLink(all,purchases)
        assertTrue(purchases.getJSONObject(0).optString("tripId").isEmpty())
        TripReceiptLinker.manualLink(all,purchases,0,"fuel-a")
        TripReceiptLinker.autoLink(all,purchases)
        assertEquals(all.getJSONObject(0).getString("id"),purchases.getJSONObject(0).getString("tripId"))
    }
    @Test fun tripLinkSurvivesWorkflowSyncAndDatabaseBackupState(){
        val all=JSONArray().put(trip());val purchases=JSONArray().put(receipt())
        TripReceiptLinker.autoLink(all,purchases)
        prefs.edit().putString("trips",all.toString()).putString("receipts",purchases.toString()).commit()
        val workflow=RequestWorkflow(ctx);workflow.initialize();workflow.sync()
        val indexed=TrackerDatabase(ctx).use{it.receiptSearch("45.18").single()}
        assertEquals(all.getJSONObject(0).getString("id"),indexed.payload.getString("tripId"))
        assertEquals(1,workflow.current().getJSONArray("receipts").length())
        val backup=TrackerDatabase(ctx).use{it.snapshot()}
        ctx.deleteDatabase("fuel_records.db")
        TrackerDatabase(ctx).use{it.restoreSnapshot(backup)}
        val restored=TrackerDatabase(ctx).use{it.receiptSearch("45.18").single()}
        assertEquals(indexed.payload.getString("tripId"),restored.payload.getString("tripId"))
    }
    private fun views(v:View):List<View> = listOf(v)+
        if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))} else emptyList()
    private fun click(title:String){val a=requireNotNull(activity)
        ins.runOnMainSync{val button=views(a.window.decorView)
            .filterIsInstance<Button>().firstOrNull{it.text.toString()==title}
            ?:error("Missing button: "+title)
            button.performClick()}
        ins.waitForIdleSync()
    }
    private fun enter(field:String,value:String){val a=requireNotNull(activity)
        ins.runOnMainSync{val edit=views(a.window.decorView).filterIsInstance<EditText>()
            .firstOrNull{it.contentDescription==field}?:error("Missing: $field")
            edit.setText(value)}
    }
    @Test fun canEnterTripMileageAfterReceiptAndLinkWhenTripEnds(){
        prefs.edit().putBoolean("setupDone",true).putString("driver","Test Driver")
            .putString("vehicle","309").putString("vehicles","309")
            .putString("department","TEST").putString("receipts",JSONArray().put(receipt()).toString())
            .putString("trips","[]").commit()
        RequestWorkflow(ctx).initialize()
        activity=ins.startActivitySync(Intent(ctx,MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        ins.waitForIdleSync()
        enter("Starting odometer","1000")
        enter("Destination","Happy Camp")
        enter("Purpose of travel","Elder outreach")
        click("Start trip")
        var saved=JSONArray(prefs.getString("trips","[]"))
        assertEquals(1,saved.length())
        assertEquals("10/15/2026",saved.getJSONObject(0).getString("tripDate"))
        enter("Ending odometer","1100")
        click("End trip")
        saved=JSONArray(prefs.getString("trips","[]"))
        val fuel=JSONArray(prefs.getString("receipts","[]"))
        assertEquals(saved.getJSONObject(0).getString("id"),fuel.getJSONObject(0).getString("tripId"))
        assertEquals("45.18",fuel.getJSONObject(0).getString("cost"))
        assertEquals(1,JSONArray(prefs.getString("receipts","[]")).length())
    }
}