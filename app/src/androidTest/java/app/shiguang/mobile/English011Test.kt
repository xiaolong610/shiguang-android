package app.shiguang.mobile

import android.app.LocaleManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class English011Test {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val ins get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=ins.targetContext
    private fun language(tag:String){
        ins.runOnMainSync{context.getSystemService(LocaleManager::class.java).applicationLocales=LocaleList.forLanguageTags(tag)}
        ins.waitForIdleSync();AppLanguage.refresh()
        compose.waitUntil(10000){AppLanguage.locale.language==tag.substringBefore('-')}
    }
    @Before fun english(){language("en")}
    @After fun chinese(){language("zh-CN")}
    private fun model():AppModel {lateinit var m:AppModel;compose.activityRule.scenario.onActivity{m=ViewModelProvider(it)[AppModel::class.java]};compose.waitUntil(15000){m.libraryIdle};return m}
    private fun capture(name:String){compose.waitForIdle();ins.waitForIdleSync();Thread.sleep(150);ins.uiAutomation.takeScreenshot()!!.let{b->File(context.getExternalFilesDir(null),"english011-$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    @Test fun everyMessageFormatsInBothLanguagesWithoutMissingArguments(){
        for(tag in listOf("en","zh-CN")){
            val resources=context.createConfigurationContext(Configuration(context.resources.configuration).apply{setLocales(LocaleList.forLanguageTags(tag))}).resources
            textFallbacks.keys.forEach{id->
                val raw=resources.getString(id)
                if(tag=="en")assertFalse(resources.getResourceEntryName(id),raw.any{it in '\u3400'..'\u9fff'})
                if(raw.contains("%1")){
                    val value=resources.getString(id,*Array(12){"ARG${it+1}"})
                    assertFalse(value,value.contains("%1"))
                }
            }
        }
        assertEquals("Comics",ContentCategory.COMIC.label)
        assertEquals("Horizontal swipe",NovelTurnMode.SLIDE.label)
        assertEquals("Anime",SourceCodec.parse("""{"name":"动画源","kind":"anime","engine":"maccms","url":"https://example.com/api.php"}""").single().category.label)
    }
    @Test fun languageMenuSwitchesBothWaysAndKeepsSavedChineseMetadata(){
        val m=model();val entry=Entry("locale011","test-locale011","保留原始作品名",Kind.NOVEL,"https://example.com/book")
        try{
            LibraryStore(context).use{it.favorite(entry,true)}
            compose.onNodeWithContentDescription("Add").performClick()
            compose.onNodeWithText("Language").performClick()
            compose.onNodeWithText("简体中文").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithContentDescription("发现",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            assertEquals("漫画",ContentCategory.COMIC.label)
            compose.onNodeWithContentDescription("添加").performClick()
            compose.onNodeWithText("语言 / Language").performClick();compose.onNodeWithText("English").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithContentDescription("Discover",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
            assertEquals("Comics",ContentCategory.COMIC.label)
            assertEquals(entry,LibraryStore(context).use{it.favorites().single{saved->saved.key==entry.key}})
            capture("discover")
        }finally{LibraryStore(context).use{it.favorite(entry,false)}}
    }
    @Test fun englishLibrarySourcesImportAndCacheControlsFitAndRemainUsable(){
        model()
        compose.onNodeWithContentDescription("Library",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Favorites").assertIsDisplayed();capture("library")
        compose.onNodeWithContentDescription("Sources",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("Choose a source category").performClick();compose.onNodeWithText("Anime · 0").performClick()
        compose.onNodeWithText("No rules in this category").assertExists()
        compose.onNodeWithContentDescription("Choose a source category").performClick();compose.onNodeWithText("All types · ${model().sources.size}").performClick();capture("sources")
        compose.onNodeWithContentDescription("Import sources").performClick();compose.onNodeWithText("Import JSON rules").assertIsDisplayed();capture("import")
        compose.onNodeWithContentDescription("Close import").performClick()
        compose.onNodeWithText("Subscriptions",useUnmergedTree=true).performClick();compose.onNodeWithText("No subscriptions yet. Tap + in the top-right corner to add one.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Discover",useUnmergedTree=true).performClick();compose.onNodeWithContentDescription("Add").performClick();compose.onNodeWithText("Downloads & cache").performClick()
        compose.onNodeWithText("Download over Wi-Fi only").assertIsDisplayed();capture("downloads")
        compose.onNodeWithText("Cache manager").performClick();compose.onNodeWithText("Covers & comic images").assertIsDisplayed();capture("cache")
    }
    @Test fun englishLocalBookSearchAndReaderSettingsPreserveChapterPositions()=runBlocking {
        val m=model();val f=File(context.cacheDir,"English011.txt").apply{writeText("Chapter 1 Start\n\nOrdinary text ${System.nanoTime()}.\n\nChapter 2 Lighthouse\n\nThe silver lighthouse is here.\n\nChapter 3 Home\n\nA quiet ending.")}
        val entry=LocalNovels(context).import(Uri.fromFile(f));val detail=LocalNovels(context).detail(entry)
        try{
            compose.activityRule.scenario.onActivity{it.setContent{ShiguangTheme{Box(Modifier.safeDrawingPadding()){NovelDetailScreen(m,entry,{}, {_,_,_->},{_,_,_->})}}}}
            compose.waitUntil(10000){compose.onAllNodesWithText("Chapter 2 Lighthouse").fetchSemanticsNodes().isNotEmpty()};capture("book")
            compose.activityRule.scenario.onActivity{it.setContent{ShiguangTheme{NovelReaderScreen(m,detail,0,0,{})}}}
            compose.waitUntil(10000){compose.onAllNodesWithContentDescription("Show reader controls").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithContentDescription("Show reader controls").performClick();compose.onNodeWithContentDescription("Search text").performClick()
            compose.onNodeWithText("Full book · Local").performClick();compose.onNodeWithText("Text keyword").performTextInput("silver lighthouse");compose.onNodeWithText("Search").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("Matches: 1").fetchSemanticsNodes().isNotEmpty()};capture("search")
            compose.onNodeWithText("The silver lighthouse is here.",substring=true).performClick()
            compose.waitUntil(10000){LibraryStore(context).use{it.progressFor(entry.key)?.chapter==1}}
            compose.onNodeWithContentDescription("Show reader controls").performClick();compose.onNodeWithText("Settings").performClick();compose.onNodeWithText("Reading settings").assertIsDisplayed();capture("reader")
            compose.onNodeWithText("Font, margins & screen · More").performScrollTo().performClick();compose.onNodeWithText("Volume keys turn pages").assertIsDisplayed();capture("reader-more")
        }finally{
            compose.activityRule.scenario.onActivity{it.setContent{Text("Done")}}
            LibraryStore(context).use{it.writableDatabase.delete("progress","id=?",arrayOf(entry.key))}
            File(context.filesDir,"novels/${entry.id}.json").delete();val dir=File(context.filesDir,"novels/${entry.id}");require(entry.id.matches(Regex("[a-f0-9]{24}")));dir.deleteRecursively();f.delete()
        }
    }
    @Test fun englishPlayerAndComicSettingsExposeDownloadSortAndPageModes(){
        val m=model();val entry=Demo.entries.first();val detail=Demo.detail(entry)
        compose.activityRule.scenario.onActivity{it.setContent{ShiguangTheme{CompactPlayerScreen(PlaybackInfo(entry=entry,pages=detail.lines.first().chapters,total=1),null,false,false,true,true,null,{},{},{},{},{},{},{},{},{},{})}}}
        compose.onNodeWithContentDescription("Sort episodes").assertIsDisplayed();compose.onNodeWithContentDescription("Playback settings").performClick()
        compose.onNodeWithText("Background playback").assertIsDisplayed();compose.onNodeWithText("Download this episode").assertExists();capture("player")
        compose.activityRule.scenario.onActivity{it.setContent{ShiguangTheme{ComicReaderScreen(m,Demo.detail(Demo.entries[1]),0,0,{})}}}
        compose.waitUntil(10000){compose.onAllNodesWithContentDescription("Show comic controls").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Show comic controls").performClick();compose.onNodeWithText("Settings").performClick();compose.onNodeWithText("Right-to-left").assertIsDisplayed();capture("comic")
    }
}
