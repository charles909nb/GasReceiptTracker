package org.karuk.gasreceipttracker

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReceiptLocalLearningTest {
    private val ctx get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun learnsStationFieldLocationWithoutStoringAmounts(){
        ReceiptLocalLearning.clear(ctx)
        val box=ReceiptEvidence("total","$61.35",0.61f,0.68f,0.78f,0.72f)
        ReceiptLocalLearning.learn(ctx,"Example Fuel Station","total",box)
        val hint=ReceiptLocalLearning.hint(ctx,"Example Fuel Station","total")
        assertNotNull(hint)
        assertEquals(1,hint!!.getInt("count"))
        assertNull(ReceiptLocalLearning.hint(ctx,"Different Station","total"))
        val raw=ctx.getSharedPreferences("usage_data",0).getString(ReceiptLocalLearning.KEY,"")!!
        assertFalse(raw.contains("61.35"))
        assertFalse(raw.contains("Example Fuel Station"))
        ReceiptLocalLearning.clear(ctx)
        assertNull(ReceiptLocalLearning.hint(ctx,"Example Fuel Station","total"))
    }
    @Test fun parsesTappableFieldsConservatively(){
        assertEquals("61.35",ReceiptLocalLearning.extract("total","TOTAL $61.35"))
        assertEquals("14.827",ReceiptLocalLearning.extract("gallons","14.827 GAL"))
        assertEquals("4.125",ReceiptLocalLearning.extract("price","4.125"))
        assertEquals("",ReceiptLocalLearning.extract("total","No number found"))
    }
}
