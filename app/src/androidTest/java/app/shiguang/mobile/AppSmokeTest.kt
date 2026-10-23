package app.shiguang.mobile

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun capture(name:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val file=java.io.File(instrumentation.targetContext.getExternalFilesDir(null),name)
        instrumentation.uiAutomation.takeScreenshot()?.let {bitmap->file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
    }
    @Test fun canFavoriteAndReadOfflineNovel() {
        compose.openDemoNovel()
        compose.waitUntil(10000) {compose.onAllNodesWithText("开始阅读").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("继续阅读").fetchSemanticsNodes().isNotEmpty()}
        val alreadySaved=LibraryStore(InstrumentationRegistry.getInstrumentation().targetContext).use {store->store.favorites().any{it.key==Demo.entries.last().key}}
        if(!alreadySaved)compose.onNodeWithText("加入收藏").performClick()
        compose.onAllNodesWithText("第一章 · 蓝色信箱").onFirst().performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("海边的邮局在下午四点关门。",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("海边的邮局在下午四点关门。",substring=true).performTouchInput{click()}
        capture("novel.png")
        compose.onNodeWithContentDescription("阅读进度").performClick()
        compose.onNodeWithText("下一章").performClick()
        compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
        compose.waitUntil(10000) {compose.onAllNodesWithText("入秋后，小镇的游客少了。",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithContentDescription("书架",useUnmergedTree=true).performClick()
        compose.onAllNodesWithText("把今天寄给明天").onFirst().assertExists()
    }
    @Test fun canReadOfflineComic() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        // Old migration fixtures share the demo title and now appear in history-backed discovery.
        LibraryStore(context).use{it.writableDatabase.delete("progress","id LIKE ?",arrayOf("demo-COMIC::legacy-%"))}
        compose.activityRule.scenario.onActivity{androidx.lifecycle.ViewModelProvider(it)[AppModel::class.java].refreshLibrary()}
        context.getSharedPreferences("reader",0).edit().putString("comicTurnMode",ComicTurnMode.SCROLL.name).putString("comic:${Demo.entries[1].key}:0:0","0:0").apply()
        compose.onNodeWithText("漫画").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("月亮便利店").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("月亮便利店").onLast().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithText("第 1 话 · 今晚也有光").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("第 1 话 · 今晚也有光").performScrollTo().performClick()
        try{compose.waitUntil(10000) {compose.onAllNodesWithContentDescription("第 1 页").fetchSemanticsNodes().isNotEmpty()}}
        finally{capture("comic-load-check.png")}
        Thread.sleep(700)
        compose.onNodeWithContentDescription("第 1 页").assertIsDisplayed()
        capture("comic.png")
    }
    @Test fun sourceImportPersistsAndCanBeDisabled() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val source=SourceCodec.parse("https://example.com/api").single()
        LibraryStore(context).use {store->store.putSource(source.copy(enabled=false))}
        LibraryStore(context).use {store->
            assertFalse(store.sources().first{it.id==source.id}.enabled)
            val entry=Demo.entries.last();store.save(entry,0,1,157)
            assertEquals(157L,store.progress().first{it.entry.key==entry.key}.offset)
            store.deleteSource(source.id)
        }
    }
}


