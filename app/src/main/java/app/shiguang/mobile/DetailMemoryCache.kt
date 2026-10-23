package app.shiguang.mobile

/** A short catalog cache speeds reopening; follow checks and explicit retry bypass it. */
class DetailMemoryCache(private val now:()->Long={System.nanoTime()/1_000_000}) {
    private data class Item(val detail:Detail,val time:Long,val weight:Int)
    private val items=linkedMapOf<String,Item>();private var bytes=0
    @Synchronized fun get(key:String):Detail? {
        val item=items.remove(key)?:return null;bytes-=item.weight
        if(now()-item.time>=120_000)return null
        items[key]=item;bytes+=item.weight;return item.detail
    }
    @Synchronized fun put(key:String,detail:Detail){
        val weight=detail.lines.sumOf{l->l.chapters.sumOf{(it.title.length+it.url.length)*2+64}}+detail.entry.description.length*2+512
        items.remove(key)?.let{bytes-=it.weight};if(weight>8*1024*1024)return
        items[key]=Item(detail,now(),weight);bytes+=weight
        while(items.size>24||bytes>8*1024*1024){val first=items.entries.first();bytes-=first.value.weight;items.remove(first.key)}
    }
    @Synchronized fun clear(){items.clear();bytes=0}
    @Synchronized fun sizeBytes()=bytes.toLong()
    companion object {val shared=DetailMemoryCache()}
}
