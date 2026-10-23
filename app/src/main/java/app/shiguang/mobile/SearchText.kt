package app.shiguang.mobile

import java.text.Normalizer
import java.util.Locale

/** Local matching complements site search; it cannot index unseen remote catalogs. */
object SearchText {
    private val punctuation=Regex("[\\s\\p{Punct}，。！？：；、·《》【】（）]+")
    fun normalize(text:String)=punctuation.replace(Normalizer.normalize(text,Normalizer.Form.NFKC).lowercase(Locale.ROOT),"")
    fun score(title:String,query:String):Int {
        val t=normalize(title).take(180);val q=normalize(query).take(80)
        if(q.isEmpty())return 1
        if(t==q)return 1000
        if(t.startsWith(q))return 900-(t.length-q.length).coerceAtMost(80)
        val at=t.indexOf(q);if(at>=0)return 800-at.coerceAtMost(80)
        val tokens=query.trim().split(Regex("\\s+")).map{normalize(it)}.filter{it.isNotEmpty()}
        if(tokens.size>1&&tokens.all{t.contains(it)})return 720
        if(q.length<2)return 0
        var cursor=0;var first=-1;var last=-1
        for(c in q){val pos=t.indexOf(c,cursor);if(pos<0){last=-1;break};if(first<0)first=pos;last=pos;cursor=pos+1}
        if(last>=0&&last-first+1<=maxOf(6,q.length*3))return 650-(last-first+1-q.length)*12
        if(q.length !in 3..32)return 0
        val limit=if(q.length<=4)1 else 2
        // Levenshtein distance against any title substring, O(query * title).
        var previous=IntArray(t.length+1)
        for(i in 1..q.length){val row=IntArray(t.length+1);row[0]=i
            for(j in 1..t.length)row[j]=minOf(previous[j]+1,row[j-1]+1,previous[j-1]+if(q[i-1]==t[j-1])0 else 1)
            previous=row
        }
        val distance=previous.minOrNull()?:q.length
        return if(distance<=limit)450-distance*80 else 0
    }
    fun rank(entries:List<Entry>,query:String):List<Entry> = if(query.isBlank())entries else entries.map{it to score(it.title,query)}.sortedByDescending{it.second}.map{it.first}
    fun matches(entries:List<Entry>,query:String,limit:Int=300)=entries.asSequence().map{it to score(it.title,query)}.filter{it.second>0}.sortedByDescending{it.second}.take(limit).map{it.first}.toList()
    fun suggestions(entries:List<Entry>,query:String)=if(normalize(query).isEmpty())emptyList() else matches(entries,query,60).map{it.title}.distinct().filter{normalize(it)!=normalize(query)}.take(5)
    fun broader(query:String):List<String> {
        val q=normalize(query)
        if(q.length<3)return emptyList()
        val n=(q.length/2).coerceIn(2,6)
        return listOf(q.take(n),q.takeLast(n)).distinct().filter{it!=q}
    }
}
