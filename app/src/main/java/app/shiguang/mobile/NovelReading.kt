package app.shiguang.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SpeechPiece(val text:String,val start:Int,val end:Int)
object NovelText {
    fun paragraphs(text:String)=text.replace("\r\n","\n").split(Regex("\n+" )).map{it.trim()}.filter{it.isNotEmpty()}
    // Bound every request well below the platform limit; retain exact offsets and surrogate pairs.
    fun pieces(text:String,limit:Int=280):List<SpeechPiece> {
        require(limit>=2)
        val out=mutableListOf<SpeechPiece>();var begin=0
        while(begin<text.length){
            while(begin<text.length&&text[begin].isWhitespace())begin++
            if(begin==text.length)break
            var end=(begin+limit).coerceAtMost(text.length)
            if(end<text.length){
                if(Character.isHighSurrogate(text[end-1])&&Character.isLowSurrogate(text[end]))end--
                val boundary=(end-1 downTo begin+limit/3).firstOrNull{text[it] in "。！？!?；;\n"}
                if(boundary!=null)end=boundary+1
            }
            out+=SpeechPiece(text.substring(begin,end),begin,end);begin=end
        };return out
    }
    fun paragraphAt(text:String,offset:Int):Int {
        var cursor=0
        paragraphs(text).forEachIndexed{i,p->val start=text.indexOf(p,cursor).coerceAtLeast(cursor);if(offset<start+p.length)return i;cursor=start+p.length}
        return (paragraphs(text).size-1).coerceAtLeast(0)
    }
    fun paragraphOffset(text:String,index:Int):Int {
        var cursor=0
        paragraphs(text).forEachIndexed{i,p->val start=text.indexOf(p,cursor).coerceAtLeast(cursor);if(i==index)return start;cursor=start+p.length};return 0
    }
}

data class ReadingMark(val chapter:Int,val paragraph:Int,val snippet:String,val created:Long)
class NovelPreferences(context:Context) {
    private val p=context.getSharedPreferences("novel-reading",0)
    var turnMode:NovelTurnMode get()=NovelTurnMode.parse(p.getString("turnMode",null));set(v){p.edit().putString("turnMode",v.name).apply()}
    var font:Float get()=p.getFloat("font",20f).coerceIn(16f,32f);set(v){p.edit().putFloat("font",v.coerceIn(16f,32f)).apply()}
    var spacing:Float get()=p.getFloat("spacing",1.9f).coerceIn(1.4f,2.4f);set(v){p.edit().putFloat("spacing",v.coerceIn(1.4f,2.4f)).apply()}
    var theme:Int get()=p.getInt("theme",0).coerceIn(0,3);set(v){p.edit().putInt("theme",v).apply()}
    var serif:Boolean get()=p.getBoolean("serif",false);set(v){p.edit().putBoolean("serif",v).apply()}
    var margin:Int get()=p.getInt("margin",24).coerceIn(16,40);set(v){p.edit().putInt("margin",v.coerceIn(16,40)).apply()}
    var keepScreen:Boolean get()=p.getBoolean("keep",false);set(v){p.edit().putBoolean("keep",v).apply()}
    fun position(key:String,chapter:Int)=p.getString("position:$key:$chapter",null)?.split(':')?.let{(it.getOrNull(0)?.toIntOrNull()?:0) to (it.getOrNull(1)?.toIntOrNull()?:0)} ?: (0 to 0)
    fun position(key:String,chapter:Int,paragraph:Int,offset:Int){p.edit().putString("position:$key:$chapter","${paragraph.coerceAtLeast(0)}:${offset.coerceAtLeast(0)}").apply()}
    fun marks(key:String):List<ReadingMark> = runCatching{val a=JSONArray(p.getString("marks:$key","[]"));(0 until a.length()).map{a.getJSONObject(it).let{v->ReadingMark(v.getInt("chapter"),v.getInt("paragraph"),v.getString("snippet"),v.getLong("created"))}}}.getOrDefault(emptyList())
    fun toggleMark(key:String,chapter:Int,paragraph:Int,snippet:String){
        val old=marks(key);val found=old.any{it.chapter==chapter&&it.paragraph==paragraph}
        val next=if(found)old.filterNot{it.chapter==chapter&&it.paragraph==paragraph}else listOf(ReadingMark(chapter,paragraph,snippet.take(100),System.currentTimeMillis()))+old
        val a=JSONArray();next.take(500).forEach{a.put(JSONObject().put("chapter",it.chapter).put("paragraph",it.paragraph).put("snippet",it.snippet).put("created",it.created))};p.edit().putString("marks:$key",a.toString()).apply()
    }
}
