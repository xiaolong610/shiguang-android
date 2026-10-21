package app.shiguang.mobile

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

data class LocalNovelChapter(val title:String,val text:String)
object NovelImport {
    fun decode(bytes:ByteArray):String {
        require(bytes.isNotEmpty()){ "文件为空" }
        val decoded=when{
            bytes.size>=2&&bytes[0]==0xff.toByte()&&bytes[1]==0xfe.toByte()->String(bytes,Charsets.UTF_16LE).removePrefix("\uFEFF")
            bytes.size>=2&&bytes[0]==0xfe.toByte()&&bytes[1]==0xff.toByte()->String(bytes,Charsets.UTF_16BE).removePrefix("\uFEFF")
            else->runCatching{Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")}.getOrElse{String(bytes,charset("GB18030"))}
        }.replace("\r\n","\n").replace('\r','\n').trim()
        require(decoded.isNotBlank()&&!decoded.contains('\u0000')){"不是可读取的 TXT 文本，请检查文件格式"};return decoded
    }
    fun chapters(text:String):List<LocalNovelChapter>{
        val headings=Regex("(?m)^[ \\t]*(?:第[〇零一二三四五六七八九十百千万两0-9]{1,14}[章回节卷部篇][^\\n]{0,65}|[Cc][Hh][Aa][Pp][Tt][Ee][Rr]\\s+[0-9IVXivx]+[^\\n]{0,60})[ \\t]*$").findAll(text).toList()
        require(headings.size<=3000){"章节超过3000，请分卷导入"}
        val sections=mutableListOf<LocalNovelChapter>()
        if(headings.isEmpty())sections+=LocalNovelChapter("正文",text)
        else{
            if(headings.first().range.first>0){val intro=text.take(headings.first().range.first).trim();if(intro.isNotBlank())sections+=LocalNovelChapter("序言",intro)}
            headings.forEachIndexed{i,m->val end=headings.getOrNull(i+1)?.range?.first?:text.length;val body=text.substring(m.range.last+1,end).trim();sections+=LocalNovelChapter(m.value.trim(),body.ifBlank{m.value.trim()})}
        }
        return sections.flatMap{s->if(s.text.length<=20_000)listOf(s)else NovelText.pieces(s.text,12_000).mapIndexed{i,body->LocalNovelChapter("${s.title} · ${i+1}",body.text)}}.also{require(it.size<=3000){"正文过长，请分卷导入"}}
    }
}

class LocalNovels(private val context:Context){
    companion object {val source=Source("local-novels","本地书籍",Kind.NOVEL,"local")}
    private val directory get()=File(context.filesDir,"novels").apply{mkdirs()}
    fun entries():List<Entry> = directory.listFiles().orEmpty().filter{it.extension=="json"}.sortedByDescending{it.lastModified()}.mapNotNull{runCatching{Entry.parse(JSONObject(it.readText()).getJSONObject("entry"))}.getOrNull()}
    private fun read(id:String):JSONObject {require(id.matches(Regex("[a-f0-9]{24}")));return JSONObject(File(directory,"$id.json").readText())}
    fun detail(entry:Entry):Detail {val chapters=read(entry.id).getJSONArray("chapters");return Detail(entry,listOf(Line("目录",(0 until chapters.length()).map{Chapter(chapters.getJSONObject(it).getString("title"),"local:${entry.id}:$it")})))}
    fun page(entry:Entry,chapter:Chapter):PageContent {
        val prefix="local:${entry.id}:";require(chapter.url.startsWith(prefix));val index=chapter.url.removePrefix(prefix).toInt()
        val value=read(entry.id).getJSONArray("chapters").getJSONObject(index)
        if(value.has("text"))return PageContent(text=value.getString("text")) // Preserve 0.4/0.5 imports.
        val relative=value.getString("file");require(relative.matches(Regex("chapters/[0-9]+\\.txt")))
        return PageContent(text=File(directory,"${entry.id}/$relative").readText())
    }
    suspend fun import(uri:Uri):Entry=withContext(Dispatchers.IO){
        val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}.orEmpty().ifBlank{"我的小说.txt"}
        require(name.substringAfterLast('.',"").lowercase() in BookFormats.extensions){"不支持此格式。${BookFormats.LABEL}"}
        val coroutine=currentCoroutineContext()
        val work=File(directory,".import-${java.util.UUID.randomUUID()}").apply{mkdirs()}
        val raw=File(context.cacheDir,"novel-import-${java.util.UUID.randomUUID()}")
        try{
            context.contentResolver.openInputStream(uri)?.use{input->raw.outputStream().use{output->
                val buffer=ByteArray(65536);var count=0L;var n:Int
                while(input.read(buffer).also{n=it}>=0){coroutine.ensureActive();count+=n;require(count<=BookFormats.MAX_BYTES){"单个小说文件最大100MiB"};output.write(buffer,0,n)}
                require(count>0){"文件为空"}
            }}?:error("无法读取此文件")
            val sink=BookStorage(work){coroutine.ensureActive()}
            BookFormats.read(context,raw,name,sink,work)
            val (id,chapters)=sink.finish()
            val title=sink.bookTitle.ifBlank{name.substringBeforeLast('.',name)}.take(100)
            val entry=Entry(id,source.id,title,Kind.NOVEL,"local:$id",subtitle="本地导入 · ${chapters.length()}章",description=(sink.author.takeIf{it.isNotBlank()}?.let{"作者：$it\n"}?:"")+"保存在此设备的本地小说。支持阅读与离线语音朗读。")
            val file=File(directory,"$id.json")
            if(file.exists())return@withContext Entry.parse(JSONObject(file.readText()).getJSONObject("entry"))
            val target=File(directory,id);check(!target.exists()&&work.renameTo(target)){"保存本地书籍失败"}
            try{
                val data=JSONObject().put("format",2).put("inputFormat",sink.inputFormat).put("entry",entry.json()).put("chapters",chapters)
                val atomic=android.util.AtomicFile(file);val stream=atomic.startWrite()
                try{stream.write(data.toString().toByteArray());atomic.finishWrite(stream)}catch(e:Exception){atomic.failWrite(stream);throw e}
            }catch(e:Exception){target.deleteRecursively();throw e}
            entry
        }finally{raw.delete();work.deleteRecursively()}
    }
}
