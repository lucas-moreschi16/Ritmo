package br.com.ritmo.nativo

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Dados separados por profile_id: nunca importar em outro usuário automaticamente. */
class RitmoDb(ctx: Context) : SQLiteOpenHelper(ctx, "ritmo_native_v2.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE profiles(id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at TEXT NOT NULL)")
        db.execSQL("CREATE TABLE records(profile_id TEXT NOT NULL, kind TEXT NOT NULL, external_id TEXT NOT NULL, date TEXT NOT NULL, value REAL, details TEXT NOT NULL DEFAULT '{}', source TEXT NOT NULL, PRIMARY KEY(profile_id,kind,external_id))")
        db.execSQL("CREATE INDEX records_dates ON records(profile_id, date, kind)")
        db.execSQL("CREATE TABLE settings(profile_id TEXT NOT NULL, key TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY(profile_id,key))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun createProfile(name: String): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.execSQL("INSERT INTO profiles VALUES(?,?,?)", arrayOf(id, name.trim().ifEmpty {"Usuário"}, java.time.Instant.now().toString()))
        return id
    }
    fun profiles(): List<Pair<String,String>> {
        val list= mutableListOf<Pair<String,String>>()
        readableDatabase.rawQuery("SELECT id,name FROM profiles ORDER BY created_at",null).use { c ->
            while(c.moveToNext()) list.add(c.getString(0) to c.getString(1))
        }
        return list
    }
    fun profileName(id:String):String = profiles().firstOrNull{it.first==id}?.second ?: "Usuário"
    fun setting(profile:String, key:String, default:String="") : String {
        readableDatabase.rawQuery("SELECT value FROM settings WHERE profile_id=? AND key=?", arrayOf(profile,key)).use { c ->
            return if (c.moveToFirst()) c.getString(0) else default
        }
    }
    fun setSetting(profile:String,key:String,value:String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO settings VALUES(?,?,?)",arrayOf(profile,key,value))
    }
    fun add(profile:String, kind:String,date:String,value:Double?,details:JSONObject=JSONObject(), source:String="manual", id:String=UUID.randomUUID().toString()):Boolean {
        require(profile.isNotBlank() && kind in setOf("weight","fast","medication","measure","habit","journal"))
        val validDate= java.time.LocalDate.parse(date).toString()
        val row = android.content.ContentValues().apply {
            put("profile_id",profile); put("kind",kind); put("external_id",id)
            put("date",validDate); if(value!=null)put("value",value) else putNull("value")
            put("details",details.toString());put("source",source)
        }
        // IGNORE impede reimportação duplicada dos IDs de origem.
        return writableDatabase.insertWithOnConflict("records",null,row,SQLiteDatabase.CONFLICT_IGNORE)!=-1L
    }
    fun records(profile:String,kind:String?=null): List<JSONObject> {
        val out=mutableListOf<JSONObject>()
        val args=if(kind==null) arrayOf(profile) else arrayOf(profile,kind)
        val sql="SELECT kind,external_id,date,value,details,source FROM records WHERE profile_id=?"+(if(kind!=null)" AND kind=?" else "")+" ORDER BY date ASC, external_id ASC"
        readableDatabase.rawQuery(sql,args).use { c -> while (c.moveToNext()) {
            val x=JSONObject().put("kind",c.getString(0)).put("id",c.getString(1)).put("date",c.getString(2))
            if (!c.isNull(3)) x.put("value",c.getDouble(3))
            x.put("details",JSONObject(c.getString(4))).put("source",c.getString(5));out.add(x)
        }}
        return out
    }
    fun latest(profile:String,kind:String) = records(profile,kind).lastOrNull()
    fun exportJson(profile:String): JSONObject {
        val a=JSONArray()
        records(profile).forEach { a.put(it) }
        return JSONObject()
            .put("format","ritmo-native-v2")
            .put("exportedAt",java.time.Instant.now().toString())
            .put("profileName",profileName(profile))
            .put("records",a)
    }
    fun importJson(profile:String,raw:String): Pair<Int,Int> {
        val root=JSONObject(raw)
        val records = when {
            root.optJSONArray("records")!=null -> root.getJSONArray("records")
            root.optJSONObject("data")?.optJSONArray("weight")!=null -> {
                val data=root.getJSONObject("data")
                val arr=JSONArray()
                for(i in 0 until data.getJSONArray("weight").length()) {
                    val w=data.getJSONArray("weight").getJSONObject(i)
                    arr.put(JSONObject().put("kind","weight").put("id",w.optString("id","legacy-$i"))
                        .put("date",w.getString("date")).put("value",w.getDouble("value"))
                        .put("source","ritmo-v1").put("details",JSONObject().put("note",w.optString("note"))))
                }
                for (key in listOf("fasting","medication","measures","habits","journal")) {
                    val old=data.optJSONArray(key)?:continue
                    val kind= when(key) {"fasting"->"fast";"measures"->"measure";"habits"->"habit"; else->key}
                    for(i in 0 until old.length()) {
                        val item=old.optJSONObject(i)?:continue
                        val ts=item.optString("start",item.optString("startedAt"))
                        val date=item.optString("date").ifBlank { ts.take(10) }
                        if(date.isBlank()) continue
                        val rec=JSONObject().put("kind",kind).put("id",item.optString("id","v1-$key-$i"))
                            .put("date",date).put("details",item).put("source","ritmo-v1")
                        if(kind=="weight")rec.put("value",item.optDouble("value"))
                        arr.put(rec)
                    }
                }
                arr
            }
            else -> throw IllegalArgumentException("JSON incompatível: esperado formato RITMO nativo ou RITMO v1")
        }
        var imported=0;var ignored=0
        writableDatabase.beginTransaction()
        try {
            for(i in 0 until records.length()) {
                val r=records.optJSONObject(i)?:continue
                val type=r.optString("kind")
                val date=r.optString("date")
                if(type.isBlank()||date.isBlank()) { ignored++; continue }
                val value=if(r.has("value")&&!r.isNull("value"))r.optDouble("value") else null
                val ok=try { add(profile,type,date,value,r.optJSONObject("details")?:JSONObject(),r.optString("source","import"),r.optString("id","import-$i")) } catch (_:Exception){false}
                if(ok) imported++ else ignored++
            }
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
        return imported to ignored
    }
}
