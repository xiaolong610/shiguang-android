package app.shiguang.mobile

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class AiNarrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun wave(seconds:Int=2):ByteArray {
        val samples=16000*seconds;val buffer=ByteBuffer.allocate(44+samples*2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray()).putInt(36+samples*2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(samples*2)
        repeat(samples){buffer.putShort((sin(it*2*Math.PI*220/16000)*1500).toInt().toShort())};return buffer.array()
    }
    private fun singleChapter()=Detail(Demo.entries.last(),listOf(Line("目录",listOf(Chapter("队列测试","demo:novel:1")))))
    private fun finish(){Narration.command(context,"stop");compose.waitUntil(5000){Narration.state.value.entry==null};AiVoiceSettings(context).clear()}
    private fun openNovelReader(){
        compose.openDemoNovel()
        compose.waitUntil(15000){compose.onAllNodes((hasText("开始阅读")or hasText("继续阅读"))and isEnabled()).fetchSemanticsNodes().isNotEmpty()}
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("第一章 · 蓝色信箱"))
        compose.onNodeWithText("第一章 · 蓝色信箱").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("海边的邮局在下午四点关门。",substring=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("海边的邮局在下午四点关门。",substring=true).performTouchInput{click()}
    }
    private fun server(texts:MutableList<String>,seconds:Int=2):MockWebServer=MockWebServer().apply{
        dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest):MockResponse {
            if(request.path=="/health")return MockResponse().setBody("{\"ready\":true,\"model\":\"test-model\",\"styles\":[\"natural\",\"happy\",\"sad\",\"angry\"],\"voices\":[\"synthetic-female\"]}")
            if(request.path=="/v1/speech"){texts+=JSONObject(request.body.readUtf8()).getString("text");return MockResponse().setHeader("Content-Type","audio/wav").setBody(Buffer().write(wave(seconds)))}
            return MockResponse().setResponseCode(404)
        }};start()
    }
    @Test fun pairedKeyIsEncryptedAndCanBeForgotten(){
        val key="device-key-"+System.nanoTime()+"-long-enough"
        val config=AiVoiceConfig("http://127.0.0.1:8765",key,"sad")
        val settings=AiVoiceSettings(context);settings.save(config)
        assertEquals(config,settings.load());assertFalse(context.getSharedPreferences("ai-voice",0).all.values.any{it.toString().contains(key)})
        settings.clear();assertNull(settings.load())
    }
    @Test fun playerCanPairAndSwitchVoiceModesWithoutLosingTheBook(){
        val texts=CopyOnWriteArrayList<String>()
        server(texts,6).use{server->
            try{
                val d=Demo.detail(Demo.entries.last());val text=Demo.page(d.entry,d.lines[0].chapters[0]).text
                compose.activityRule.scenario.onActivity{Narration.start(it,Demo.sources.last(),d,0,0,text,0,"system")}
                compose.waitUntil(20000){Narration.state.value.playing};Narration.command(context,"pause");compose.waitUntil(5000){!Narration.state.value.playing}
                compose.onAllNodesWithText(d.entry.title).onFirst().performClick()
                compose.onNodeWithContentDescription("听书设置").performClick();compose.onNodeWithText("配对电脑 AI 语音").performClick()
                compose.onNodeWithText("语音服务地址").performTextInput(server.url("/").toString());compose.onNodeWithText("设备配对密钥").performTextInput("a".repeat(32))
                compose.onNodeWithText("保存并开始AI听书").performClick();compose.waitUntil(15000){Narration.state.value.playing&&Narration.state.value.mode=="ai"}
                assertEquals(d.entry.key,Narration.state.value.entry?.key);assertEquals(0,Narration.state.value.chapter)
                compose.onNodeWithContentDescription("听书设置").performClick();compose.onNodeWithText("切换到手机离线语音").performClick()
                compose.waitUntil(15000){Narration.state.value.playing&&Narration.state.value.mode=="system"}
                assertEquals(d.entry.key,Narration.state.value.entry?.key);assertEquals(0,Narration.state.value.chapter)
            }finally{finish()}
        }
    }
    @Test fun aiSegmentsDecodePrefetchAndFinishWithoutRepeatingText(){
        val texts=CopyOnWriteArrayList<String>()
        server(texts).use{server->
            AiVoiceSettings(context).save(AiVoiceConfig(server.url("/").toString(),"a".repeat(32),"happy","synthetic-female","fixture-${System.nanoTime()}"))
            val text="第一段开始。"+"甲".repeat(60)+"第一段结束。\n第二段开始。"+"乙".repeat(60)+"第二段结束。\n第三段开始。"+"丙".repeat(60)+"第三段结束。"
            try{
                compose.activityRule.scenario.onActivity{Narration.start(it,Demo.sources.last(),singleChapter(),0,0,text,0,"ai")}
                compose.waitUntil(15000){Narration.state.value.playing}
                assertEquals("ai",Narration.state.value.mode);assertEquals("happy",Narration.state.value.style);assertTrue(Narration.state.value.voices.isEmpty())
                compose.waitUntil(5000){texts.size>=2};assertTrue("下一段未在当前段播放时预取",Narration.state.value.playing)
                context.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                compose.waitUntil(20000){Narration.state.value.message=="全书已听完"}
                assertEquals(NovelText.pieces(text,80).map{it.text},texts.toList());assertFalse(Narration.state.value.active)
            }finally{finish()}
        }
    }
    @Test fun aiPauseResumeKeepsCurrentAudioAndDoesNotResendTheCurrentSegment(){
        val texts=CopyOnWriteArrayList<String>()
        server(texts,6).use{server->
            AiVoiceSettings(context).save(AiVoiceConfig(server.url("/").toString(),"a".repeat(32),revision="pause-${System.nanoTime()}"))
            val text="暂停测试。"+"甲".repeat(140)+"。后续测试。"+"乙".repeat(140)
            try{
                compose.activityRule.scenario.onActivity{Narration.start(it,Demo.sources.last(),singleChapter(),0,0,text,0,"ai")}
                compose.waitUntil(15000){Narration.state.value.playing}
                val first=NovelText.pieces(text,80).first().text
                Narration.command(context,"pause");compose.waitUntil(5000){!Narration.state.value.active};val offset=Narration.state.value.offset
                Thread.sleep(250);assertFalse(Narration.state.value.playing);assertEquals(offset,Narration.state.value.offset)
                Narration.command(context,"play");compose.waitUntil(10000){Narration.state.value.playing};assertEquals(1,texts.count{it==first})
                Narration.command(context,"rate","1.4");compose.waitUntil(3000){Narration.state.value.rate==1.4f};assertTrue(Narration.state.value.playing)
                Narration.command(context,"pause");compose.waitUntil(3000){!Narration.state.value.active}
                Narration.command(context,"seek","100");compose.waitUntil(3000){Narration.state.value.offset==100}
                Narration.command(context,"play");compose.waitUntil(10000){Narration.state.value.playing&&Narration.state.value.offset>=100}
                assertEquals(1,texts.count{it==first})
            }finally{finish()}
        }
    }
    @Test fun serviceFailureStaysInAiUntilUserChoosesSystemVoice(){
        MockWebServer().use{server->
            server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest)=if(r.path=="/health")MockResponse().setBody("{\"ready\":true,\"styles\":[\"natural\"],\"voices\":[\"synthetic-female\"]}")else MockResponse().setResponseCode(503)}
            server.start();AiVoiceSettings(context).save(AiVoiceConfig(server.url("/").toString(),"a".repeat(32),revision="failure-${System.nanoTime()}"))
            try{
                compose.activityRule.scenario.onActivity{Narration.start(it,Demo.sources.last(),singleChapter(),0,0,"这是故障处理测试。",0,"ai")}
                compose.waitUntil(15000){Narration.state.value.entry!=null&&!Narration.state.value.active}
                assertEquals("ai",Narration.state.value.mode);assertFalse(Narration.state.value.playing);assertTrue(Narration.state.value.message.contains("503"))
                compose.activityRule.scenario.onActivity{Narration.start(it,Demo.sources.last(),singleChapter(),0,0,"这次由用户主动选择手机离线语音继续朗读。".repeat(10),0)}
                compose.waitUntil(15000){Narration.state.value.playing};assertEquals("system",Narration.state.value.mode)
            }finally{finish()}
        }
    }
    @Test fun busyModelRetriesAndTheChapterStillCompletes(){
        MockWebServer().use{server->
            val attempts=java.util.concurrent.atomic.AtomicInteger()
            server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{
                if(r.path=="/health")return MockResponse().setBody("{\"ready\":true,\"styles\":[\"natural\"],\"voices\":[\"synthetic-female\"]}")
                return if(attempts.incrementAndGet()==1)MockResponse().setResponseCode(429)else MockResponse().setBody(Buffer().write(wave()))
            }};server.start();AiVoiceSettings(context).save(AiVoiceConfig(server.url("/").toString(),"a".repeat(32),revision="busy-${System.nanoTime()}"))
            try{compose.activityRule.scenario.onActivity{Narration.start(it,Demo.sources.last(),singleChapter(),0,0,"模型忙时稍后重试。",0,"ai")};compose.waitUntil(15000){Narration.state.value.playing};compose.waitUntil(10000){Narration.state.value.message=="全书已听完"};assertEquals(2,attempts.get())}finally{finish()}
        }
    }
    @Test fun chapterChangeCancelsPendingOldAudioAndReadsTheNewChapter(){
        MockWebServer().use{server->
            val oldStarted=java.util.concurrent.CountDownLatch(1);val requested=CopyOnWriteArrayList<String>()
            server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse{
                if(r.path=="/health")return MockResponse().setBody("{\"ready\":true,\"styles\":[\"natural\"],\"voices\":[\"synthetic-female\"]}")
                if(r.path=="/new")return MockResponse().setBody("<div id='content'>这是新章节的内容。</div>")
                val text=JSONObject(r.body.readUtf8()).getString("text");requested+=text
                val response=MockResponse().setBody(Buffer().write(wave()))
                if(text.startsWith("旧章节")){oldStarted.countDown();response.setBodyDelay(3,TimeUnit.SECONDS)}
                return response
            }};server.start()
            val rules=JSONObject().put("searchUrl","/").put("list","a").put("title","a").put("chapters","a").put("content","#content")
            val source=Source(SourceCodec.stableId("chapter-switch"),"切章测试",Kind.NOVEL,"html",server.url("/").toString(),rules=rules.toString())
            val entry=Demo.entries.last().copy(sourceId=source.id)
            val detail=Detail(entry,listOf(Line("目录",listOf(Chapter("旧章",server.url("/old").toString()),Chapter("新章",server.url("/new").toString())))))
            AiVoiceSettings(context).save(AiVoiceConfig(server.url("/").toString(),"a".repeat(32),revision="switch-${System.nanoTime()}"))
            try{
                compose.activityRule.scenario.onActivity{Narration.start(it,source,detail,0,0,"旧章节正在等待合成。",0,"ai")}
                assertTrue(oldStarted.await(10,TimeUnit.SECONDS));Narration.command(context,"next")
                compose.waitUntil(15000){Narration.state.value.chapter==1&&Narration.state.value.playing}
                compose.waitUntil(10000){Narration.state.value.message=="全书已听完"};Thread.sleep(1200)
                assertEquals(1,Narration.state.value.chapter);assertFalse(Narration.state.value.playing);assertTrue(requested.any{it=="这是新章节的内容。"})
            }finally{finish()}
        }
    }
    @Test fun pairingScreenTestsServiceAndStartsAiOnlyAfterExplicitAction(){
        val texts=CopyOnWriteArrayList<String>()
        server(texts,6).use{server->
            AiVoiceSettings(context).clear()
            try{
                openNovelReader()
                compose.onNodeWithText("听书").performClick();compose.onNodeWithText("AI 情感听书").performScrollTo().performClick()
                compose.onNodeWithText("语音服务地址").performTextInput(server.url("/").toString());compose.onNodeWithText("设备配对密钥").performTextInput("a".repeat(32));compose.onNodeWithText("伤感").performClick()
                compose.onNodeWithText("测试连接").performScrollTo().performClick();compose.waitUntil(10000){compose.onAllNodesWithText("连接成功",substring=true).fetchSemanticsNodes().isNotEmpty()}
                assertTrue(texts.isEmpty());assertNull(AiVoiceSettings(context).load())
                compose.onNodeWithText("保存并开始AI听书").performClick();compose.waitUntil(15000){Narration.state.value.playing&&Narration.state.value.mode=="ai"}
                assertEquals("sad",AiVoiceSettings(context).load()!!.style)
                compose.onNodeWithContentDescription("听书进度").assertExists()
                compose.onNodeWithContentDescription("暂停听书").performClick();compose.waitUntil(5000){!Narration.state.value.active}
                compose.onNodeWithContentDescription("继续听书").performClick();compose.waitUntil(10000){Narration.state.value.playing}
                compose.waitForIdle();Thread.sleep(300)
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let{bitmap->java.io.File(context.getExternalFilesDir(null),"ai-listening.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
            }finally{finish()}
        }
    }
    @Test fun generatedPairingDocumentLoadsThroughTheAndroidFilePicker(){
        val instrumentation=InstrumentationRegistry.getInstrumentation();val resolver=context.contentResolver
        val filename="ShiguangPairing-${System.nanoTime()}.json"
        val uri=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply{put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,filename);put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"application/json")})?:error("无法创建配对测试文件")
        val texts=CopyOnWriteArrayList<String>()
        server(texts).use{server->
            val document=JSONObject().put("format","shiguang-ai-pairing").put("version",1).put("url",server.url("/").toString()).put("token","a".repeat(32)).put("style","sad")
            resolver.openOutputStream(uri)!!.use{it.write(document.toString().toByteArray())};AiVoiceSettings(context).clear()
            fun find(node:android.view.accessibility.AccessibilityNodeInfo?,predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo?{
                if(node==null)return null;if(predicate(node))return node
                for(i in 0 until node.childCount)find(node.getChild(i),predicate)?.let{return it};return null
            }
            fun awaitNode(predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo?{
                val end=android.os.SystemClock.uptimeMillis()+8000
                while(android.os.SystemClock.uptimeMillis()<end){find(instrumentation.uiAutomation.rootInActiveWindow,predicate)?.let{return it};Thread.sleep(100)};return null
            }
            fun click(node:android.view.accessibility.AccessibilityNodeInfo){var target:android.view.accessibility.AccessibilityNodeInfo?=node;repeat(6){if(target?.isClickable==true){target!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK);return};target=target?.parent};error("文件选择项不可点击")}
            try{
                openNovelReader()
                compose.onNodeWithText("听书").performClick();compose.onNodeWithText("AI 情感听书").performScrollTo().performClick();compose.onNodeWithText("导入电脑配对文件").performScrollTo().performClick()
                var file=awaitNode{it.text?.toString()==filename}
                if(file==null){val roots=awaitNode{it.contentDescription?.toString()in listOf("Show roots","显示根目录","显示位置")};if(roots!=null)click(roots);val downloads=awaitNode{it.text?.toString()in listOf("Downloads","下载","下載")};if(downloads!=null)click(downloads);file=awaitNode{it.text?.toString()==filename}}
                assertNotNull("系统文件选择器没有找到配对文件",file);click(file!!)
                compose.waitUntil(10000){compose.onAllNodesWithText("已载入配对信息",substring=true).fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("测试连接").performScrollTo().performClick();compose.waitUntil(10000){compose.onAllNodesWithText("连接成功",substring=true).fetchSemanticsNodes().isNotEmpty()}
                assertTrue(texts.isEmpty());assertNull(AiVoiceSettings(context).load())
            }finally{resolver.delete(uri,null,null);AiVoiceSettings(context).clear()}
        }
    }
}

