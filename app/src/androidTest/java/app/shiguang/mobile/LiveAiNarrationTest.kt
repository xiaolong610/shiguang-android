package app.shiguang.mobile

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveAiNarrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun actualPrivateModelSynthesizesAndPlaysOnAndroid(){
        assumeTrue(InstrumentationRegistry.getArguments().getString("liveAi")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(context.getExternalFilesDir(null),"ai-test-config.json")
        val imported=AiPairing.parse(file.readText());file.delete()
        val config=imported.copy(style="sad",revision="live-${System.currentTimeMillis()}")
        AiVoiceSettings(context).save(config)
        val text="林夏拆开信，读到最后一句时，忽然停了下来。海风吹过，她把信轻轻贴在胸口。\n她看了看远方，想起海边的邮局，也想起那年春天的风。\n第二封信没有署名。她坐在窗边，认真读完，又把信纸折好，放进抽屉。那天傍晚，灯一直亮着。"
        val detail=Detail(Demo.entries.last(),listOf(Line("目录",listOf(Chapter("真实模型朗读","demo:novel:1")))))
        try{
            compose.activityRule.scenario.onActivity{Narration.start(it,Demo.sources.last(),detail,0,0,text,0,"ai")}
            compose.waitUntil(90000){Narration.state.value.playing||(!Narration.state.value.active&&Narration.state.value.entry!=null)}
            assertTrue("真实 AI 未播放：${Narration.state.value.message}",Narration.state.value.playing)
            val firstOffset=Narration.state.value.offset
            compose.waitUntil(90000){Narration.state.value.offset>firstOffset}
            assertEquals("ai",Narration.state.value.mode)
            Narration.command(context,"pause");compose.waitUntil(5000){!Narration.state.value.active}
            val pausedOffset=Narration.state.value.offset
            Narration.command(context,"play");compose.waitUntil(10000){Narration.state.value.playing}
            File(context.getExternalFilesDir(null),"live-ai-result.json").writeText(JSONObject().put("realModel",true).put("style",Narration.state.value.style).put("mode",Narration.state.value.mode).put("firstOffset",firstOffset).put("advancedOffset",pausedOffset).put("resumed",Narration.state.value.playing).put("keyStoredEncrypted",!context.getSharedPreferences("ai-voice",0).all.values.any{it.toString().contains(config.token)}).toString(2))
        }finally{Narration.command(context,"stop");AiVoiceSettings(context).clear()}
    }
}
