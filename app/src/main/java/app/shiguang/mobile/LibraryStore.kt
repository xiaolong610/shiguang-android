package app.shiguang.mobile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

class LibraryStore(context: Context,databaseName:String="shiguang.db"): SQLiteOpenHelper(context,databaseName,null,4) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sources (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE favorites (id TEXT PRIMARY KEY, payload TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE progress (id TEXT PRIMARY KEY, payload TEXT NOT NULL, line INTEGER NOT NULL, chapter INTEGER NOT NULL, offset INTEGER NOT NULL, updated INTEGER NOT NULL, chapter_title TEXT NOT NULL DEFAULT '')")
        createSubscriptions(db)
        createFollows(db)
        createSearch(db)
    }
    private fun createSubscriptions(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS subscriptions (id TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS subscription_sources (subscription_id TEXT NOT NULL, source_id TEXT NOT NULL, baseline TEXT NOT NULL, removed INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(subscription_id,source_id))")
    }
    private fun createFollows(db:SQLiteDatabase){db.execSQL("CREATE TABLE IF NOT EXISTS follow_updates (id TEXT PRIMARY KEY,payload TEXT NOT NULL)")}
    private fun createSearch(db:SQLiteDatabase){
        db.execSQL("CREATE TABLE IF NOT EXISTS search_catalog (id TEXT PRIMARY KEY,source_id TEXT NOT NULL,category TEXT NOT NULL,fingerprint TEXT NOT NULL,payload TEXT NOT NULL,seen INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS search_catalog_category_seen ON search_catalog(category,seen)")
        db.execSQL("CREATE TABLE IF NOT EXISTS source_health (id TEXT PRIMARY KEY,fingerprint TEXT NOT NULL,latency INTEGER NOT NULL,failures INTEGER NOT NULL,checked INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {if(oldVersion<2)createSubscriptions(db);if(oldVersion<3){db.execSQL("ALTER TABLE progress ADD COLUMN chapter_title TEXT NOT NULL DEFAULT ''");createFollows(db)};if(oldVersion<4)createSearch(db)}
    fun searchCatalog(category:ContentCategory,sources:List<Source>,now:Long=System.currentTimeMillis()):List<Entry>{
        val fingerprints=sources.filter{it.enabled}.associate{it.id to SearchPlanner.fingerprint(it)}
        return readableDatabase.rawQuery("SELECT source_id,fingerprint,payload FROM search_catalog WHERE category=? AND seen>? ORDER BY seen DESC LIMIT 8000",arrayOf(category.name,(now-7*24*60*60*1000L).toString())).use{c->buildList{while(c.moveToNext())if(fingerprints[c.getString(0)]==c.getString(1))runCatching{Entry.parse(JSONObject(c.getString(2)))}.getOrNull()?.let{add(it)}}}
    }
    fun sourceHealth():Map<String,SourceHealth> = readableDatabase.rawQuery("SELECT id,latency,failures,checked,fingerprint FROM source_health",null).use{c->buildMap{while(c.moveToNext())put(c.getString(0),SourceHealth(c.getString(0),c.getLong(1),c.getInt(2),c.getLong(3),c.getString(4)))}}
    fun recordSearch(outcome:SearchOutcome,now:Long=System.currentTimeMillis())=transaction{
        val fingerprint=SearchPlanner.fingerprint(outcome.source)
        val old=readableDatabase.rawQuery("SELECT latency,failures,fingerprint FROM source_health WHERE id=?",arrayOf(outcome.source.id)).use{c->if(c.moveToFirst()&&c.getString(2)==fingerprint)c.getLong(0) to c.getInt(1)else null}
        val failed=outcome.error!=null
        val latency=if(failed)old?.first?:outcome.elapsedMs else if(old!=null)(old.first*3+outcome.elapsedMs)/4 else outcome.elapsedMs
        writableDatabase.insertWithOnConflict("source_health",null,ContentValues().apply{put("id",outcome.source.id);put("fingerprint",fingerprint);put("latency",latency);put("failures",if(failed)(old?.second?:0)+1 else 0);put("checked",now)},SQLiteDatabase.CONFLICT_REPLACE)
        outcome.entries.take(100).forEach{e->writableDatabase.insertWithOnConflict("search_catalog",null,ContentValues().apply{put("id",e.key);put("source_id",outcome.source.id);put("category",e.category.name);put("fingerprint",fingerprint);put("payload",e.copy(description=e.description.take(2000)).json().toString());put("seen",now)},SQLiteDatabase.CONFLICT_REPLACE)}
        writableDatabase.execSQL("DELETE FROM search_catalog WHERE seen<?",arrayOf(now-7*24*60*60*1000L))
        writableDatabase.execSQL("DELETE FROM search_catalog WHERE id IN (SELECT id FROM search_catalog ORDER BY seen DESC LIMIT -1 OFFSET 8000)")
    }
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
