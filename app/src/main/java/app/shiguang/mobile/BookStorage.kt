package app.shiguang.mobile

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.Reader
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Streaming text chunks and a small directory; never keep a complete large book in memory. */
class BookStorage(private val root:File,private val checkAlive:()->Unit={}) {
    private val chapters=JSONArray()
    private val pending=StringBuilder()
    private var title="正文"
    private var part=1
    private var chars=0L
    private val digest=MessageDigest.getInstance("SHA-256")
    var bookTitle:String=""
    var author:String=""
    var inputFormat:String=""
    init{root.mkdirs();File(root,"chapters").mkdirs()}
    fun startChapter(value:String){flush();title=value.trim().take(160).ifBlank{"正文"};part=1}
    fun append(value:String){
        checkAlive()
        var at=0
        while(at<value.length){
            val end=(at+4096).coerceAtMost(value.length)
            pending.append(value,at,end);at=end
            if(pending.length>=16000)flushChunk(false)
        }
    }
    fun paragraph(value:String){if(value.isNotBlank())append(value.trim()+"\n\n")}
    private fun flushChunk(all:Boolean){
        if(pending.isEmpty())return
        var end=if(all)pending.length else 12000
        if(!all){val boundary=(end-1 downTo 8000).firstOrNull{pending[it] in "\n。！？!?；;"};if(boundary!=null)end=boundary+1}
        if(end<pending.length&&Character.isHighSurrogate(pending[end-1])&&Character.isLowSurrogate(pending[end]))end--
        val text=pending.substring(0,end).trim();pending.delete(0,end)
        if(text.isBlank())return
        chars+=text.length
        require(chars<=240L*1024*1024){"解码后的正文过大，请分卷导入"}
        require(chapters.length()<24000){"章节超过24000，请分卷导入"}
        val filename="chapters/${chapters.length()}.txt"
        File(root,filename).writeText(text)
        val shown=if(part==1)title else "$title · $part"
        chapters.put(JSONObject().put("title",shown).put("file",filename))
        digest.update(shown.toByteArray());digest.update(0.toByte());digest.update(text.toByteArray());digest.update(0.toByte());part++
    }
    private fun flush(){flushChunk(true)}
    fun finish():Pair<String,JSONArray>{flush();require(chapters.length()>0){"未找到可阅读文字；扫描图片文件需要先识别为文字"};return digest.digest().take(12).joinToString(""){"%02x".format(it)} to chapters}
}

object BookText {
    private val heading=Regex("^(?:第[〇零一二三四五六七八九十百千万两0-9]{1,14}[章回节卷部篇].{0,65}|[Cc][Hh][Aa][Pp][Tt][Ee][Rr]\\s+[0-9IVXivx]+.{0,60})$")
    /** A line is capped: a 100MiB TXT with no newline cannot allocate a 100MiB String. */
    fun read(reader:Reader,sink:BookStorage,markdown:Boolean=false){
        val buffer=CharArray(8192);val line=StringBuilder();var continued=false;var first=true
        fun emit(newline:Boolean){
            val value=line.toString().removePrefix(if(first)"\uFEFF"else "").trimEnd('\r');first=false
            val md=markdown&&value.matches(Regex("^#{1,6}\\s+.+"))
            if(!continued&&(heading.matches(value.trim())||md))sink.startChapter(if(md)value.trimStart('#',' ')else value.trim())
            else sink.append(value+if(newline)"\n"else "")
            line.setLength(0);continued=!newline
        }
        while(true){val n=reader.read(buffer);if(n<0)break;for(i in 0 until n){val c=buffer[i];if(c=='\u0000')error("文件含二进制数据，请检查格式");if(c=='\n'){emit(true)}else{line.append(c);if(line.length>=8192)emit(false)}}}
        if(line.isNotEmpty())emit(true)
    }
    fun reader(file:File):Reader {
        val header=file.inputStream().use{val bytes=ByteArray(4);val n=it.read(bytes);if(n<0)byteArrayOf()else bytes.copyOf(n)}
        val encoding=when{
            header.size>=2&&header[0]==0xff.toByte()&&header[1]==0xfe.toByte()->Charsets.UTF_16LE
            header.size>=2&&header[0]==0xfe.toByte()&&header[1]==0xff.toByte()->Charsets.UTF_16BE
            else->{
                val valid=runCatching{InputStreamReader(file.inputStream(),Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).use{r->val b=CharArray(8192);while(r.read(b)>=0){}}}.isSuccess
                if(valid)Charsets.UTF_8 else charset("GB18030")
            }
        }
        return file.reader(encoding).buffered()
    }
}
