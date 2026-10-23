package app.shiguang.mobile

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class Search091UiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val fixture=mutableListOf<Source>();private lateinit var server:MockWebServer
    private fun model(action:(AppModel)->Unit){lateinit var m:AppModel;compose.activityRule.scenario.onActivity{m=ViewModelProvider(it)[AppModel::class.java]};compose.waitUntil(15000){m.libraryIdle};compose.activityRule.scenario.onActivity{action(m)};compose.waitForIdle();compose.waitUntil(15000){m.libraryIdle}}
    private fun source(name:String,path:String,category:ContentCategory=ContentCategory.VIDEO):Source {
        val rules=JSONObject().put("category",category.name.lowercase())
        if(category.kind!=Kind.VIDEO)rules.put("searchUrl","$path?q={query}").put("list",".book").put("title","a").put("chapters","a.chapter").put("content","main").put("images","img")
        return Source(SourceCodec.stableId("search091:$name"),name,category.kind,if(category.kind==Kind.VIDEO)"maccms"else"html",server.url(path).toString(),rules=rules.toString()).also{fixture+=it}
    }
    private fun response(title:String)=MockResponse().setBody(JSONObject().put("list",JSONArray().put(JSONObject().put("vod_id","1").put("vod_name",title).put("vod_remarks","搜索验证作品"))).toString())
    private fun show(query:String){model{m->m.store.putSources(fixture);m.refreshSources();m.search(query);compose.activity.setContent{ShiguangTheme{ShiguangApp(m)}}}}
    private fun capture(name:String){compose.waitForIdle();Thread.sleep(150);ins.uiAutomation.takeScreenshot()!!.let{b->File(context.getExternalFilesDir(null),"search091-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    @Before fun start(){server=MockWebServer();server.start()}
    @After fun clean(){model{m->m.stopSearch();fixture.forEach{m.store.deleteSource(it.id);m.store.writableDatabase.delete("search_catalog","source_id=?",arrayOf(it.id));m.store.writableDatabase.delete("source_health","id=?",arrayOf(it.id))};m.refreshSources()};server.shutdown()}
    @Test fun fastWorkIsClickableWhileSlowSourceIsStillLoadingAndStopKeepsResults(){
        source("快速验证源","/fast");source("慢速验证源","/slow")
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=if(request.requestUrl?.encodedPath=="/slow")response("迟到的星海").setBodyDelay(4,TimeUnit.SECONDS)else response("星海远航")}
        show("星海")
        compose.waitUntil(3000){compose.onAllNodesWithText("星海远航").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("星海远航").onLast().assertIsDisplayed();model{assertTrue("结果仍需等全部来源",it.loading)};capture("01-partial-results")
        compose.onNodeWithText("停止").performClick();model{assertFalse(it.loading);assertTrue(it.entries.any{e->e.title=="星海远航"});assertTrue(it.searchRemaining>0)}
    }
    @Test fun missingCharacterAndSingleCharacterSearchUseKnownCatalogBeforeNetwork(){
        val s=source("本地索引验证源","/slow")
        val e=Entry("known",s.id,"星海远航",Kind.VIDEO,server.url("/known").toString())
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=response("远端结果").setBodyDelay(4,TimeUnit.SECONDS)}
        model{it.store.putSource(s);it.store.recordSearch(SearchOutcome(s,listOf(e),elapsedMs=60));it.refreshSources()}
        show("星远航")
        compose.waitUntil(3000){compose.onAllNodesWithText("星海远航").fetchSemanticsNodes().isNotEmpty()}
        model{assertTrue(it.loading);assertTrue(it.entries.any{e->e.title=="星海远航"})};capture("02-fuzzy-cached")
        // Avoid unrelated demo-video history accumulated by playback regression fixtures.
        model{it.search("星")}
        compose.waitUntil(3000){var exists=false;model{m->exists=m.entries.any{it.title=="星海远航"}};exists}
        try{compose.waitUntil(3000){runCatching{compose.onAllNodesWithText("星海远航").onLast().assertIsDisplayed()}.isSuccess}}
        finally{capture("02b-single-character")}
        compose.onAllNodesWithText("星海远航").onLast().assertIsDisplayed()
    }
    @Test fun allEnabledRulesStartWithBoundedBatchAndFullSearchCanExpandWithoutRepeatingFinishedSources(){
        repeat(28){source("范围验证源$it","/s$it")}
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=response("星海远航${request.requestUrl!!.encodedPath.removePrefix("/s").padStart(2,'0')}")}
        show("星海")
        compose.waitUntil(10000){var done=false;model{done=!it.loading};done}
        model{assertEquals(12,it.searchCount);assertEquals(17,it.searchRemaining);assertEquals(29,it.searchTotal)}
        assertEquals(11,server.requestCount);capture("03-bounded-search")
        compose.onNodeWithContentDescription("搜索范围").performClick();compose.onNodeWithText("搜索全部剩余来源").performClick()
        compose.waitUntil(15000){var done=false;model{done=!it.loading};done}
        model{assertEquals(29,it.searchCount);assertEquals(0,it.searchRemaining);assertEquals(28,it.entries.size)}
        assertEquals(28,server.requestCount)
    }
    @Test fun videoAnimeComicAndNovelAllShowFastResultsBeforeTheirSlowSiteCompletes(){
        ContentCategory.entries.forEach{category->source("${category.name}快源","/${category.name}/fast",category);source("${category.name}慢源","/${category.name}/slow",category)}
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            val path=request.requestUrl!!.encodedPath;val category=ContentCategory.valueOf(path.split('/')[1]);val title="${category.label}星海远航"
            val result=if(category.kind==Kind.VIDEO)response(title)else MockResponse().setBody("<div class='book'><a href='/book'>${title}</a></div>")
            return if(path.endsWith("slow"))result.setBodyDelay(3,TimeUnit.SECONDS)else result
        }}
        model{m->m.store.putSources(fixture);m.refreshSources();compose.activity.setContent{ShiguangTheme{ShiguangApp(m)}}}
        ContentCategory.entries.forEach{category->
            model{it.changeCategory(category);it.search("星海")}
            val title="${category.label}星海远航"
            compose.waitUntil(3000){compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()}
            model{assertTrue("${category.label}结果被慢源阻塞",it.loading)}
            compose.onAllNodesWithText(title).onLast().assertIsDisplayed();capture("04-${category.name}-streaming")
            model{it.stopSearch()}
        }
    }
}
