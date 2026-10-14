package org.karuk.gasreceipttracker;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.*;

/** SQLite is authoritative. The preference-shaped API preserves existing workflows.
 * Legacy preferences migrate atomically once and remain untouched as a safety copy.
 * Searchable rows are rebuilt in the same transaction as request edits.
 */
public final class LedgerStore {
    private static Store instance;
    private LedgerStore() {}
    private static synchronized Store store(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }
    public static SharedPreferences prefs(Context c) { return store(c); }
    public static synchronized void closeForTests() {
        if (instance != null) { instance.helper.close(); instance = null; }
    }
    public static JSONArray search(Context c, String query) { return store(c).search(query); }
    public static JSONObject counts(Context c) { return store(c).counts(); }
    public static String rememberScan(Context c, JSONObject scan) {
        String id = "scan/" + UUID.randomUUID(); store(c).remember(id, "scan", scan); return id;
    }
    public static void rememberDocument(Context c, String id, JSONObject record) { store(c).remember("document/"+id,"document",record); }
    public static String historyJson(Context c) { return store(c).history().toString(); }
    public static final String RESTORE_HISTORY = "__restore_database_history";

    private static final class Helper extends SQLiteOpenHelper {
        Helper(Context c) { super(c, "gas_receipt_tracker.db", null, 1); setWriteAheadLoggingEnabled(true); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE settings (key TEXT PRIMARY KEY, type TEXT NOT NULL, value TEXT NOT NULL)");
            db.execSQL("CREATE TABLE migration (id TEXT PRIMARY KEY, completed_at INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE records (record_key TEXT PRIMARY KEY, kind TEXT NOT NULL, request_id TEXT, pr_number TEXT, receipt_date TEXT, pr_date TEXT, vehicle TEXT, station TEXT, amount TEXT, search_text TEXT NOT NULL, payload TEXT NOT NULL, updated_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX records_kind ON records(kind, updated_at)");
            db.execSQL("CREATE INDEX records_request ON records(request_id)");
            db.execSQL("CREATE INDEX records_vehicle ON records(vehicle, receipt_date)");
        }
        @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion) {
            throw new IllegalStateException("A non-destructive database migration is required. Existing data has not been deleted.");
        }
    }
    private static final class Store implements SharedPreferences {
        final Helper helper;
        final Set<OnSharedPreferenceChangeListener> listeners = Collections.newSetFromMap(new WeakHashMap<OnSharedPreferenceChangeListener,Boolean>());
        Store(Context c) {
            helper = new Helper(c); SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                boolean migrated;
                try(Cursor cur=db.rawQuery("SELECT id FROM migration WHERE id='usage_data_v1'",null)){migrated=cur.moveToFirst();}
                if(!migrated) {
                    Map<String,?> previous=c.getSharedPreferences("usage_data",Context.MODE_PRIVATE).getAll();
                    for(Map.Entry<String,?> e:previous.entrySet()) putValue(db,e.getKey(),e.getValue());
                    rebuild(db);
                    db.execSQL("INSERT INTO migration(id,completed_at) VALUES('usage_data_v1',?)",new Object[]{System.currentTimeMillis()});
                }
                db.setTransactionSuccessful();
            } catch(Exception e) { throw new IllegalStateException("Could not migrate saved records; the original preference files remain intact", e); } finally { db.endTransaction(); }
        }
        private static void putValue(SQLiteDatabase db,String key,Object value) {
            if(value==null){db.delete("settings","key=?",new String[]{key});return;}
            String type=value instanceof Boolean?"boolean":value instanceof Integer?"int":value instanceof Long?"long":value instanceof Float?"float":value instanceof Set?"set":"string";
            String text=value instanceof Set?new JSONArray((Set<?>)value).toString():value.toString();
            ContentValues v=new ContentValues();v.put("key",key);v.put("type",type);v.put("value",text);
            if(db.insertWithOnConflict("settings",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("Database write failed");
        }
        private static Object decode(String type,String value) {
            switch(type){
                case "boolean":return Boolean.valueOf(value);case "int":return Integer.valueOf(value);case "long":return Long.valueOf(value);case "float":return Float.valueOf(value);
                case "set":try{JSONArray a=new JSONArray(value);Set<String>s=new HashSet<>();for(int i=0;i<a.length();i++)s.add(a.getString(i));return s;}catch(Exception e){throw new IllegalStateException(e);}
                default:return value;
            }
        }
        @Override public synchronized Map<String,?> getAll() {
            Map<String,Object> out=new HashMap<>();
            try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT key,type,value FROM settings",null)){
                while(c.moveToNext())out.put(c.getString(0),decode(c.getString(1),c.getString(2)));
            }return out;
        }
        private synchronized Object value(String key) {
            try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT type,value FROM settings WHERE key=?",new String[]{key})){
                return c.moveToFirst()?decode(c.getString(0),c.getString(1)):null;
            }
        }
        @Override public String getString(String key,String def){Object v=value(key);return v==null?def:(String)v;}
        @SuppressWarnings("unchecked") @Override public Set<String> getStringSet(String key,Set<String> def){Object v=value(key);return v==null?def:new HashSet<>((Set<String>)v);}
        @Override public int getInt(String key,int def){Object v=value(key);return v==null?def:(Integer)v;}
        @Override public long getLong(String key,long def){Object v=value(key);return v==null?def:(Long)v;}
        @Override public float getFloat(String key,float def){Object v=value(key);return v==null?def:(Float)v;}
        @Override public boolean getBoolean(String key,boolean def){Object v=value(key);return v==null?def:(Boolean)v;}
        @Override public boolean contains(String key){return value(key)!=null;}
        @Override public Editor edit(){return new Edit(this);}
        @Override public synchronized void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listeners.add(l);}
        @Override public synchronized void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l){listeners.remove(l);}
        synchronized boolean commit(Edit edit) {
            SQLiteDatabase db=helper.getWritableDatabase();db.beginTransaction();
            try{
                if(edit.clear){db.delete("settings",null,null);db.delete("records",null,null);}
                for(Map.Entry<String,Object> e:edit.changes.entrySet())if(!RESTORE_HISTORY.equals(e.getKey()))putValue(db,e.getKey(),e.getValue());
                if(edit.changes.containsKey(RESTORE_HISTORY)){
                    JSONArray a=new JSONArray(String.valueOf(edit.changes.get(RESTORE_HISTORY)));
                    db.delete("records","kind IN ('scan','document')",null);
                    for(int i=0;i<a.length();i++){
                        JSONObject r=a.getJSONObject(i);String k=r.getString("kind");
                        if(!k.equals("scan")&&!k.equals("document"))throw new IllegalArgumentException("Invalid history record");
                        index(db,r.getString("key"),k,r.getJSONObject("payload"),r.optString("requestId"),r.optString("reference"),r.optLong("updatedAt",System.currentTimeMillis()));
                    }
                }
                if(edit.clear||edit.changes.keySet().stream().anyMatch(k->Arrays.asList("requestWorkflowV1","trips","receipts","archives").contains(k)))rebuild(db);
                db.setTransactionSuccessful();
            }catch(Exception e){android.util.Log.e("LedgerStore","Database transaction rolled back",e);return false;}
            finally{db.endTransaction();}
            for(OnSharedPreferenceChangeListener l:new ArrayList<>(listeners))for(String key:edit.changes.keySet())l.onSharedPreferenceChanged(this,key);
            return true;
        }
        static String setting(SQLiteDatabase db,String key,String def){try(Cursor c=db.rawQuery("SELECT value FROM settings WHERE key=?",new String[]{key})){return c.moveToFirst()?c.getString(0):def;}}
        static void rebuild(SQLiteDatabase db) throws Exception {
            db.delete("records","kind IN ('request','receipt','trip')",null);
            JSONObject state=new JSONObject(setting(db,"requestWorkflowV1","{}"));JSONArray orders=state.optJSONArray("orders");
            Set<String> ids=new HashSet<>();
            if(orders!=null)for(int i=0;i<orders.length();i++){
                JSONObject a=new JSONObject(orders.getJSONObject(i).toString());String id=a.getString("id");ids.add(id);
                if(id.equals(state.optString("activeId"))){a.put("receipts",new JSONArray(setting(db,"receipts","[]")));a.put("trips",new JSONArray(setting(db,"trips","[]")));}
                indexRequest(db,a,id,a.optString("reference"));
            }
            if(orders==null){JSONObject a=new JSONObject().put("trips",new JSONArray(setting(db,"trips","[]"))).put("receipts",new JSONArray(setting(db,"receipts","[]"))).put("status","draft");indexRequest(db,a,"legacy-active","Current request");}
            JSONArray archives=new JSONArray(setting(db,"archives","[]"));
            for(int i=0;i<archives.length();i++){JSONObject a=archives.getJSONObject(i);String id=a.optString("id","legacy-archive-"+i);if(!ids.contains(id))indexRequest(db,a,id,a.optString("reference","Archive "+(i+1)));}
        }
        static void indexRequest(SQLiteDatabase db,JSONObject a,String id,String ref) throws Exception {
            long time=a.optLong("savedAtEpoch",System.currentTimeMillis());index(db,"request/"+id,"request",a,id,ref,time);
            for(String kind:new String[]{"receipt","trip"}){
                JSONArray values=a.optJSONArray(kind.equals("receipt")?"receipts":"trips");if(values==null)continue;
                for(int i=0;i<values.length();i++){
                    JSONObject v=new JSONObject(values.getJSONObject(i).toString());v.put("requestDate",a.optString("requestDate"));v.put("requestTitle",a.optString("title"));
                    index(db,kind+"/"+id+"/"+i,kind,v,id,ref,time);
                }
            }
        }
        static void index(SQLiteDatabase db,String key,String kind,JSONObject p,String id,String ref,long time) {
            ContentValues v=new ContentValues();v.put("record_key",key);v.put("kind",kind);v.put("request_id",id);v.put("pr_number",ref);
            v.put("receipt_date",p.optString("receiptDate",p.optString("date")));v.put("pr_date",p.optString("requestDate"));
            v.put("vehicle",p.optString("vehicle"));v.put("station",p.optString("station"));v.put("amount",p.optString("cost"));
            v.put("payload",p.toString());v.put("search_text",(ref+" "+id+" "+p.toString()).toLowerCase(Locale.US));v.put("updated_at",time);
            if(db.insertWithOnConflict("records",null,v,SQLiteDatabase.CONFLICT_REPLACE)<0)throw new IllegalStateException("Record write failed");
        }
        synchronized void remember(String key,String kind,JSONObject payload){
            SQLiteDatabase db=helper.getWritableDatabase();index(db,key,kind,payload,payload.optString("requestId"),payload.optString("reference"),System.currentTimeMillis());
        }
        synchronized JSONArray search(String query){
            String q=query.trim().toLowerCase(Locale.US).replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
            JSONArray out=new JSONArray();
            try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT record_key,kind,request_id,pr_number,payload,updated_at FROM records WHERE search_text LIKE ? ESCAPE '\\' ORDER BY updated_at DESC,record_key LIMIT 100",new String[]{"%"+q+"%"})){
                while(c.moveToNext())out.put(new JSONObject().put("key",c.getString(0)).put("kind",c.getString(1)).put("requestId",c.getString(2)).put("reference",c.getString(3)).put("payload",new JSONObject(c.getString(4))).put("updatedAt",c.getLong(5)));
            }catch(Exception e){throw new IllegalStateException("Could not search saved records",e);}return out;
        }
        synchronized JSONArray history(){
            JSONArray a=new JSONArray();try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT record_key,kind,request_id,pr_number,payload,updated_at FROM records WHERE kind IN ('scan','document') ORDER BY updated_at",null)){
                while(c.moveToNext())a.put(new JSONObject().put("key",c.getString(0)).put("kind",c.getString(1)).put("requestId",c.getString(2)).put("reference",c.getString(3)).put("payload",new JSONObject(c.getString(4))).put("updatedAt",c.getLong(5)));
            }catch(Exception e){throw new IllegalStateException(e);}return a;
        }
        synchronized JSONObject counts(){JSONObject p=new JSONObject();try(Cursor c=helper.getReadableDatabase().rawQuery("SELECT kind,COUNT(*) FROM records GROUP BY kind",null)){
            while(c.moveToNext())p.put(c.getString(0),c.getInt(1));
        }catch(Exception e){throw new IllegalStateException(e);}return p;}
    }
    private static final class Edit implements SharedPreferences.Editor {
        final Store store;final Map<String,Object>changes=new LinkedHashMap<>();boolean clear;
        Edit(Store s){store=s;}
        @Override public Editor putString(String k,String v){changes.put(k,v);return this;}
        @Override public Editor putStringSet(String k,Set<String>v){changes.put(k,v==null?null:new HashSet<>(v));return this;}
        @Override public Editor putInt(String k,int v){changes.put(k,v);return this;}
        @Override public Editor putLong(String k,long v){changes.put(k,v);return this;}
        @Override public Editor putFloat(String k,float v){changes.put(k,v);return this;}
        @Override public Editor putBoolean(String k,boolean v){changes.put(k,v);return this;}
        @Override public Editor remove(String k){changes.put(k,null);return this;}
        @Override public Editor clear(){clear=true;return this;}
        @Override public boolean commit(){return store.commit(this);}
        @Override public void apply(){if(!commit())throw new IllegalStateException("The database could not save this change. Your previous saved records are intact.");}
    }
}
