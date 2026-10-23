package app.shiguang.mobile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class Search091StoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun v3MigrationKeepsLibraryAndAddsLocalFuzzyIndexAndHealth(){
        val name="search091-${UUID.randomUUID()}.db"
        val s=Source(SourceCodec.stableId("search-store"),"索引站点",Kind.VIDEO,"maccms","https://example.com/api")
        val e=Entry("book",s.id,"星海远航",Kind.VIDEO,"https://example.com/book")
        try{
            LibraryStore(context,name).use{store->
                store.putSource(s);store.favorite(e,true);store.save(e,0,2,5432,"第三集")
                store.writableDatabase.execSQL("DROP TABLE search_catalog");store.writableDatabase.execSQL("DROP TABLE source_health");store.writableDatabase.version=3
            }
            LibraryStore(context,name).use{store->
                assertEquals(4,store.readableDatabase.version);assertEquals(e.key,store.favorites().single().key)
                assertEquals(5432L,store.progressFor(e.key)!!.offset);assertEquals("第三集",store.progressFor(e.key)!!.chapterTitle)
                store.recordSearch(SearchOutcome(s,listOf(e),elapsedMs=120),now=1_000_000)
                val cached=store.searchCatalog(ContentCategory.VIDEO,listOf(s),now=1_000_001)
                assertEquals(e.key,SearchText.matches(cached,"星远航").single().key)
                assertTrue(store.searchCatalog(ContentCategory.VIDEO,listOf(s.copy(enabled=false)),1_000_001).isEmpty())
                assertTrue(store.searchCatalog(ContentCategory.VIDEO,listOf(s.copy(url="https://changed.example/api")),1_000_001).isEmpty())
                assertTrue(store.searchCatalog(ContentCategory.VIDEO,listOf(s),1_000_001+8*24*60*60*1000L).isEmpty())
                repeat(2){store.recordSearch(SearchOutcome(s,emptyList(),"超时",7000),1_000_002+it.toLong())}
                assertEquals(2,store.sourceHealth().getValue(s.id).failures);assertTrue(store.sources().single().enabled)
                store.recordSearch(SearchOutcome(s,listOf(e),elapsedMs=90),1_000_005);assertEquals(0,store.sourceHealth().getValue(s.id).failures)
            }
        }finally{context.deleteDatabase(name)}
    }
}
