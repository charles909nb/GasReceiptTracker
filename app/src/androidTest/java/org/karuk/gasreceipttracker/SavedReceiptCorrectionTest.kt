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
class SavedReceiptCorrectionTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get()=ctx.getSharedPreferences("usage_data",Context.MODE_PRIVATE)

    @Before fun clear(){prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db")}
    @After fun cleanup(){prefs.edit().clear().commit();ctx.deleteDatabase("fuel_records.db")}

    private fun setup(): Pair<RequestWorkflow,String>{
        val original=JSONObject().put("id","original-fuel-receipt")
            .put("station","Station A").put("date","10/10/2026")
            .put("gallons","12.000").put("price","4.500").put("cost","54.00")
            .put("vehicle","309").put("image","file:///private/receipt_1.jpg")
            .put("ocrRawText","TOTAL $54.00")
        prefs.edit().putString("receipts",JSONArray().put(original).toString())
            .putString("vehicle","309").putString("cardLast4","1234").commit()
        val wf=RequestWorkflow(ctx);wf.initialize()
        return wf to wf.current().getString("id")
    }

    @Test fun correctedReceiptUpdatesSearchAndPreservesImageAndIdentifier(){
        val (wf,id)=setup()
        val change=JSONObject().put("cost","64.00").put("gallons","14.222")
            .put("changedFields",JSONArray().put("total").put("gallons"))
        val updated=wf.correctSavedReceipt(id,"original-fuel-receipt",change)
        assertEquals("original-fuel-receipt",updated.getString("id"))
        assertEquals("file:///private/receipt_1.jpg",updated.getString("image"))
        assertEquals("64.00",updated.getString("cost"))
        assertEquals("54.00",JSONObject(prefs.getString(RequestWorkflow.STATE,"{}"))
            .getJSONArray("orders").getJSONObject(0).getJSONArray("receipts")
            .getJSONObject(0).getString("ocrRawText").let{if(it=="TOTAL $54.00")"54.00"else it})
        TrackerDatabase(ctx).use{db->
            assertEquals(1,db.receiptSearch("64.00").size)
            assertEquals(0,db.receiptSearch("54.00").size)
        }
        assertEquals("64.00",wf.current().getJSONArray("receipts").getJSONObject(0).getString("cost"))
    }

    @Test fun correctionOfPreviouslySentReceiptDoesNotChangeSentPacket(){
        val (wf,id)=setup()
        val state=JSONObject(prefs.getString(RequestWorkflow.STATE,"{}"))
        state.getJSONArray("orders").getJSONObject(0)
            .put("status","sent").put("sentPdfUri","content://original-finance-packet.pdf")
        prefs.edit().putString(RequestWorkflow.STATE,state.toString()).commit()
        val result=wf.correctSavedReceipt(id,"original-fuel-receipt",
            JSONObject().put("date","10/11/2026").put("changedFields",JSONArray().put("date")))
        assertTrue(result.getBoolean("correctedAfterFinanceSubmission"))
        assertEquals(1,result.getJSONArray("correctionHistory").length())
        val originalRequest=wf.all().getJSONObject(0)
        assertEquals("sent",originalRequest.getString("status"))
        assertEquals("content://original-finance-packet.pdf",originalRequest.getString("sentPdfUri"))
        assertEquals("10/11/2026",originalRequest.getJSONArray("receipts").getJSONObject(0).getString("date"))
    }
}
