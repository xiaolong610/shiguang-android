package app.shiguang.mobile

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

data class NovelHit(val chapter:Int,val paragraph:Int,val offset:Int,val snippet:String)
object NovelSearch {
    fun hits(text:String,query:String,chapter:Int=0,limit:Int=200):List<NovelHit> {
        val q=query.trim();if(q.isEmpty())return emptyList()
        val result=mutableListOf<NovelHit>();var at=0
        while(result.size<limit){at=text.indexOf(q,at,ignoreCase=true);if(at<0)break
            var begin=(at-24).coerceAtLeast(0);var end=(at+q.length+36).coerceAtMost(text.length)
            if(begin>0&&Character.isLowSurrogate(text[begin]))begin--
            if(end<text.length&&end>0&&Character.isHighSurrogate(text[end-1]))end++
            result+=NovelHit(chapter,NovelText.paragraphAt(text,at),at,text.substring(begin,end).replace('\n',' '))
            at+=q.length
        };return result
    }
    suspend fun local(context:Context,entry:Entry,query:String,onProgress:(Int,Int)->Unit={_,_->}):List<NovelHit> = withContext(Dispatchers.IO){
        require(query.trim().isNotEmpty()){"请输入搜索关键词"}
        require(entry.sourceId==LocalNovels.source.id){"全书搜索仅用于本地导入小说"}
        require(entry.id.matches(Regex("[a-f0-9]{24}")))
        val directory=File(context.filesDir,"novels");val manifest=JSONObject(File(directory,"${entry.id}.json").readText());val chapters=manifest.getJSONArray("chapters")
        val results=mutableListOf<NovelHit>()
        for(index in 0 until chapters.length()){
            ensureActive();val value=chapters.getJSONObject(index)
            val body=if(value.has("text"))value.getString("text")else{val relative=value.getString("file");require(relative.matches(Regex("chapters/[0-9]+\\.txt")));File(directory,"${entry.id}/$relative").readText()}
            results+=hits(body,query,index,200-results.size)
            if(index%20==0||index==chapters.length()-1)onProgress(index+1,chapters.length())
            if(results.size>=200)break
        };results
    }
}
object NovelFonts {
    suspend fun import(context:Context,uri:Uri):String=withContext(Dispatchers.IO){
        val directory=File(context.filesDir,"reader-fonts").apply{mkdirs()}
        val temp=File(directory,".font-${java.util.UUID.randomUUID()}")
        try{
            context.contentResolver.openInputStream(uri)?.use{input->temp.outputStream().use{output->
                val buffer=ByteArray(65536);var bytes=0;while(true){ensureActive();val n=input.read(buffer);if(n<0)break;bytes+=n;require(bytes<=20*1024*1024){"字体文件最大20MiB"};output.write(buffer,0,n)}
            }}?:error("无法读取字体")
            require(temp.length()>4){"字体文件为空或不完整"}
            val magic=ByteArray(4);java.io.DataInputStream(temp.inputStream()).use{it.readFully(magic)}
            require(magic.contentEquals(byteArrayOf(0,1,0,0))||String(magic,Charsets.ISO_8859_1) in listOf("OTTO","ttcf","true")){"请选择有效的TTF或OTF字体"}
            Typeface.createFromFile(temp)
            val name=java.util.UUID.randomUUID().toString()+".font";check(temp.renameTo(File(directory,name))){"保存字体失败"};name
        }finally{temp.delete()}
    }
}

@Composable fun NovelSearchPanel(context:Context,entry:Entry,chapters:List<Chapter>,current:Int,text:String,onPick:(NovelHit)->Unit){
    var query by remember{mutableStateOf("")};var full by remember{mutableStateOf(false)}
    var results by remember{mutableStateOf(emptyList<NovelHit>())};var busy by remember{mutableStateOf(false)};var message by remember{mutableStateOf("")}
    var requestId by remember{mutableIntStateOf(0)};var job by remember{mutableStateOf<Job?>(null)};val scope=rememberCoroutineScope();val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    DisposableEffect(Unit){onDispose{job?.cancel()}}
    fun search(){keyboard?.hide();job?.cancel();val request=++requestId;val term=query.trim();if(term.isEmpty())return;results=emptyList();busy=true;message="正在查找…";job=scope.launch{try{
        val found=if(full)NovelSearch.local(context,entry,term){done,total->scope.launch{if(request==requestId)message="正在搜索 $done / $total 章"}}else withContext(Dispatchers.Default){NovelSearch.hits(text,term,current)}
        if(request!=requestId)return@launch;results=found
        message=if(results.isEmpty())"没有找到匹配内容"else"找到 ${results.size} 处"+if(results.size>=200)"，仅显示前200处，请缩小关键词"else""
    }catch(e:CancellationException){throw e}catch(e:Exception){if(request==requestId)message=ContentRepository.friendlyError(e)}finally{if(request==requestId)busy=false}}}
    Column(Modifier.fillMaxWidth()){
        Text("搜索正文",fontSize=21.sp)
        Row{FilterChip(!full,{full=false;requestId++;job?.cancel();busy=false;results=emptyList()},label={Text("本章")});Spacer(Modifier.width(8.dp));FilterChip(full,{full=true;requestId++;job?.cancel();busy=false;results=emptyList()},enabled=entry.sourceId==LocalNovels.source.id,label={Text("全书 · 本地")})}
        OutlinedTextField(query,{query=it.take(200);requestId++;job?.cancel();busy=false;results=emptyList();message=""},singleLine=true,label={Text("正文关键词")},modifier=Modifier.fillMaxWidth())
        Row{TextButton(onClick={search()},enabled=query.isNotBlank()){Text("开始搜索")};if(busy)TextButton(onClick={requestId++;job?.cancel();busy=false;message="已停止搜索"}){Text("停止")}}
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(message,fontSize=11.sp)
        LazyColumn(Modifier.heightIn(max=350.dp),contentPadding=PaddingValues(bottom=20.dp)){
            items(results,key={"${it.chapter}:${it.offset}"}){hit->Column(Modifier.fillMaxWidth().clickable{onPick(hit)}.padding(vertical=12.dp)){Text(chapters.getOrNull(hit.chapter)?.title.orEmpty(),fontSize=13.sp);Text(hit.snippet,fontSize=12.sp,maxLines=3,overflow=TextOverflow.Ellipsis);HorizontalDivider(Modifier.padding(top=10.dp))}}
        }
    }
}
