package app.shiguang.mobile

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovelReaderTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun capture(name:String){compose.waitForIdle();instrumentation.waitForIdleSync();Thread.sleep(400);instrumentation.uiAutomation.takeScreenshot()?.let{b->java.io.File(instrumentation.targetContext.getExternalFilesDir(null),name).outputStream().use{b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
    private fun open(){compose.openDemoNovel();compose.waitUntil(15000){compose.onAllNodesWithText("第一章 · 蓝色信箱").fetchSemanticsNodes().isNotEmpty()};capture("novel-0.4-detail.png");compose.onNodeWithText("第一章 · 蓝色信箱").performScrollTo().performClick();compose.waitUntil(15000){compose.onAllNodesWithText("海边的邮局在下午四点关门。",substring=true).fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("海边的邮局在下午四点关门。",substring=true).performTouchInput{click()}}
    private fun dismiss(){compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()};compose.waitForIdle()}
    @Test fun readerSettingsBookmarksDirectoryAndPositionPersist(){
        val p=instrumentation.targetContext.getSharedPreferences("novel-reading",0);p.edit().remove("marks:${Demo.entries.last().key}").putInt("theme",0).apply()
        open();capture("novel-0.4-reader.png")
        compose.onNodeWithText("海边的邮局在下午四点关门。",substring=true).performTouchInput{click()}
        compose.onNodeWithText("目录").assertDoesNotExist()
        compose.onNodeWithText("海边的邮局在下午四点关门。",substring=true).performTouchInput{click()}
        compose.onNodeWithText("目录").assertIsDisplayed()
        compose.onNodeWithContentDescription("添加或移除书签").performClick()
        compose.onNodeWithText("设置").performClick();compose.onNodeWithText("阅读设置").assertIsDisplayed()
        compose.onNodeWithText("亮度").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("阅读设置").performScrollTo();capture("novel-0.4-settings.png")
        compose.onNodeWithText("夜").performClick();assertEquals(3,NovelPreferences(instrumentation.targetContext).theme);dismiss()
        compose.onNodeWithText("目录").performClick();compose.onNodeWithText("书签 1").performClick();compose.onNodeWithText("查看目录").assertExists();dismiss()
        compose.onNodeWithContentDescription("阅读进度").performClick();compose.onNodeWithText("下一章").performClick();dismiss()
        compose.waitUntil(15000){compose.onAllNodesWithText("入秋后，小镇的游客少了。",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000){compose.onAllNodesWithText("入秋后，小镇的游客少了。",substring=true).fetchSemanticsNodes().isNotEmpty()}
        capture("novel-0.4-night.png")
        compose.onNodeWithText("设置").performClick();compose.onNodeWithText("白").performClick();dismiss()
        compose.onNodeWithText("听书").performClick();compose.onNodeWithText("手机离线语音 · 文字不发送到云端").assertExists();capture("novel-0.4-listen.png");dismiss()
        compose.onNodeWithContentDescription("返回").performClick()
        assertEquals(1,LibraryStore(instrumentation.targetContext).use{it.progress().first{p->p.entry.key==Demo.entries.last().key}.chapter})
        compose.onNodeWithContentDescription("返回").performClick();compose.onNodeWithContentDescription("书架",useUnmergedTree=true).performClick();compose.onNodeWithText("最近",substring=true).performClick();capture("novel-0.4-shelf.png")
    }
    @Test fun realOfflineNarrationChangesChapterAndSurvivesBackground(){
        val context=instrumentation.targetContext
        val offlineRequired=InstrumentationRegistry.getArguments().getString("requireOffline")=="true"
        if(offlineRequired)assertNull("测试要求设备断网",context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork)
        val detail=Demo.detail(Demo.entries.last());val source=Demo.sources.last()
        compose.activityRule.scenario.onActivity{Narration.start(it,source,detail,0,0,"这是拾光的离线听书测试。",0)}
        compose.waitUntil(25000){val s=Narration.state.value;s.playing||(!s.loading&&s.message.contains("不可用"))||s.message.contains("未找到")}
        assertTrue("真实手机语音未开始：${Narration.state.value.message}",Narration.state.value.playing)
        compose.waitUntil(30000){Narration.state.value.chapter==1}
        compose.waitUntil(15000){Narration.state.value.playing}
        context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(2000);assertTrue("后台听书停止",Narration.state.value.playing)
        Narration.command(context,"pause");compose.waitUntil(5000){!Narration.state.value.playing}
        Narration.command(context,"rate","1.3");compose.waitUntil(5000){Narration.state.value.rate==1.3f}
        Narration.command(context,"sleep","15");compose.waitUntil(5000){Narration.state.value.sleepUntil>System.currentTimeMillis()}
        Narration.command(context,"play");compose.waitUntil(15000){Narration.state.value.playing}
        val state=Narration.state.value
        java.io.File(context.getExternalFilesDir(null),"narration-test-result.json").writeText(org.json.JSONObject().put("offlineRequired",offlineRequired).put("activeNetwork",context.getSystemService(android.net.ConnectivityManager::class.java).activeNetwork?.toString()?:org.json.JSONObject.NULL).put("voice",state.voice).put("voiceCount",state.voices.size).put("chapter",state.chapter).put("playingAfterBackgroundAndResume",state.playing).put("rate",state.rate).put("sleepUntil",state.sleepUntil).toString(2))
        Narration.command(context,"stop");compose.waitUntil(5000){Narration.state.value.entry==null}
    }
    @Test fun mediaNotificationAudioInterruptionAndSleepExpiryWork(){
        val context=instrumentation.targetContext
        compose.activityRule.scenario.onActivity{Narration.start(it,Demo.sources.last(),Demo.detail(Demo.entries.last()),0,0,"这是持续朗读的定时测试段落，手机会在定时结束后自动停止朗读。".repeat(100),0)}
        try{
            compose.waitUntil(20000){Narration.state.value.playing}
            val notification=context.getSystemService(android.app.NotificationManager::class.java).activeNotifications.firstOrNull{it.id==74}?.notification
            assertNotNull("未显示媒体通知",notification);assertTrue(notification!!.extras.containsKey(android.app.Notification.EXTRA_MEDIA_SESSION))
            val audio=context.getSystemService(android.media.AudioManager::class.java)
            val other=android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build()).setOnAudioFocusChangeListener{}.build()
            assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,audio.requestAudioFocus(other))
            compose.waitUntil(5000){!Narration.state.value.playing};assertTrue(Narration.state.value.message.contains("其他音频"));audio.abandonAudioFocusRequest(other)
            Narration.command(context,"play");compose.waitUntil(10000){Narration.state.value.playing}
            Narration.command(context,"sleep","1");compose.waitUntil(5000){Narration.state.value.sleepUntil>System.currentTimeMillis()}
            context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(75000){Narration.state.value.message=="定时结束"&&!Narration.state.value.playing}
            assertEquals(0L,Narration.state.value.sleepUntil)
        }finally{Narration.command(context,"stop")}
    }
}


