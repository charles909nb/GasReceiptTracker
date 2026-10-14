package org.karuk.gasreceipttracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FinanceArchiveMileageTest {
    @Test fun purchaseDateAndAmountSearchIsDefaultAndSortable(){
        val p=FinancePacketArchive.Packet("1","PR-01","Gas","309",
            "Oct 9, 2026",61.35,"Oct 10, 2026",
            android.net.Uri.parse("content://example/a"),JSONObject(),"sent")
        val q=p.copy(requestId="2",purchaseDate="Oct 2, 2026",amount=32.10)
        assertEquals("1",FinancePacketArchive.filter(listOf(q,p),"","Newest purchase first")[0].requestId)
        assertEquals("2",FinancePacketArchive.filter(listOf(q,p),"","Oldest purchase first")[0].requestId)
        assertEquals("1",FinancePacketArchive.filter(listOf(q,p),"61.35","Newest purchase first").single().requestId)
        assertEquals("2",FinancePacketArchive.filter(listOf(q,p),"32.10","Newest purchase first").single().requestId)
        assertEquals("1",FinancePacketArchive.filter(listOf(q,p),"Oct 9","Newest purchase first").single().requestId)
    }
    private fun receipts(vararg values:Pair<Double,Double>):JSONArray{
        val list=JSONArray()
        val r=JSONArray()
        values.forEachIndexed{i,(odometer,gallons)->
            r.put(JSONObject().put("id","f$i").put("vehicle","309").put("odometer",odometer)
                .put("gallons",gallons).put("cost",65).put("date","Oct "+(i+1)+", 2026"))
        }
        list.put(JSONObject().put("receipts",r))
        return list
    }
    @Test fun mileageUsesNextFuelPurchaseGallons(){
        val points=VehicleFuelAnalytics.points(VehicleFuelAnalytics.fills(
            receipts(1000.0 to 10.0,1250.0 to 10.0,1500.0 to 10.0)),"309")
        assertEquals(2,points.size)
        assertEquals(25.0,points[0].mpg,.001)
    }
    @Test fun mileageDropWarnsOnlyAfterThreeReferenceIntervals(){
        val points=VehicleFuelAnalytics.points(VehicleFuelAnalytics.fills(
            receipts(1000.0 to 10.0,1250.0 to 10.0,1500.0 to 10.0,
                1750.0 to 10.0,1900.0 to 10.0)),"309")
        assertEquals(4,points.size)
        assertFalse(points[2].warning)
        assertTrue(points[3].warning)
        assertEquals(15.0,points[3].mpg,.001)
    }
    @Test fun vehicleMileageNeverMixesOtherVehicles(){
        val a=VehicleFuelAnalytics.fills(receipts(1000.0 to 10.0,1250.0 to 10.0))
        val b=VehicleFuelAnalytics.Fill("410","Oct 4, 2026",900.0,50.0,120.0,"other")
        assertEquals(25.0,VehicleFuelAnalytics.points(a+listOf(b),"309").single().mpg,.001)
    }
}
