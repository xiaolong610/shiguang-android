package app.shiguang.mobile

import java.text.Normalizer

/** Keep seasons, years and edition names: title similarity alone must not mix sequels. */
object WorkIdentity {
    fun title(value:String):String = Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
        .replace(Regex("(?i)[\\[【(（](?:1080p|720p|2160p|4k|高清|超清|蓝光)[\\]】)）]"),"")
        .replace(Regex("[\\s\\p{Punct}，。！？：；、·《》【】（）]+"),"")
    fun key(e:Entry):String = "${e.category}:${title(e.title)}" +
        if(e.sourceId.startsWith("demo-")||e.sourceId==LocalNovels.source.id)":${e.key}" else ""
    fun same(a:Entry,b:Entry)=key(a)==key(b)&&title(a.title).isNotBlank()
    fun groups(entries:List<Entry>):List<List<Entry>> = entries.groupBy{key(it)}.values.flatMap{group->
        // Multiple editions with one title on the same site need manual selection.
        if(group.groupBy{it.sourceId}.any{it.value.size>1})group.map{listOf(it)} else listOf(group)
    }
}

object ChapterIdentity {
    fun normalized(value:String)=Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(java.util.Locale.ROOT)
        .replace(Regex("[\\s\\p{Punct}，。！？：；、·《》【】（）]+"),"")
    private fun number(value:String):Int? {
        value.toIntOrNull()?.let{return it}
        val digits=mapOf('零' to 0,'〇' to 0,'一' to 1,'二' to 2,'两' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
        var total=0;var digit=0
        for(c in value){if(c in digits)digit=digits.getValue(c) else {val unit=when(c){'十'->10;'百'->100;'千'->1000;else->return null};total+=(if(digit==0)1 else digit)*unit;digit=0}}
        return total+digit
    }
    fun episode(value:String):Int? {
        val v=normalized(value)
        return Regex("^(?:第)?([0-9零〇一二两三四五六七八九十百千]+)(?:章|集|话|回)(?:.*)$").matchEntire(v)?.groupValues?.get(1)?.let{number(it)}
            ?: Regex("^(?:ep|episode)?0*([0-9]+)$").matchEntire(v)?.groupValues?.get(1)?.toIntOrNull()
    }
    private fun suffix(value:String):String=normalized(value).replace(Regex("^(?:第)?[0-9零〇一二两三四五六七八九十百千]+(?:章|集|话|回)"),"")
        .replace(Regex("^(?:ep|episode)?[0-9]+$"),"")
    fun keys(chapters:List<Chapter>):List<String> {
        val seen=mutableMapOf<String,Int>()
        return chapters.map{ch->val base=normalized(ch.title).ifBlank{ch.url};val occurrence=seen.getOrDefault(base,0);seen[base]=occurrence+1;"$base#$occurrence"}
    }
    /** Novel chapter subtitles must match; numeric-only matching is reserved for video. */
    fun match(title:String,chapters:List<Chapter>,kind:Kind):Int? {
        if(title.isBlank())return null
        val exact=chapters.indices.filter{normalized(chapters[it].title)==normalized(title)}
        if(exact.size==1)return exact.single()
        if(kind!=Kind.VIDEO||exact.size>1)return null
        val n=episode(title)?:return null
        return chapters.indices.filter{episode(chapters[it].title)==n&&suffix(chapters[it].title)==suffix(title)}.singleOrNull()
    }
}
