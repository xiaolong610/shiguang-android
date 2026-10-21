package app.shiguang.mobile

import android.content.Intent
import android.os.Bundle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in audit of public rules. Kept separate from deterministic device tests. */
@RunWith(AndroidJUnit4::class)
class LiveSiteProbe {
    @Test fun auditSelectedSites()=runBlocking {
        val args=InstrumentationRegistry.getArguments();assumeTrue(args.getString("live")=="true")
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val input=File(context.getExternalFilesDir(null),"live-rules.json").readText()
        val rules=JSONArray(input);val results=JSONArray();val repo=ContentRepository()
        context.getSharedPreferences("player",0).edit().putBoolean("autoPip",false).putBoolean("backgroundPlayback",true).apply()
        for(i in 0 until rules.length()) {
            val raw=rules.getJSONObject(i);val result=JSONObject().put("name",raw.optString("name"));results.put(result)
            instrumentation.sendStatus(10,Bundle().apply{putString("probe",raw.optString("name")+"：搜索与详情")})
            try {
                val source=SourceCodec.parse(raw.toString()).single();val entries=repo.searchOne(source,"海贼王");result.put("searchCount",entries.size)
                val entry=entries.first();val detail=repo.detail(source,entry);val selectedLine=raw.optInt("probeLine",0).coerceIn(detail.lines.indices);val chosen=detail.lines[selectedLine];result.put("lines",detail.lines.size).put("episodes",chosen.chapters.size).put("selectedLine",selectedLine+1)
                val entryForProbe=entry.copy(id="live-${System.nanoTime()}")
                val intent=Intent(context,PlayerActivity::class.java).putExtra("entry",entryForProbe.json().toString()).putExtra("source",source.json().toString()).putExtra("url",chosen.chapters.first().url).putExtra("line",selectedLine)
                    .putExtra("playlist",JSONArray().apply{chosen.chapters.forEach{put(JSONObject().put("title",it.title).put("url",it.url))}}.toString()).putExtra("headers",JSONObject(repo.headers(source)).toString()).putExtra("knownAdSource",JSONObject(source.rules).optBoolean("knownAds")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                instrumentation.sendStatus(10,Bundle().apply{putString("probe",source.name+"：解析并检查原生首帧")})
                ActivityScenario.launch<PlayerActivity>(intent).use {scenario->
                    val until=System.currentTimeMillis()+60000
                    while(System.currentTimeMillis()<until) {
                        val info=PlaybackSession.state.value
                        if(info.entry?.key==entryForProbe.key&&(info.rendered||info.error!=null))break
                        Thread.sleep(300)
                    }
                    val info=PlaybackSession.state.value
                    result.put("rendered",info.entry?.key==entryForProbe.key&&info.rendered).put("error",info.error ?: "").put("status",info.status)
                    if(info.rendered) {
                        Thread.sleep(2500)
                        var position=0L;scenario.onActivity{position=PlaybackTestSupport.view(it.window.decorView)?.player?.currentPosition ?: 0};result.put("positionMs",position)
                        result.put("advanced",position>1000)
                        instrumentation.uiAutomation.takeScreenshot()?.let{bitmap->File(context.getExternalFilesDir(null),"live-${source.name}.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()}
                    }
                }
            }catch(e:Exception){result.put("error",ContentRepository.friendlyError(e))}
            finally{context.stopService(Intent(context,PlaybackService::class.java));Thread.sleep(300);File(context.getExternalFilesDir(null),"live-results.json").writeText(results.toString(2))}
        }
    }
}
