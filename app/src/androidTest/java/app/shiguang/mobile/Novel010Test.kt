package app.shiguang.mobile

import android.graphics.Bitmap
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class Novel010Test {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation();private val context get()=ins.targetContext
    private val generated=mutableListOf<Entry>()
    private fun model():AppModel {lateinit var m:AppModel;compose.activityRule.scenario.onActivity{m=ViewModelProvider(it)[AppModel::class.java]};compose.waitUntil(15000){m.libraryIdle};return m}
    private fun capture(name:String){compose.waitForIdle();ins.uiAutomation.takeScreenshot()!!.let{b->File(context.getExternalFilesDir(null),"reliability010-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun show(m:AppModel,entry:Entry=Demo.entries.last(),detail:Detail=Demo.detail(entry)){
        compose.activityRule.scenario.onActivity{it.setContent{ShiguangTheme{NovelReaderScreen(m,detail,0,0,{})}}}
        compose.waitUntil(10000){compose.onAllNodesWithContentDescription("唤出阅读操作").fetchSemanticsNodes().isNotEmpty()}
    }
    @After fun reset(){compose.activityRule.scenario.onActivity{it.readerVolumeHandler=null;it.setContent{Text("阅读检查完成")}};compose.waitForIdle();NovelPreferences(context).apply{volumeKeys=false;turnMode=NovelTurnMode.SCROLL;fontFile=""};LibraryStore(context).use{db->generated.forEach{db.writableDatabase.delete("progress","id=?",arrayOf(it.key))}};LibraryEvents.changed(library=true)}
    @Test fun fullBookSearchJumpsToCorrectLocalChapterAndDirectoryFilterKeepsOriginalIndices()=runBlocking {
        val m=model();val file=File(context.cacheDir,"search010.txt").apply{writeText("第一章 起点\n\n普通正文${System.nanoTime()}。\n\n第二章 星海\n\n这是第二章。\n\n灯塔暗号在这里。\n\n第三章 归来\n\n终点正文。")}
        val entry=LocalNovels(context).import(Uri.fromFile(file)).also{generated+=it};val detail=LocalNovels(context).detail(entry)
        try{
            NovelPreferences(context).turnMode=NovelTurnMode.SCROLL;show(m,entry,detail)
            compose.onNodeWithContentDescription("唤出阅读操作").performClick();compose.onNodeWithContentDescription("搜索正文").performClick()
            compose.onNodeWithText("全书 · 本地").performClick();compose.onNodeWithText("正文关键词").performTextInput("灯塔暗号");compose.onNodeWithText("开始搜索").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("找到 1 处").fetchSemanticsNodes().isNotEmpty()};capture("novel-search")
            compose.onNodeWithText("灯塔暗号在这里。",substring=true).performClick()
            compose.waitUntil(10000){LibraryStore(context).use{it.progressFor(entry.key)?.chapter==1}}
            compose.onNodeWithText("　　灯塔暗号在这里。").assertExists()
            compose.onNodeWithContentDescription("唤出阅读操作").performClick();compose.onNodeWithText("目录").performClick();compose.onNodeWithText("搜索章节名").performTextInput("归来")
            compose.onNodeWithText("第三章 归来").performClick();compose.waitUntil(10000){compose.onAllNodesWithText("　　终点正文。").fetchSemanticsNodes().isNotEmpty()}
            assertEquals(2,LibraryStore(context).use{it.progressFor(entry.key)!!.chapter})
        }finally{
            compose.activityRule.scenario.onActivity{it.setContent{Text("搜索验收完成")}}
            LibraryStore(context).use{it.writableDatabase.delete("progress","id=?",arrayOf(entry.key))}
            File(context.filesDir,"novels/${entry.id}.json").delete();val dir=File(context.filesDir,"novels/${entry.id}");require(entry.id.matches(Regex("[a-f0-9]{24}")));dir.deleteRecursively();file.delete()
        }
    }
    @Test fun volumeKeysTurnPagedReaderAndReleaseHandlerWhenReaderCloses(){
        val m=model();val entry=Demo.entries.last().copy(id="volume010-${System.nanoTime()}").also{generated+=it};NovelPreferences(context).apply{volumeKeys=true;turnMode=NovelTurnMode.SLIDE;position(entry.key,0,0,0)}
        show(m,entry);compose.waitUntil(10000){compose.onAllNodesWithContentDescription("小说分页阅读区").fetchSemanticsNodes().isNotEmpty()}
        ins.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)
        compose.waitUntil(5000){NovelPreferences(context).position(entry.key,0).let{it.first>0||it.second>0}}
        ins.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_UP)
        compose.waitUntil(5000){NovelPreferences(context).position(entry.key,0)==(0 to 0)}
        compose.activityRule.scenario.onActivity{it.setContent{Text("退出阅读")}}
        compose.waitForIdle();compose.activityRule.scenario.onActivity{assertNull(it.readerVolumeHandler)}
    }
    @Test fun customFontIsValidatedAndUsedAfterReaderReopens()=runBlocking {
        val m=model();val invalid=File(context.cacheDir,"bad010.ttf").apply{writeText("invalid font")}
        try{assertTrue(runCatching{NovelFonts.import(context,Uri.fromFile(invalid))}.isFailure)}finally{invalid.delete()}
        val systemFont=File("/system/fonts/DroidSans.ttf").takeIf{it.exists()}?:File("/system/fonts").listFiles()!!.first{it.extension=="ttf"&&it.length()<20*1024*1024}
        val name=NovelFonts.import(context,Uri.fromFile(systemFont));NovelPreferences(context).fontFile=name
        try{show(m);compose.onNodeWithContentDescription("唤出阅读操作").performClick();compose.onNodeWithText("设置").performClick();compose.onNodeWithText("字体、边距、常亮 · 更多设置").performClick();compose.onNodeWithText("恢复系统字体").assertExists();capture("novel-options")}
        finally{compose.activityRule.scenario.onActivity{it.setContent{Text("字体验收完成")}};NovelPreferences(context).fontFile="";File(context.filesDir,"reader-fonts/$name").delete()}
    }
    @Test fun automaticScrollingStopsWhenAppGoesToBackground(){
        val m=model();val entry=Demo.entries.last().copy(id="auto010-${System.nanoTime()}").also{generated+=it};NovelPreferences(context).apply{turnMode=NovelTurnMode.SCROLL;autoSpeed=80f;position(entry.key,0,0,0)}
        show(m,entry);compose.onNodeWithContentDescription("唤出阅读操作").performClick();compose.onNodeWithText("设置").performClick();compose.onNodeWithText("字体、边距、常亮 · 更多设置").performClick();compose.onNodeWithText("开始自动滚动").performScrollTo().performClick()
        compose.waitUntil(5000){NovelPreferences(context).position(entry.key,0).let{it.first>0||it.second>0}}
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED);val stopped=NovelPreferences(context).position(entry.key,0)
        Thread.sleep(500);compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);Thread.sleep(600)
        assertEquals(stopped,NovelPreferences(context).position(entry.key,0))
    }
}
