package app.shiguang.mobile

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class Reading08Test{
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation();private val context get()=ins.targetContext
    private lateinit var server:MockWebServer;private lateinit var source:Source
    private lateinit var novel:Entry;private lateinit var comic:Entry;private lateinit var video:Entry
    private fun model(action:(AppModel)->Unit){lateinit var m:AppModel;compose.activityRule.scenario.onActivity{m=ViewModelProvider(it)[AppModel::class.java]};compose.waitUntil(15000){m.libraryIdle};compose.activityRule.scenario.onActivity{action(m)};compose.waitForIdle();compose.waitUntil(15000){m.libraryIdle}}
    private fun capture(name:String){compose.waitForIdle();Thread.sleep(200);ins.uiAutomation.takeScreenshot()!!.let{b->File(context.getExternalFilesDir(null),"reading08-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun dismiss(){compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
    @Before fun setup(){
        server=MockWebServer();server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            val body=if(request.path!!.startsWith("/body"))"<main>"+(1..80).joinToString(""){"<p>第${it}段。这是一段用于验证左右翻页和阅读位置的文字，分页后每个字都要保留。月光经过海边小镇，邮局已经准备关门。</p>"}+"</main>"else(1..12).joinToString(""){"<a class='chapter' href='/body$it'>第 %02d 章</a>".format(it)}
            return MockResponse().setBody(body)
        }};server.start()
        source=Source(SourceCodec.stableId("reading08-${System.nanoTime()}"),"阅读交互验证",Kind.NOVEL,"html",server.url("/").toString(),false,"""{"searchUrl":"/search?q={query}","list":".item","title":"a","chapters":".chapter","content":"main","images":"img"}""")
        novel=Entry("novel",source.id,"海边来信",Kind.NOVEL,server.url("/detail").toString());comic=novel.copy(id="comic",kind=Kind.COMIC,category=ContentCategory.COMIC);video=novel.copy(id="video",kind=Kind.VIDEO,category=ContentCategory.VIDEO)
        context.getSharedPreferences("chapter-order",0).edit().clear().apply();NovelPreferences(context).turnMode=NovelTurnMode.SCROLL
        context.getSharedPreferences("reader",0).edit().putString("comicTurnMode",ComicTurnMode.SCROLL.name).putString("comic:${Demo.entries[1].key}:0:0","0:0").apply()
        model{it.store.putSource(source);it.refreshSources()}
    }
    @After fun cleanup(){model{m->listOf(novel,comic,video).forEach{e->m.store.favorite(e,false)};m.store.deleteSource(source.id);m.refreshSources()};NovelPreferences(context).turnMode=NovelTurnMode.SCROLL;context.getSharedPreferences("reader",0).edit().putString("comicTurnMode",ComicTurnMode.SCROLL.name).apply();server.shutdown()}
    private fun details(entry:Entry,onRead:(Detail,Int,Int)->Unit={_,_,_->}){model{m->compose.activity.setContent{ShiguangTheme{if(entry.kind==Kind.NOVEL)Box(Modifier.statusBarsPadding()){NovelDetailScreen(m,entry,{},onRead)}else DetailScreen(m,entry,{},onRead)}}};compose.waitUntil(15000){compose.onAllNodesWithText("第 01 章").fetchSemanticsNodes().isNotEmpty()}}
    @Test fun favoriteHasDistinctTextStateAndNovelReverseUsesOriginalChapterIndex(){
        val selected=AtomicInteger(-1);details(novel){_,_,i->selected.set(i)}
        compose.onNodeWithText("加入收藏").assertIsDisplayed();capture("01-favorite-before")
        compose.onNodeWithText("加入收藏").performClick();compose.onNodeWithText("已收藏").assertIsDisplayed();assertTrue(LibraryStore(context).use{it.favorites().any{e->e.key==novel.key}});capture("02-favorite-after")
        compose.onNodeWithText("已收藏").performClick();compose.onNodeWithText("加入收藏").assertIsDisplayed()
        compose.onNodeWithContentDescription("切换章节排序").performScrollTo().performClick()
        compose.onNodeWithText("第 12 章").performScrollTo().assertIsDisplayed();capture("03-novel-reverse")
        compose.onNodeWithText("第 12 章").performClick();assertEquals(11,selected.get());assertTrue(context.getSharedPreferences("chapter-order",0).getBoolean(Kind.NOVEL.name,false))
    }
    @Test fun comicDetailReverseStillOpensCorrectChapter(){val selected=AtomicInteger(-1);details(comic){_,_,i->selected.set(i)};compose.onNodeWithContentDescription("切换章节排序").performScrollTo().performClick();compose.onNodeWithText("第 12 章").performScrollTo().assertIsDisplayed();capture("04-comic-detail");compose.onNodeWithText("第 12 章").performClick();assertEquals(11,selected.get())}
    @Test fun videoDetailHasVisibleFavoriteAndReverseEpisodeList(){details(video);compose.onNodeWithText("加入收藏").assertIsDisplayed();compose.onNodeWithContentDescription("切换章节排序").performScrollTo().performClick();compose.onNodeWithText("第 12 章").performScrollTo().assertIsDisplayed();capture("05-video-detail");compose.onNodeWithText("倒序 ↓").performScrollTo().performClick();compose.onNodeWithText("第 01 章").performScrollTo().assertIsDisplayed()}
    @Test fun novelSlideTapAndScrollKeepPositionAndModes(){
        val d=Detail(novel,listOf(Line("章节",(1..3).map{Chapter("第 $it 章",server.url("/body$it").toString())})))
        model{m->compose.activity.setContent{ShiguangTheme{NovelReaderScreen(m,d,0,0,{})}}}
        compose.waitUntil(15000){compose.onAllNodesWithText("第1段。",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("唤出阅读操作").performClick();compose.onNodeWithText("设置").performClick();compose.onNodeWithText("左右滑动").performClick();assertEquals(NovelTurnMode.SLIDE,NovelPreferences(context).turnMode);capture("06-novel-settings");dismiss()
        compose.onNodeWithContentDescription("小说分页阅读区").performTouchInput{click(center)}
        compose.onNodeWithContentDescription("小说分页阅读区").performTouchInput{swipeLeft()};compose.waitForIdle()
        val saved=NovelPreferences(context).position(novel.key,0);assertTrue("滑动没有向后翻页：$saved",saved.first>1||saved.second>0);capture("07-novel-slide")
        compose.onNodeWithContentDescription("唤出阅读操作").performClick();compose.onNodeWithText("设置").performClick();compose.onNodeWithText("点击翻页").performClick();dismiss()
        compose.onNodeWithContentDescription("小说分页阅读区").performTouchInput{click(center)};val before=NovelPreferences(context).position(novel.key,0)
        compose.onNodeWithContentDescription("小说分页阅读区").performTouchInput{click(Offset(width*.9f,height*.5f))};compose.waitForIdle();val after=NovelPreferences(context).position(novel.key,0);assertTrue(after.first>before.first||(after.first==before.first&&after.second>before.second))
        compose.onNodeWithContentDescription("唤出阅读操作").performClick();compose.onNodeWithText("设置").performClick();compose.onNodeWithText("上下滚动").performClick();dismiss();assertEquals(NovelTurnMode.SCROLL,NovelPreferences(context).turnMode);assertTrue(NovelPreferences(context).position(novel.key,0).first>=after.first)
    }
    @Test fun comicSwipeDirectionsTapAndScrollPreserveImage(){
        model{m->compose.activity.setContent{ShiguangTheme{ComicReaderScreen(m,Demo.detail(Demo.entries[1]),0,0,{})}}}
        compose.waitUntil(15000){compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("唤出漫画操作").performClick();compose.onNodeWithText("设置").performClick();compose.onNodeWithText("左右翻页").performClick();capture("08-comic-settings");dismiss()
        compose.onNodeWithContentDescription("漫画分页阅读区").performTouchInput{click(center)};compose.onNodeWithContentDescription("漫画分页阅读区").performTouchInput{swipeLeft()};compose.waitForIdle();compose.onNodeWithText("2 / 3").assertExists();capture("09-comic-page")
        compose.onNodeWithContentDescription("漫画分页阅读区").performTouchInput{click(Offset(width*.9f,height*.5f))};compose.waitForIdle();compose.onNodeWithText("3 / 3").assertExists()
        compose.onNodeWithContentDescription("漫画分页阅读区").performTouchInput{click(Offset(width*.1f,height*.5f))};compose.waitForIdle();compose.onNodeWithText("2 / 3").assertExists()
        compose.onNodeWithContentDescription("唤出漫画操作").performClick();compose.onNodeWithText("设置").performClick();compose.onNodeWithText("日漫右翻").performClick();dismiss();compose.onNodeWithContentDescription("漫画分页阅读区").performTouchInput{click(center)};compose.onNodeWithContentDescription("漫画分页阅读区").performTouchInput{swipeRight()};compose.waitForIdle();compose.onNodeWithText("3 / 3").assertExists()
        model{compose.activity.setContent{androidx.compose.material3.Text("重新打开漫画")}}
        model{m->compose.activity.setContent{ShiguangTheme{ComicReaderScreen(m,Demo.detail(Demo.entries[1]),0,0,{})}}}
        compose.waitUntil(10000){compose.onAllNodesWithText("3 / 3").fetchSemanticsNodes().isNotEmpty()};assertEquals(ComicTurnMode.RIGHT.name,context.getSharedPreferences("reader",0).getString("comicTurnMode",null));capture("12-comic-restored")
        compose.onNodeWithContentDescription("唤出漫画操作").performClick();compose.onNodeWithText("设置").performClick();compose.onNodeWithText("上下条漫").performClick();dismiss();compose.onNodeWithContentDescription("漫画条漫阅读区").assertExists();compose.onNodeWithContentDescription("第 3 页").assertIsDisplayed()
    }
    @Test fun oldComicPixelPositionMigratesToImagePosition(){
        val entry=Demo.entries[1].copy(id="legacy-${System.nanoTime()}")
        val key="comic:${entry.key}:0:0";context.getSharedPreferences("reader",0).edit().remove(key).apply()
        model{m->m.store.save(entry,0,0,700);compose.activity.setContent{ShiguangTheme{ComicReaderScreen(m,Demo.detail(entry),0,0,{})}}}
        try{compose.waitUntil(15000){val p=context.getSharedPreferences("reader",0).getString(key,null);p!=null&&p!="0:0"}}
        finally{model{compose.activity.setContent{androidx.compose.material3.Text("迁移检查完成")}};model{it.store.writableDatabase.delete("progress","id=?",arrayOf(entry.key))};context.getSharedPreferences("reader",0).edit().remove(key).apply()}
    }
    @Test fun realNovelPagedModeAndPositionSurviveActivityRecreation(){
        NovelPreferences(context).turnMode=NovelTurnMode.SLIDE
        val key=Demo.entries.last().key;NovelPreferences(context).position(key,0,0,0)
        compose.openDemoNovel();compose.onNodeWithText("第一章 · 蓝色信箱").performScrollTo().performClick()
        compose.waitUntil(15000){compose.onAllNodesWithContentDescription("小说分页阅读区").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("小说分页阅读区").performTouchInput{swipeLeft()};compose.waitForIdle()
        val before=NovelPreferences(context).position(key,0);assertTrue(before.first>0||before.second>0)
        compose.activityRule.scenario.recreate();compose.waitUntil(15000){compose.onAllNodesWithContentDescription("小说分页阅读区").fetchSemanticsNodes().isNotEmpty()};compose.waitForIdle()
        assertEquals(NovelTurnMode.SLIDE,NovelPreferences(context).turnMode);assertEquals(before,NovelPreferences(context).position(key,0));capture("10-novel-restored")
    }
    @Test fun measuredPaginationKeepsEveryCharacterAtLargeFontInSmallViewport(){
        val title="分页验收";val body="甲乙丙丁😀，12345。月光经过海边小镇，邮局已经准备关门。".repeat(8)
        val current=AtomicInteger(0);val count=AtomicInteger(0)
        model{compose.activity.setContent{ShiguangTheme{Box(Modifier.width(260.dp).height(320.dp)){PagedNovelBody("large-font-${System.nanoTime()}",0,title,listOf(body),NovelTurnMode.TAP,32f,2.4f,false,16,Color.Black,0,0,null,0,{_,_,n,total->current.set(n);count.set(total)},{},{},{})}}}}
        compose.waitUntil(10000){count.get()>1};val actual=StringBuilder()
        repeat(count.get()){i->
            compose.waitForIdle();assertEquals(i+1,current.get())
            val bounds=compose.onNodeWithContentDescription("小说分页阅读区").fetchSemanticsNode().boundsInRoot
            val texts=compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes()
            val visible=texts.filter{it.boundsInRoot.width>0&&it.boundsInRoot.center.x>=bounds.left&&it.boundsInRoot.center.x<bounds.right}
            assertEquals("分页文字节点不唯一",1,visible.size);val node=visible.single();assertTrue("文字超出页面高度",node.boundsInRoot.bottom<=bounds.bottom+1)
            actual.append(node.config[SemanticsProperties.Text].joinToString(""){it.text})
            if(i<count.get()-1)compose.onNodeWithContentDescription("小说分页阅读区").performTouchInput{click(Offset(width*.9f,height*.5f))}
        }
        assertEquals("$title\n\n　　$body",actual.toString());capture("11-novel-small-largefont")
    }
}
