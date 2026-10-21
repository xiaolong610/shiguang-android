package app.shiguang.mobile

import android.content.Context
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File

/** Store chapter directories outside the Activity Bundle (large books can exceed Binder's limit). */
class ReaderNavigationCache(context:Context){
    private val directory=File(context.cacheDir,"reader-navigation").apply{mkdirs()}
    fun save(value:Triple<Detail,Int,Int>?):String {
        if(value==null)return ""
        val id=SourceCodec.stableId(value.first.entry.key)
        val data=JSONObject().put("detail",value.first.json()).put("line",value.second).put("chapter",value.third).toString().toByteArray()
        val file=AtomicFile(File(directory,"$id.json"));val stream=file.startWrite()
        try{stream.write(data);file.finishWrite(stream)}catch(e:Exception){file.failWrite(stream);throw e}
        return id
    }
    fun restore(id:String):Triple<Detail,Int,Int>?=runCatching{
        require(id.matches(Regex("[a-f0-9]{24}")))
        val value=JSONObject(AtomicFile(File(directory,"$id.json")).openRead().use{it.readBytes().toString(Charsets.UTF_8)})
        val detail=Detail.parse(value.getJSONObject("detail"));val line=value.getInt("line").coerceIn(detail.lines.indices)
        Triple(detail,line,value.getInt("chapter").coerceIn(detail.lines[line].chapters.indices))
    }.getOrNull()
}
