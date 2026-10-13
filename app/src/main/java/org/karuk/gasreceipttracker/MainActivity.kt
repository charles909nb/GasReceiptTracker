package org.karuk.gasreceipttracker

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("usage_data", MODE_PRIVATE) }
    private val trips get() = JSONArray(prefs.getString("trips", "[]"))
    private val now get() = SimpleDateFormat("MMM d, yyyy h:mm a", Locale.US).format(Date())
    private lateinit var page: LinearLayout
    private fun save(items: JSONArray) { prefs.edit().putString("trips", items.toString()).commit() }
    private fun field(label: String, initial: String = ""): EditText {
        val e = EditText(this); e.hint = label; e.setSingleLine(true); e.setText(initial)
        page.addView(e, LinearLayout.LayoutParams(-1, -2)); return e
    }
    private fun button(label: String, action: () -> Unit) {
        val b = Button(this); b.text = label; page.addView(b); b.setOnClickListener { action() }
    }
    private fun title(label: String) {
        val t = TextView(this); t.text = label; t.textSize = 22f; t.setTypeface(null, Typeface.BOLD); t.setPadding(0, 18, 0, 12); page.addView(t)
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); home() }
    private fun layout() {
        val scroll = ScrollView(this); page = LinearLayout(this); page.orientation = LinearLayout.VERTICAL
        page.setPadding(24, 20, 24, 24); scroll.addView(page); setContentView(scroll)
    }
    private fun home() {
        layout(); title("Vehicle Usage Tracker 1.1")
        val driver = field("Driver name", prefs.getString("driver", "") ?: "")
        val vehicle = field("Vehicle number", prefs.getString("vehicle", "") ?: "")
        val department = field("Department / program", prefs.getString("department", "") ?: "")
        button("Save driver and vehicle") {
            prefs.edit().putString("driver", driver.text.toString()).putString("vehicle", vehicle.text.toString())
                .putString("department", department.text.toString()).commit()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        val list = trips
        var active = -1
        for (i in 0 until list.length()) if (!list.getJSONObject(i).has("endOdometer")) active = i
        if (active >= 0) {
            val item = list.getJSONObject(active)
            title("Trip in progress")
            TextView(this).also { it.text = "${item.optString("destination")} — ${item.optString("purpose")}\nStart: ${item.optString("startOdometer")}"; page.addView(it) }
            val ending = field("Ending odometer")
            button("End trip") {
                if (ending.text.toString().toDoubleOrNull() == null) { ending.error = "Enter a valid odometer"; return@button }
                if (ending.text.toString().toDouble() < item.optDouble("startOdometer")) { ending.error = "Must be at least starting odometer"; return@button }
                item.put("endOdometer", ending.text.toString()).put("endDate", now)
                save(list); home()
            }
        } else {
            title("Start a trip")
            val start = field("Starting odometer")
            val destination = field("Destination")
            val purpose = field("Purpose of travel")
            button("Start trip") {
                if (start.text.toString().toDoubleOrNull() == null || destination.text.isBlank() || purpose.text.isBlank()) {
                    Toast.makeText(this, "Enter odometer, destination and purpose", Toast.LENGTH_LONG).show(); return@button
                }
                prefs.edit().putString("driver", driver.text.toString()).putString("vehicle", vehicle.text.toString())
                    .putString("department", department.text.toString()).commit()
                val entry = JSONObject().put("startDate", now).put("startOdometer", start.text.toString())
                    .put("destination", destination.text.toString()).put("purpose", purpose.text.toString())
                    .put("driver", driver.text.toString()).put("vehicle", vehicle.text.toString())
                    .put("department", department.text.toString())
                list.put(entry); save(list); home()
            }
        }
        title("Current usage sheet (${list.length()} trips)")
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            val view = TextView(this)
            view.text = "${i + 1}. ${item.optString("destination")} — ${item.optString("purpose")}\n${item.optString("startDate")} | ${item.optString("startOdometer")} → ${item.optString("endOdometer", "IN PROGRESS")}"
            view.setPadding(0, 8, 0, 8); page.addView(view)
        }
        button("Share current usage sheet (text)") {
            val text = StringBuilder("VEHICLE USAGE SHEET\nDriver: ${driver.text}\nVehicle: ${vehicle.text}\nDepartment: ${department.text}\n\n")
            for (i in 0 until list.length()) {
                val j = list.getJSONObject(i)
                text.append("${i + 1}. ${j.optString("startDate")} | ${j.optString("destination")} | ${j.optString("purpose")} | ${j.optString("startOdometer")} to ${j.optString("endOdometer", "Open")}\n")
            }
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text.toString()), "Share usage sheet"))
        }
        button("Archive sheet and start new") {
            if (list.length() == 0) return@button
            val archive = JSONArray(prefs.getString("archives", "[]"))
            archive.put(JSONObject().put("archivedAt", now).put("trips", list))
            prefs.edit().putString("archives", archive.toString()).putString("trips", "[]").commit()
            home()
        }
        TextView(this).also { it.text = "Draft prototype: usage entries save locally. PDF, receipt attachment, fueling totals, and Excel export are not implemented yet."; page.addView(it) }
    }
}
