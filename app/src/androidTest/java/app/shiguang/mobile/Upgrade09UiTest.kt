package app.shiguang.mobile

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Upgrade09UiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private val sourceIds=mutableListOf<String>();private val entryKeys=mutableListOf<Entry>()
    private fun model(action:(AppModel)->Unit){compose.activityRule.scenario.onActivity{action(ViewModelProvider(it)[AppModel::class.java])};compose.waitForIdle()}
    private fun capture(name:String){compose.waitForIdle();Thread.sleep(300);ins.uiAutomation.takeScreenshot()!!.let{b->File(context.getExternalFilesDir(null),"upgrade09-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    @After fun clean(){model{m->entryKeys.forEach{m.store.favorite(it,false);m.store.writableDatabase.delete("progress","id=?",arrayOf(it.key))};sourceIds.forEach{m.store.deleteSource(it)};m.refreshLibrary()}}
    @Test fun shelfShowsNewAndUnreadCountsAndUpdatesFilterStopsShowingReadWork(){
        val e=Entry("ui09",SourceCodec.stableId("ui09-source"),"星光追更验证",Kind.NOVEL,"https://example.com/09")
        val source=Source(e.sourceId,"追更演示源",Kind.NOVEL,"html","https://example.com",rules="""{"searchUrl":"/search","list":"a","title":"a","chapters":"a","content":"main"}""")
        sourceIds+=source.id;entryKeys+=e
        model{m->m.store.putSource(source);m.store.favorite(e,true)
            val old=Detail(e,listOf(Line("目录",(1..2).map{Chapter("第${it}章","url$it")})))
            val fresh=Detail(e,listOf(Line("目录",(1..4).map{Chapter("第${it}章","url$it")})))
            m.store.observeDetail(old);m.store.observeDetail(fresh);m.store.save(e,0,0,0,"第1章");m.refreshLibrary()
            compose.activity.setContent{ShiguangTheme{Box(Modifier.safeDrawingPadding()){CompactLibraryScreen(m,{})}}}
        }
        compose.onNodeWithContentDescription("切换小说分类").performClick()
        compose.onNodeWithText("新增 2 章").assertExists();compose.onNodeWithText("未读 3 章").assertExists()
        compose.onNodeWithText("有更新").performClick();compose.onNodeWithText(e.title).assertExists();capture("01-shelf-updates")
        model{m->m.store.save(e,0,3,0,"第4章");m.refreshLibrary()}
        compose.onNodeWithText(e.title).assertDoesNotExist();compose.onNodeWithText("暂时没有新的更新").assertExists()
    }
    @Test fun sourceSwitchMatchesChapterTitleAndMovesFavoriteWithoutLosingOriginalProgress(){
        val server=MockWebServer();server.start()
        val rules="""{"searchUrl":"/search?q={query}","list":".book","title":"a","chapters":"a.chapter","content":"main"}"""
        val a=Source(SourceCodec.stableId("switch09-a"),"来源甲",Kind.NOVEL,"html",server.url("/").toString(),rules=rules)
        val b=a.copy(id=SourceCodec.stableId("switch09-b"),name="来源乙")
        val entry=Entry("old",a.id,"星海换源验证",Kind.NOVEL,server.url("/old").toString())
        val candidate=entry.copy(id=SourceCodec.stableId(server.url("/new").toString()),sourceId=b.id,url=server.url("/new").toString())
        sourceIds+=listOf(a.id,b.id);entryKeys+=listOf(entry,candidate)
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse=MockResponse().setBody(when(request.requestUrl?.encodedPath){
            "/search"->"<div class='book'><a href='/new'>星海换源验证</a></div>"
            "/old"->"<a class='chapter' href='/old1'>第一章 相遇</a><a class='chapter' href='/old2'>第二章 重逢</a>"
            else->"<a class='chapter' href='/intro'>序章</a><a class='chapter' href='/new1'>第一章 相遇</a><a class='chapter' href='/new2'>第二章 重逢</a>"
        })}
        try{
            model{m->m.store.putSources(listOf(a,b));m.store.favorite(entry,true);m.store.save(entry,0,1,57,"第二章 重逢");m.refreshLibrary()
                compose.activity.setContent{var selected by remember{mutableStateOf(entry)};ShiguangTheme{Box(Modifier.safeDrawingPadding()){NovelDetailScreen(m,selected,{},onRead={_,_,_->},onSwitched={selected=it})}}}
            }
            compose.onNodeWithText("查找其他来源 / 换源").performScrollTo().performClick()
            compose.waitUntil(20000){compose.onAllNodesWithText("来源乙").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("来源乙").performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText("换源并继续").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("已匹配“第二章 重逢”，可手动调整。").assertExists();capture("02-switch-confirm")
            compose.onNodeWithText("换源并继续").performClick()
            compose.waitUntil(10000){LibraryStore(context).use{it.favorites().any{e->e.sourceId==b.id}}}
            LibraryStore(context).use{store->assertFalse(store.favorites().any{it.key==entry.key});val p=store.progress().first{it.entry.sourceId==b.id};assertEquals(2,p.chapter);assertEquals(0L,p.offset);assertEquals(57L,store.progressFor(entry.key)!!.offset);entryKeys+=p.entry}
        }finally{server.shutdown()}
    }
    @Test fun prefetchedNovelNextChapterOpensEvenAfterServerGoesOffline(){
        val server=MockWebServer();server.start();val second=java.util.concurrent.CountDownLatch(1)
        val source=Source(SourceCodec.stableId("preload09"),"预取验证源",Kind.NOVEL,"html",server.url("/").toString(),rules="""{"searchUrl":"/search","list":"a","title":"a","chapters":"a","content":"main"}""")
        val entry=Entry("novel",source.id,"预取验证小说",Kind.NOVEL,server.url("/book").toString());sourceIds+=source.id;entryKeys+=entry
        org.junit.Assume.assumeTrue(ReadingPreload.allowed(context,source))
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{if(request.path=="/body2")second.countDown();return MockResponse().setBody("<main>"+(if(request.path=="/body2")"下一章已提前准备好"else"第一章正文")+"</main>")}}
        val detail=Detail(entry,listOf(Line("目录",listOf(Chapter("第一章",server.url("/body1").toString()),Chapter("第二章",server.url("/body2").toString())))))
        try{
            NovelPreferences(context).turnMode=NovelTurnMode.SCROLL
            model{m->m.store.putSource(source);m.refreshSources();compose.activity.setContent{ShiguangTheme{NovelReaderScreen(m,detail,0,0,{})}}}
            compose.waitUntil(15000){compose.onAllNodesWithText("　　第一章正文").fetchSemanticsNodes().isNotEmpty()}
            assertTrue("没有预取下一章",second.await(10,java.util.concurrent.TimeUnit.SECONDS));Thread.sleep(300);server.shutdown()
            compose.onNodeWithText("下一章  第二章").performScrollTo().performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("　　下一章已提前准备好").fetchSemanticsNodes().isNotEmpty()};capture("04-novel-preloaded")
        }finally{runCatching{server.shutdown()}}
    }
    @Test fun failedComicImageRetriesIndividuallyWithoutReloadingChapter(){
        val server=MockWebServer();server.start();val recover=java.util.concurrent.atomic.AtomicBoolean(false);val chapterCalls=java.util.concurrent.atomic.AtomicInteger()
        val source=Source(SourceCodec.stableId("comic09"),"漫画重试源",Kind.COMIC,"html",server.url("/").toString(),rules="""{"searchUrl":"/search","list":"a","title":"a","chapters":"a","images":"img"}""")
        val entry=Entry("comic",source.id,"漫画重试验证",Kind.COMIC,server.url("/book").toString());sourceIds+=source.id;entryKeys+=entry
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            if(request.path=="/chapter"){chapterCalls.incrementAndGet();return MockResponse().setBody("<img src='/1.svg'><img src='/2.svg'><img src='/3.svg'>")}
            if(request.path=="/2.svg"&&!recover.get())return MockResponse().setResponseCode(503)
            return MockResponse().setHeader("Content-Type","image/svg+xml").setBody("<svg xmlns='http://www.w3.org/2000/svg' width='600' height='800'><rect width='600' height='800' fill='#285e50'/><text x='40' y='80' fill='white' font-size='40'>Shiguang</text></svg>")
        }}
        try{
            context.getSharedPreferences("reader",0).edit().putString("comicTurnMode",ComicTurnMode.SCROLL.name).apply()
            model{m->m.store.putSource(source);m.refreshSources();compose.activity.setContent{ShiguangTheme{ComicReaderScreen(m,Detail(entry,listOf(Line("目录",listOf(Chapter("第一话",server.url("/chapter").toString()))))),0,0,{})}}}
            compose.waitUntil(15000){compose.onAllNodesWithContentDescription("漫画条漫阅读区").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("漫画条漫阅读区").performScrollToIndex(1)
            compose.waitUntil(15000){compose.onAllNodesWithText("第 2 页加载失败").fetchSemanticsNodes().isNotEmpty()}
            recover.set(true);compose.onNodeWithText("重新加载图片").performClick()
            compose.waitUntil(15000){compose.onAllNodesWithText("第 2 页加载失败").fetchSemanticsNodes().isEmpty()};assertEquals(1,chapterCalls.get());capture("05-comic-retry")
        }finally{server.shutdown()}
    }
}
