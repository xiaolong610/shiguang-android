package app.shiguang.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class LibraryStore(context: Context,databaseName:String="shiguang.db"): SQLiteOpenHelper(context,databaseName,null,3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sources (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE favorites (id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE progress (id TEXT PRIMARY KEY, payload TEXT NOT NULL, line INTEGER NOT NULL, chapter INTEGER NOT NULL, offset INTEGER NOT NULL, updated INTEGER NOT NULL, chapter_title TEXT NOT NULL DEFAULT '')")
        createSubscriptions(db)
        createFollows(db)
    }
    private fun createSubscriptions(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS subscriptions (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS subscription_sources (subscription_id TEXT NOT NULL, source_id TEXT NOT NULL, baseline TEXT NOT NULL, removed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(subscription_id,source_id))")
    }
    private fun createFollows(db:SQLiteDatabase){db.execSQL("CREATE TABLE IF NOT EXISTS follow_updates (id TEXT PRIMARY KEY,payload TEXT NOT NULL)")}
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {if(oldVersion<2)createSubscriptions(db);if(oldVersion<3){db.execSQL("ALTER TABLE progress ADD COLUMN chapter_title TEXT NOT NULL DEFAULT ''");createFollows(db)}}
    fun followStates():Map<String,FollowState> = readableDatabase.rawQuery("SELECT payload FROM follow_updates",null).use{c->buildMap{while(c.moveToNext())runCatching{FollowState.parse(JSONObject(c.getString(0)))}.getOrNull()?.let{put(it.entryKey,it)}}}
    fun followState(key:String):FollowState? = readableDatabase.rawQuery("SELECT payload FROM follow_updates WHERE id=?",arrayOf(key)).use{c->if(c.moveToFirst())FollowState.parse(JSONObject(c.getString(0)))else null}
    fun putFollowState(state:FollowState){writableDatabase.insertWithOnConflict("follow_updates",null,ContentValues().apply{put("id",state.entryKey);put("payload",state.json().toString())},SQLiteDatabase.CONFLICT_REPLACE)}
    fun observeDetail(detail:Detail,line:Int=0){if(detail.lines.isNotEmpty()&&favorites().any{it.key==detail.entry.key})transaction{val old=followState(detail.entry.key);val selected=detail.lines.indexOfFirst{it.name==old?.lineName}.takeIf{it>=0}?:line;putFollowState(FollowState.observe(old,detail,selected,System.currentTimeMillis()))}}
    fun sources(): List<Source> = readableDatabase.rawQuery("SELECT payload FROM sources ORDER BY rowid",null).use { c ->
        buildList { while(c.moveToNext()) runCatching { SourceCodec.parse(c.getString(0)).first() }.getOrNull()?.let { add(it) } }
    }
    fun putSource(s: Source) { writableDatabase.insertWithOnConflict("sources",null,ContentValues().apply { put("id",s.id);put("payload",s.json().toString()) },SQLiteDatabase.CONFLICT_REPLACE) }
    fun putSources(sources: List<Source>) {
        val db=writableDatabase;db.beginTransaction()
        try { sources.forEach { putSource(it) };db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    fun deleteSource(id: String) {val db=writableDatabase;db.beginTransaction();try {db.delete("sources","id=?",arrayOf(id));db.execSQL("UPDATE subscription_sources SET removed=1 WHERE source_id=?",arrayOf(id));db.setTransactionSuccessful()}finally{db.endTransaction()} }
    fun subscriptions():List<SourceSubscription> = readableDatabase.rawQuery("SELECT payload FROM subscriptions ORDER BY rowid",null).use{c->buildList{while(c.moveToNext())add(SourceSubscription.parse(JSONObject(c.getString(0))))}}
    fun putSubscription(s:SourceSubscription){writableDatabase.insertWithOnConflict("subscriptions",null,ContentValues().apply{put("id",s.id);put("payload",s.json().toString())},SQLiteDatabase.CONFLICT_REPLACE)}
    fun subscriptionLinks(id:String):List<SubscriptionLink> = readableDatabase.rawQuery("SELECT source_id,baseline,removed FROM subscription_sources WHERE subscription_id=?",arrayOf(id)).use{c->buildList{while(c.moveToNext())add(SubscriptionLink(c.getString(0),c.getString(1),c.getInt(2)!=0))}}
    fun sourceOwner(id:String):String?=readableDatabase.rawQuery("SELECT subscription_id FROM subscription_sources WHERE source_id=? LIMIT 1",arrayOf(id)).use{if(it.moveToFirst())it.getString(0) else null}
    fun putSubscriptionLink(id:String,link:SubscriptionLink){writableDatabase.insertWithOnConflict("subscription_sources",null,ContentValues().apply{put("subscription_id",id);put("source_id",link.sourceId);put("baseline",link.baseline);put("removed",if(link.removed)1 else 0)},SQLiteDatabase.CONFLICT_REPLACE)}
    fun deleteSubscription(id:String){val db=writableDatabase;db.beginTransaction();try{db.delete("subscription_sources","subscription_id=?",arrayOf(id));db.delete("subscriptions","id=?",arrayOf(id));db.setTransactionSuccessful()}finally{db.endTransaction()}}
    fun <T> transaction(action:()->T):T {val db=writableDatabase;db.beginTransaction();try{val value=action();db.setTransactionSuccessful();return value}finally{db.endTransaction()}}
    fun favorites(): List<Entry> = readableDatabase.rawQuery("SELECT payload FROM favorites ORDER BY updated DESC",null).use { c ->
        buildList { while(c.moveToNext()) runCatching { Entry.parse(JSONObject(c.getString(0))) }.getOrNull()?.let { add(it) } }
    }
    fun preserveCategory(source:Source){
        for(table in listOf("favorites","progress")){
            val updates=readableDatabase.rawQuery("SELECT id,payload FROM $table",null).use{c->buildList{while(c.moveToNext())runCatching{Entry.parse(JSONObject(c.getString(1)))}.getOrNull()?.takeIf{it.sourceId==source.id}?.let{add(c.getString(0) to it.copy(category=source.category).json().toString())}}}
            updates.forEach{(id,payload)->writableDatabase.update(table,ContentValues().apply{put("payload",payload)},"id=?",arrayOf(id))}
        }
    }
    fun favorite(e: Entry, add: Boolean) {
        if(!add) { transaction{writableDatabase.delete("favorites","id=?",arrayOf(e.key));writableDatabase.delete("follow_updates","id=?",arrayOf(e.key))};return }
        writableDatabase.insertWithOnConflict("favorites",null,ContentValues().apply { put("id",e.key);put("payload",e.json().toString());put("updated",System.currentTimeMillis()) },SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun progress(): List<Progress> = readableDatabase.rawQuery("SELECT payload,line,chapter,offset,updated,chapter_title FROM progress ORDER BY updated DESC",null).use { c ->
        buildList { while(c.moveToNext()) runCatching { Progress(Entry.parse(JSONObject(c.getString(0))),c.getInt(1),c.getInt(2),c.getLong(3),c.getLong(4),c.getString(5)) }.getOrNull()?.let { add(it) } }
    }
    fun progressFor(key:String):Progress? = readableDatabase.rawQuery("SELECT payload,line,chapter,offset,updated,chapter_title FROM progress WHERE id=?",arrayOf(key)).use{c->if(c.moveToFirst())Progress(Entry.parse(JSONObject(c.getString(0))),c.getInt(1),c.getInt(2),c.getLong(3),c.getLong(4),c.getString(5))else null}
    fun save(e: Entry,line: Int,chapter: Int,offset: Long,chapterTitle:String?=null) {
        val title=chapterTitle?:progressFor(e.key)?.takeIf{it.line==line&&it.chapter==chapter}?.chapterTitle.orEmpty()
        writableDatabase.insertWithOnConflict("progress",null,ContentValues().apply {
            put("id",e.key);put("payload",e.json().toString());put("line",line);put("chapter",chapter);put("offset",offset.coerceAtLeast(0));put("updated",System.currentTimeMillis());put("chapter_title",title)
        },SQLiteDatabase.CONFLICT_REPLACE)
    }
}
