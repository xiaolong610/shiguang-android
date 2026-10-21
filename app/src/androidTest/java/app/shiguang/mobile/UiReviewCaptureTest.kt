package app.shiguang.mobile

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Capture the shipped UI with offline examples, restoring the emulator's source and book lists. */
@RunWith(AndroidJUnit4::class)
@org.junit.Ignore("Historical 0.6.0 capture; Layout07Test covers the replacement UI")
class UiReviewCaptureTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=ins.targetContext
    private fun capture(name:String){compose.waitForIdle();ins.waitForIdleSync();Thread.sleep(550);ins.uiAutomation.takeScreenshot()!!.let{b->File(ctx.getExternalFilesDir(null),"ui-review-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun click(text:String){compose.onAllNodesWithText(text).onLast().performClick();compose.waitForIdle()}
    private fun back(){compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
    private fun waitText(text:String){compose.waitUntil(20000){compose.onAllNodesWithText(text,substring=true).fetchSemanticsNodes().isNotEmpty()}}
    private fun refresh(){compose.activityRule.scenario.onActivity{ViewModelProvider(it)[AppModel::class.java].refreshLibrary()};compose.waitForIdle()}
    @Test fun captureImportedSourceList(){
        val store=LibraryStore(ctx);val original=store.sources();val sources=SourceCodec.parse(File(ctx.getExternalFilesDir(null),"ui-review-sources.json").readText())
        try{store.putSources(sources);refresh();click("内容源");capture("06-sources");compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(sources.first().name));capture("24-sources-list")}
        finally{sources.filter{s->original.none{it.id==s.id}}.forEach{store.deleteSource(it.id)};store.putSources(original);store.close()}
    }
    @Test fun captureAllMainPages(){
        val store=LibraryStore(ctx);val oldSources=store.sources();val oldFavorites=store.favorites()
        val hidden=File(ctx.filesDir,"novels").listFiles().orEmpty().filter{it.extension=="json"}.map{it to File(it.parentFile,it.name+".ui-review-hidden")}
        try {
            hidden.forEach{(a,b)->check(a.renameTo(b))}
            oldSources.forEach{store.putSource(it.copy(enabled=false))}
            oldFavorites.forEach{store.favorite(it,false)};Demo.entries.forEach{store.favorite(it,true)}
            ctx.getSharedPreferences("novel-reading",0).edit().putInt("theme",0).apply()
            refresh();capture("01-discover-video")
            click("漫画");waitText("月亮便利店");capture("02-discover-comic")
            click("月亮便利店");waitText("第 1 话 · 今晚也有光");capture("08-comic-detail")
            compose.onNodeWithText("第 1 话 · 今晚也有光").performScrollTo().performClick()
            compose.waitUntil(15000){compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty()};capture("09-comic-reader");back();back()
            click("小说");waitText("把今天寄给明天");capture("03-discover-novel")
            click("把今天寄给明天");waitText("第一章 · 蓝色信箱");capture("10-novel-detail")
            compose.onNodeWithText("第一章 · 蓝色信箱").performScrollTo().performClick();waitText("海边的邮局在下午四点关门。")
            capture("11-novel-reader");click("沉浸");capture("12-reader-immersive")
            compose.onNodeWithText("海边的邮局在下午四点关门。",substring=true).performTouchInput{click()}
            click("目录");capture("13-reader-directory");back()
            click("进度");capture("14-reader-progress");back()
            click("设置");capture("15-reader-settings");click("夜");back();capture("16-reader-night")
            click("设置");click("白");back()
            click("听书");capture("17-listening-options")
            click("AI 情感听书");capture("18-ai-pairing");click("取消")
            click("从当前阅读位置开始");compose.waitUntil(25000){Narration.state.value.playing||Narration.state.value.message.contains("不可用")}
            if(Narration.state.value.playing){Narration.command(ctx,"pause");compose.waitUntil(5000){!Narration.state.value.playing};compose.onNodeWithText("打开听书页").performScrollTo().performClick();capture("19-listening-player");compose.onNodeWithContentDescription("收起听书页").performClick()}
            Narration.command(ctx,"stop");compose.waitUntil(5000){Narration.state.value.entry==null};back();repeat(3){if(compose.onAllNodesWithContentDescription("返回").fetchSemanticsNodes().isNotEmpty())compose.onNodeWithContentDescription("返回").performClick()}
            click("书架");capture("04-library-novel");click("全部");capture("05-library-all")
            click("发现");click("影视");waitText("山海之间");click("山海之间");waitText("选集");capture("07-video-detail");back()
            oldSources.forEach{store.putSource(it)};refresh();click("内容源");capture("06-sources")
            if(oldSources.isNotEmpty()){compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(oldSources.first().name));capture("24-sources-list")}
            click("导入内容源");capture("20-import-source");compose.onAllNodes(isToggleable()).onLast().performClick();capture("21-subscribe");click("取消")
        } finally {
            Narration.command(ctx,"stop");oldSources.forEach{store.putSource(it)}
            store.favorites().forEach{store.favorite(it,false)};oldFavorites.forEach{store.favorite(it,true)}
            hidden.forEach{(a,b)->if(b.exists())check(b.renameTo(a))};store.close()
        }
    }
}

@RunWith(AndroidJUnit4::class)
@org.junit.Ignore("Historical 0.6.0 capture; PlayerLayout07Test covers the replacement UI")
class UiReviewVideoCaptureTest {
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val ctx get()=ins.targetContext
    private fun capture(name:String){ins.waitForIdleSync();Thread.sleep(550);ins.uiAutomation.takeScreenshot()!!.let{b->File(ctx.getExternalFilesDir(null),"ui-review-$name.png").outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    @Test fun captureVideoPlayer(){
        val f=File(ctx.cacheDir,"ui-review-playback.mp4");ctx.assets.open("sample.mp4").use{input->f.outputStream().use{input.copyTo(it)}}
        ctx.getSharedPreferences("player",0).edit().putBoolean("autoPip",false).apply()
        val intent=Intent(ctx,PlayerActivity::class.java).putExtra("entry",Demo.entries.first().json().toString()).putExtra("url",android.net.Uri.fromFile(f).toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try{ActivityScenario.launch<PlayerActivity>(intent).use{s->val p=PlaybackTestSupport.await(s);s.onActivity{p.seekTo(3500);p.play()};Thread.sleep(1200);s.onActivity{p.pause();PlaybackTestSupport.view(it.window.decorView)!!.showController()};Thread.sleep(400);capture("22-video-player");s.onActivity{it.requestedOrientation=android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE};Thread.sleep(1200);s.onActivity{PlaybackTestSupport.view(it.window.decorView)!!.showController()};capture("23-video-landscape")}}finally{ctx.stopService(Intent(ctx,PlaybackService::class.java))}
    }
}
