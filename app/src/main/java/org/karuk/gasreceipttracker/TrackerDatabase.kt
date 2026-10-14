package org.karuk.gasreceipttracker

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Private on-device SQLite database, indexed independently of the active form.
 * The existing preferences/request state remain compatible with earlier app builds
 * and older backups. No network or account is required.
 */
class TrackerDatabase(context: Context) : SQLiteOpenHelper(context, "fuel_records.db", null, 1) {
    data class SavedReceipt(
        val id: String, val requestId: String, val reference: String, val requestTitle: String,
        val station: String, val date: String, val vehicle: String, val gallons: String,
        val price: String, val total: String, val fuelType: String, val imageUri: String,
        val rawText: String, val payload: JSONObject
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE requests (
            id TEXT PRIMARY KEY NOT NULL, reference TEXT, title TEXT, kind TEXT, status TEXT,
            created_at TEXT, sent_at TEXT, payload TEXT NOT NULL, modified_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE trips (
            id TEXT PRIMARY KEY NOT NULL, request_id TEXT NOT NULL, vehicle TEXT, start_date TEXT,
            end_date TEXT, destination TEXT, purpose TEXT, payload TEXT NOT NULL
        )""")
        db.execSQL("""CREATE TABLE fuel_receipts (
            id TEXT PRIMARY KEY NOT NULL, request_id TEXT NOT NULL, station TEXT, receipt_date TEXT,
            vehicle TEXT, gallons TEXT, unit_price TEXT, total TEXT, fuel_type TEXT,
            image_uri TEXT, card_last4 TEXT, raw_ocr TEXT, payload TEXT NOT NULL,
            modified_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE documents (
            id TEXT PRIMARY KEY NOT NULL, request_id TEXT NOT NULL, kind TEXT NOT NULL,
            file_uri TEXT NOT NULL, created_at INTEGER NOT NULL, payload TEXT
        )""")
        db.execSQL("""CREATE TABLE activity_log (
            id TEXT PRIMARY KEY NOT NULL, request_id TEXT, action TEXT, detail TEXT,
            created_at INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE user_settings (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)""")
        db.execSQL("""CREATE TABLE app_state (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX index_receipt_date ON fuel_receipts(receipt_date)")
        db.execSQL("CREATE INDEX index_receipt_vehicle ON fuel_receipts(vehicle)")
        db.execSQL("CREATE INDEX index_receipt_request ON fuel_receipts(request_id)")
        db.execSQL("CREATE INDEX index_trip_request ON trips(request_id)")
        db.execSQL("CREATE INDEX index_doc_request ON documents(request_id)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("Database upgrade from $oldVersion to $newVersion requires a migration, not deletion.")
    }

    private fun values(vararg pairs: Pair<String, Any?>): ContentValues = ContentValues().apply {
        for ((k, v) in pairs) when(v) {
            null -> putNull(k)
            is Long -> put(k, v)
            is Int -> put(k, v)
            else -> put(k, v.toString())
        }
    }
    private fun put(db: SQLiteDatabase, table: String, values: ContentValues) {
        check(db.insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) {
            "Unable to save $table in the offline database."
        }
    }
    @Synchronized fun saveState(state: JSONObject, prefs: SharedPreferences) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val now = System.currentTimeMillis()
            put(db, "app_state", values("key" to "workflow", "value" to state.toString()))
            val all = state.getJSONArray("orders")
            for (i in 0 until all.length()) {
                val order = all.getJSONObject(i)
                val id = order.getString("id")
                put(db, "requests", values(
                    "id" to id, "reference" to order.optString("reference"),
                    "title" to order.optString("title"), "kind" to order.optString("kind"),
                    "status" to order.optString("status"), "created_at" to order.optString("createdAt"),
                    "sent_at" to order.optString("sentAt"), "payload" to order.toString(),
                    "modified_at" to now))
                db.delete("trips", "request_id=?", arrayOf(id))
                val trips = order.optJSONArray("trips") ?: JSONArray()
                for (j in 0 until trips.length()) {
                    val item = trips.getJSONObject(j)
                    put(db, "trips", values(
                        "id" to item.optString("id").ifBlank { "$id-trip-$j" }, "request_id" to id,
                        "vehicle" to item.optString("vehicle"), "start_date" to item.optString("startDate"),
                        "end_date" to item.optString("endDate"), "destination" to item.optString("destination"),
                        "purpose" to item.optString("purpose"), "payload" to item.toString()))
                }
                db.delete("fuel_receipts", "request_id=?", arrayOf(id))
                val receipts = order.optJSONArray("receipts") ?: JSONArray()
                for (j in 0 until receipts.length()) {
                    val item = receipts.getJSONObject(j)
                    put(db, "fuel_receipts", values(
                        "id" to item.optString("id").ifBlank { "$id-receipt-$j" }, "request_id" to id,
                        "station" to item.optString("station"), "receipt_date" to item.optString("date"),
                        "vehicle" to item.optString("vehicle"), "gallons" to item.optString("gallons"),
                        "unit_price" to item.optString("price"), "total" to item.optString("cost"),
                        "fuel_type" to item.optString("fuelType"), "image_uri" to item.optString("image"),
                        "card_last4" to item.optString("receiptCardLast4"),
                        "raw_ocr" to item.optString("ocrRawText"), "payload" to item.toString(),
                        "modified_at" to now))
                }
            }
            // Never persist more than the last four digits of a card.
            for (key in listOf("driver", "vehicle", "vehicles", "department", "cardLast4", "cardBrand",
                "myEmail", "emailTo", "emailCc", "emailSubject", "emailBody", "ccSelf")) {
                val value = prefs.all[key]?.toString() ?: continue
                put(db, "user_settings", values("key" to key, "value" to value))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    @Synchronized fun recoverState(): String? =
        readableDatabase.rawQuery("SELECT value FROM app_state WHERE key='workflow'", null)
            .use { if (it.moveToFirst()) it.getString(0) else null }

    @Synchronized fun receiptSearch(query: String, maxResults: Int = 60): List<SavedReceipt> {
        val term = "%" + query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        val sql = """SELECT f.id, f.request_id, q.reference, q.title, f.station, f.receipt_date,
            f.vehicle, f.gallons, f.unit_price, f.total, f.fuel_type, f.image_uri, f.raw_ocr, f.payload
            FROM fuel_receipts f LEFT JOIN requests q ON f.request_id=q.id
            WHERE (?='' OR f.station LIKE ? ESCAPE '!' OR f.receipt_date LIKE ? ESCAPE '!'
                 OR f.vehicle LIKE ? ESCAPE '!' OR f.total LIKE ? ESCAPE '!'
                 OR f.fuel_type LIKE ? ESCAPE '!' OR q.reference LIKE ? ESCAPE '!'
                 OR q.title LIKE ? ESCAPE '!')
            ORDER BY f.modified_at DESC, f.receipt_date DESC LIMIT ?"""
        val params = arrayOf(if(query.trim().isEmpty()) "" else query.trim(), term, term, term, term, term, term, term, maxResults.toString())
        val records = mutableListOf<SavedReceipt>()
        readableDatabase.rawQuery(sql, params).use { cursor ->
            while(cursor.moveToNext()) {
                records.add(SavedReceipt(
                    cursor.getString(0), cursor.getString(1), cursor.getString(2).orEmpty(),
                    cursor.getString(3).orEmpty(), cursor.getString(4).orEmpty(), cursor.getString(5).orEmpty(),
                    cursor.getString(6).orEmpty(), cursor.getString(7).orEmpty(), cursor.getString(8).orEmpty(),
                    cursor.getString(9).orEmpty(), cursor.getString(10).orEmpty(), cursor.getString(11).orEmpty(),
                    cursor.getString(12).orEmpty(), JSONObject(cursor.getString(13))
                ))
            }
        }
        return records
    }

    @Synchronized fun recordDocument(requestId: String, kind: String, uri: String) {
        val db=writableDatabase
        val key="$requestId:$kind:$uri"
        put(db, "documents", values("id" to key, "request_id" to requestId,
            "kind" to kind, "file_uri" to uri, "created_at" to System.currentTimeMillis(),
            "payload" to JSONObject().put("kind",kind).put("uri",uri).toString()))
    }

    @Synchronized fun log(requestId: String, action: String, detail: String = "") {
        put(writableDatabase, "activity_log", values(
            "id" to UUID.randomUUID().toString(), "request_id" to requestId,
            "action" to action, "detail" to detail, "created_at" to System.currentTimeMillis()))
    }

    @Synchronized fun snapshot(): JSONObject {
        val data=JSONObject().put("schema",1)
        for(table in listOf("requests","trips","fuel_receipts","documents","activity_log","user_settings","app_state")) {
            val rows=JSONArray()
            readableDatabase.rawQuery("SELECT * FROM $table",null).use{cursor->
                while(cursor.moveToNext()){
                    val entry=JSONObject()
                    for(i in 0 until cursor.columnCount) {
                        if(cursor.isNull(i))entry.put(cursor.getColumnName(i),JSONObject.NULL)
                        else if(cursor.getType(i)==android.database.Cursor.FIELD_TYPE_INTEGER)entry.put(cursor.getColumnName(i),cursor.getLong(i))
                        else entry.put(cursor.getColumnName(i),cursor.getString(i))
                    }
                    rows.put(entry)
                }
            }
            data.put(table,rows)
        }
        return data
    }

    @Synchronized fun restoreSnapshot(snapshot: JSONObject) {
        require(snapshot.optInt("schema") == 1) { "Unsupported database backup schema" }
        val db=writableDatabase
        db.beginTransaction()
        try {
            for(table in listOf("requests","trips","fuel_receipts","documents","activity_log","user_settings","app_state")) {
                db.delete(table, null, null)
                val items=snapshot.optJSONArray(table) ?: JSONArray()
                for(i in 0 until items.length()) {
                    val row=items.getJSONObject(i)
                    val record=ContentValues()
                    val keys=row.keys()
                    while(keys.hasNext()) {
                        val key=keys.next();val v=row.opt(key)
                        when (v) {
                            JSONObject.NULL, null -> record.putNull(key)
                            is Number -> record.put(key, v.toLong())
                            else -> record.put(key, v.toString())
                        }
                    }
                    put(db, table, record)
                }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
