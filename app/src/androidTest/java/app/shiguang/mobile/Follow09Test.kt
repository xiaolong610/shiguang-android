package app.shiguang.mobile

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Follow09Test {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun v2DatabaseMigratesWithoutLosingFavoritesProgressOrRules(){
        val name="migration09-${UUID.randomUUID()}.db"
        val e=Demo.entries.last();val source=Source(SourceCodec.stableId("migration09"),"迁移规则",Kind.VIDEO,"maccms","https://example.com/api")
        try{
            context.openOrCreateDatabase(name,0,null).use{db->
                db.execSQL("CREATE TABLE sources (id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE favorites (id TEXT PRIMARY KEY,payload TEXT NOT NULL,updated INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE progress (id TEXT PRIMARY KEY,payload TEXT NOT NULL,line INTEGER NOT NULL,chapter INTEGER NOT NULL,offset INTEGER NOT NULL,updated INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE subscriptions (id TEXT PRIMARY KEY,payload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE subscription_sources (subscription_id TEXT NOT NULL,source_id TEXT NOT NULL,baseline TEXT NOT NULL,removed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(subscription_id,source_id))")
                db.insertOrThrow("sources",null,ContentValues().apply{put("id",source.id);put("payload",source.json().toString())})
                db.execSQL("INSERT INTO favorites VALUES(?,?,?)",arrayOf(e.key,e.json().toString(),123L))
                db.execSQL("INSERT INTO progress VALUES(?,?,?,?,?,?)",arrayOf(e.key,e.json().toString(),0,1,42L,123L));db.version=2
            }
            LibraryStore(context,name).use{store->
                assertEquals(e.key,store.favorites().single().key);assertEquals(42L,store.progressFor(e.key)!!.offset)
                assertEquals("",store.progressFor(e.key)!!.chapterTitle);assertEquals(1,store.sources().size)
                store.observeDetail(Demo.detail(e));assertEquals(3,store.followState(e.key)!!.titles.size)
                store.save(e,0,1,45,"第二章 · 慢一点的时间");assertEquals("第二章 · 慢一点的时间",store.progressFor(e.key)!!.chapterTitle)
            }
        }finally{context.deleteDatabase(name)}
    }
    @Test fun favoriteRefreshFindsAddedChaptersKeepsGoodStateOnFailureAndClearsBadgeAfterReading()=runBlocking {
        val name="follow09-${UUID.randomUUID()}.db";val server=MockWebServer();server.start()
        val source=Source(SourceCodec.stableId("follow-test"),"追更验证",Kind.NOVEL,"html",server.url("/").toString(),rules="""{"searchUrl":"/search","list":".book","title":"a","chapters":"a.chapter","content":"main"}""")
        val entry=Entry("book",source.id,"追更验证作品",Kind.NOVEL,server.url("/book").toString())
        try{
            LibraryStore(context,name).use{it.putSource(source);it.favorite(entry,true)}
            fun reply(count:Int){server.enqueue(MockResponse().setBody((1..count).joinToString(""){"<a class='chapter' href='/chapter/$it'>第${it}章</a>"}))}
            val manager=FollowManager(context,databaseName=name)
            reply(2);manager.refresh(true)
            LibraryStore(context,name).use{assertEquals(0,it.followState(entry.key)!!.newCount(null));it.save(entry,0,0,0,"第1章")}
            reply(4);manager.refresh(true)
            LibraryStore(context,name).use{val state=it.followState(entry.key)!!;assertEquals(2,state.newCount(it.progressFor(entry.key)));assertEquals(3,state.unread(it.progressFor(entry.key)))}
            server.enqueue(MockResponse().setResponseCode(503));manager.refresh(true)
            LibraryStore(context,name).use{val state=it.followState(entry.key)!!;assertEquals(4,state.titles.size);assertTrue(state.error.contains("503"));it.save(entry,0,3,0,"第4章");assertEquals(0,state.newCount(it.progressFor(entry.key)))}
        }finally{server.shutdown();context.deleteDatabase(name)}
    }
}
