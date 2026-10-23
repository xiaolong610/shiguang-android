package app.shiguang.mobile

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Layout07Test {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private lateinit var server:MockWebServer
    private lateinit var originals:List<Source>
    private lateinit var favorites:List<Entry>
    private lateinit var fixtureSources:List<Source>
    private var hidden=emptyList<Pair<File,File>>()
    private fun model(action:(AppModel)->Unit){lateinit var m:AppModel;compose.activityRule.scenario.onActivity{m=ViewModelProvider(it)[AppModel::class.java]};compose.waitUntil(15000){m.libraryIdle};compose.activityRule.scenario.onActivity{action(m)};compose.waitForIdle();compose.waitUntil(15000){m.libraryIdle}}
    private fun capture(name:String){compose.waitForIdle();Thread.sleep(350);ins.uiAutomation.takeScreenshot()!!.let{b->File(context.getExternalFilesDir(null),"layout07-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun category(c:ContentCategory){compose.onNodeWithContentDescription("切换${c.label}分类").performClick();compose.waitForIdle()}
    private fun nav(name:String){compose.onNodeWithContentDescription(name,useUnmergedTree=true).performClick();compose.waitForIdle()}
    private fun dismiss(){compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
    @Before fun fixture(){
        server=MockWebServer();server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse{
            val list=JSONArray();repeat(12){i->list.put(JSONObject().put("vod_id",i+1).put("vod_name","影视作品%02d".format(i+1)).put("vod_remarks","更新至12集"))}
            return MockResponse().setBody(JSONObject().put("list",list).toString())
        }};server.start()
        LibraryStore(context).use{store->
            originals=store.sources();favorites=store.favorites();originals.forEach{store.putSource(it.copy(enabled=false))};favorites.forEach{store.favorite(it,false)}
            fixtureSources=ContentCategory.entries.map{c->
                val raw=JSONObject().put("id",SourceCodec.stableId("layout07:${c.name}")).put("name","${c.label}布局源").put("kind",c.kind.name.lowercase()).put("category",c.name.lowercase()).put("engine",if(c.kind==Kind.VIDEO)"maccms"else"html").put("url",server.url("/${c.name}").toString()).put("enabled",false)
                if(c.kind!=Kind.VIDEO)raw.put("rules",JSONObject().put("searchUrl","/search?q={query}").put("list",".book").put("title","a").put("chapters",".chapter").put("images","img").put("content","main"))
                SourceCodec.parse(raw.toString()).single()
            }
            store.putSources(fixtureSources)
            fixtureSources.forEach{source->repeat(9){i->store.favorite(Entry("$i",source.id,"${source.category.label}收藏%02d".format(i+1),source.kind,server.url("/item/$i").toString(),subtitle="布局验证内容",category=source.category),true)}}
        }
        hidden=File(context.filesDir,"novels").listFiles().orEmpty().filter{it.extension=="json"}.map{it to File(it.parentFile,it.name+".layout07-hidden")};hidden.forEach{(a,b)->check(a.renameTo(b))}
        model{it.refreshLibrary()}
    }
    @After fun restore(){
        Narration.command(context,"stop")
        LibraryStore(context).use{store->fixtureSources.forEach{store.deleteSource(it.id)};store.putSources(originals);store.favorites().forEach{store.favorite(it,false)};favorites.forEach{store.favorite(it,true)}}
        hidden.forEach{(a,b)->if(b.exists())check(b.renameTo(a))};server.shutdown()
    }
    @Test fun compactDiscoveryHasThreeColumnsAndShelfChangesAllFourCollections(){
        org.junit.Assume.assumeTrue(context.resources.configuration.fontScale<=1.25f)
        model{m->m.store.putSource(fixtureSources.first().copy(enabled=true));m.refreshSources();m.search()}
        compose.waitUntil(15000){compose.onAllNodesWithText("影视作品03").fetchSemanticsNodes().isNotEmpty()}
        val nodes=listOf("山海之间","影视作品01","影视作品02").map{compose.onAllNodesWithText(it).onLast().fetchSemanticsNode().boundsInRoot}
        capture("01-discover")
        Assert.assertTrue(nodes.zipWithNext().all{(a,b)->b.left>a.right&&kotlin.math.abs(a.top-b.top)<3f})
        nav("书架")
        ContentCategory.entries.forEach{c->category(c);compose.onAllNodesWithText("${c.label}收藏09").onLast().assertExists();Assert.assertTrue(compose.onAllNodesWithText("${c.label}收藏09").fetchSemanticsNodes().isNotEmpty());capture("02-shelf-${c.name}")}
        compose.activityRule.scenario.recreate();compose.onNodeWithText("小说 9").assertExists()
    }
    @Test fun largeTextUsesTwoColumnsAndKeepsCategoryActionsReachable(){
        org.junit.Assume.assumeTrue(context.resources.configuration.fontScale>1.25f)
        model{m->m.store.putSource(fixtureSources.first().copy(enabled=true));m.refreshSources();m.search()}
        compose.waitUntil(15000){compose.onAllNodesWithText("影视作品02").fetchSemanticsNodes().isNotEmpty()}
        val first=compose.onAllNodesWithText("山海之间").onLast().fetchSemanticsNode().boundsInRoot
        val second=compose.onAllNodesWithText("影视作品01").onLast().fetchSemanticsNode().boundsInRoot
        val third=compose.onAllNodesWithText("影视作品02").onLast().fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(first.top,second.top,3f);Assert.assertTrue(third.top>first.bottom)
        compose.onNodeWithContentDescription("添加").assertIsDisplayed();ContentCategory.entries.forEach{compose.onNodeWithContentDescription("切换${it.label}分类").assertIsDisplayed()}
        capture("17-small-screen")
    }
    @Test fun compactSourceFilterKeepsEnablementAndSurvivesRecreation(){
        nav("内容源");capture("03-sources")
        compose.onNodeWithContentDescription("选择规则分类").performClick();capture("04-source-filter")
        val count=fixtureSources.count{it.category==ContentCategory.ANIME}+originals.count{it.category==ContentCategory.ANIME}
        compose.onNodeWithText("动画 · $count").performClick()
        compose.onNodeWithText("动画布局源").assertIsDisplayed();compose.onNodeWithText("影视布局源").assertDoesNotExist()
        compose.onNodeWithContentDescription("动画布局源启停").performClick()
        Assert.assertTrue(LibraryStore(context).use{it.sources().first{s->s.id==fixtureSources[1].id}.enabled})
        compose.activityRule.scenario.recreate();compose.onNodeWithText("动画布局源").assertIsDisplayed();capture("05-source-anime")
        compose.onNodeWithContentDescription("动画布局源更多").performClick();compose.onNodeWithText("规则信息").performClick();compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithContentDescription("导入内容源").performClick();compose.onNodeWithText("订阅链接").performClick();capture("06-import-subscription");compose.onNodeWithText("规则订阅地址").assertExists();compose.onNodeWithContentDescription("关闭导入").performClick()
    }
    @Test fun readerToolsOverlayWithoutMovingParagraphsAndSettingsWork(){
        NovelPreferences(context).position(Demo.entries.last().key,0,0,0)
        category(ContentCategory.NOVEL)
        compose.waitUntil(15000){compose.onAllNodesWithText("把今天寄给明天").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("把今天寄给明天").onLast().performClick()
        compose.onNodeWithText("第一章 · 蓝色信箱").performScrollTo().performClick()
        val text="海边的邮局在下午四点关门。"
        compose.waitUntil(15000){compose.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("目录").assertDoesNotExist();capture("07-reader-immersive")
        val before=compose.onNodeWithText(text,substring=true).fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithText(text,substring=true).performTouchInput{click()};compose.onNodeWithText("目录").assertIsDisplayed()
        Assert.assertEquals(before,compose.onNodeWithText(text,substring=true).fetchSemanticsNode().boundsInRoot.top,1f)
        capture("08-reader-menu");compose.onNodeWithText("设置").performClick();capture("09-reader-settings")
        compose.onNodeWithText("夜").performClick();Assert.assertEquals(3,NovelPreferences(context).theme)
        compose.onNodeWithText("白").performClick();dismiss()
        compose.onNodeWithContentDescription("阅读进度").performClick();compose.onNodeWithText("下一章").performClick();dismiss()
        compose.waitUntil(15000){compose.onAllNodesWithText("入秋后，小镇的游客少了。",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("听书").performClick();compose.onNodeWithText("从当前阅读位置开始").performClick()
        compose.waitUntil(20000){Narration.state.value.playing};capture("10-listening")
        compose.onNodeWithContentDescription("听书设置").performClick();capture("11-listening-settings");compose.onNodeWithText("结束听书").performClick()
        compose.waitUntil(5000){Narration.state.value.entry==null}
    }
    @Test fun retiringAnimeSourcePreservesOldFavoritesAndProgressCategory(){
        val source=fixtureSources[1];val entry=Entry("old-entry",source.id,"旧版动画收藏",Kind.VIDEO,server.url("/old").toString())
        LibraryStore(context).use{it.favorite(entry,true);it.save(entry,0,3,1267)}
        model{it.remove(source)}
        LibraryStore(context).use{store->Assert.assertEquals(ContentCategory.ANIME,store.favorites().first{it.key==entry.key}.category);val saved=store.progress().first{it.entry.key==entry.key};Assert.assertEquals(ContentCategory.ANIME,saved.entry.category);Assert.assertEquals(1267L,saved.offset)}
    }
}

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlayerLayout07Test{
    private val ins=InstrumentationRegistry.getInstrumentation();private val context=ins.targetContext
    private fun capture(name:String){Thread.sleep(350);ins.uiAutomation.takeScreenshot()!!.let{b->File(context.getExternalFilesDir(null),"layout07-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun click(label:String){
        fun find(node:android.view.accessibility.AccessibilityNodeInfo):android.view.accessibility.AccessibilityNodeInfo?{
            if(node.text?.toString()==label||node.contentDescription?.toString()==label)return node
            for(i in 0 until node.childCount)node.getChild(i)?.let{find(it)}?.let{return it}
            return null
        }
        var node=find(ins.uiAutomation.rootInActiveWindow)?:error("没有找到 $label")
        while(!node.isClickable)node=node.parent?:error("不可点击 $label")
        Assert.assertTrue(node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));ins.waitForIdleSync()
    }
    @Test fun reverseListDoesNotReversePlaybackAndSelectingEpisodeUsesOriginalIndex(){
        val entry=Demo.entries.first().copy(id="layout07-player-${System.nanoTime()}")
        val pages=JSONArray();repeat(12){pages.put(JSONObject().put("title","第 ${it+1} 集").put("url","asset:///sample.mp4"))}
        context.getSharedPreferences("player",0).edit().putBoolean("episodeReverse",false).putBoolean("autoPip",false).apply()
        val intent=Intent(context,PlayerActivity::class.java).putExtra("entry",entry.json().toString()).putExtra("playlist",pages.toString()).putExtra("chapter",2).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try{androidx.test.core.app.ActivityScenario.launch<PlayerActivity>(intent).use{scenario->
            val player=PlaybackTestSupport.await(scenario);scenario.onActivity{player.pause()};Thread.sleep(500)
            capture("12-player-ascending")
            click("正序 ↑");Thread.sleep(350)
            Assert.assertEquals(2,PlaybackSession.state.value.chapter);capture("13-player-descending")
            click("播放第10集")
            val deadline=System.currentTimeMillis()+10000;while(PlaybackSession.state.value.chapter!=9&&System.currentTimeMillis()<deadline)Thread.sleep(100)
            Assert.assertEquals(9,PlaybackSession.state.value.chapter)
            scenario.onActivity{player.pause();it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE};Thread.sleep(800)
            scenario.onActivity{PlaybackTestSupport.view(it.window.decorView)!!.showController()};capture("14-player-landscape")
        }}finally{context.stopService(Intent(context,PlaybackService::class.java))}
    }
}
