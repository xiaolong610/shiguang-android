package app.shiguang.mobile

import java.net.URI
import kotlin.math.roundToLong

data class AdRange(val startSeconds:Double,val endSeconds:Double,val evidence:String) {
    val durationSeconds get()=endSeconds-startSeconds
}
data class AdFilterResult(
    val text:String,val mediaPlaylist:Boolean=false,val removed:List<AdRange> = emptyList(),
    val removedSegments:Int=0,val originalSeconds:Double=0.0,val discontinuities:Int=0,
    val reason:String="",val uncertainBlocks:Int=0,
) {
    val removedSeconds get()=removed.sumOf{it.durationSeconds}
    fun originalToFilteredMs(original:Long):Long {
        val seconds=original.coerceAtLeast(0)/1000.0
        val cut=removed.sumOf{(seconds-it.startSeconds).coerceIn(0.0,it.durationSeconds)}
        return ((seconds-cut)*1000).roundToLong().coerceAtLeast(0)
    }
    fun filteredToOriginalMs(filtered:Long):Long {
        val seconds=filtered.coerceAtLeast(0)/1000.0
        var cut=0.0
        for(range in removed){if(seconds<range.startSeconds-cut)break;cut+=range.durationSeconds}
        return ((seconds+cut)*1000).roundToLong().coerceAtLeast(0)
    }
    fun message():String = when {
        removed.isNotEmpty() -> tr(R.string.ui_0312, removed.size, if(removed.any{it.evidence=="known-source-island"}) tr(R.string.ui_0313) else tr(R.string.ui_0314), removedSeconds.roundToLong())
        reason.isNotBlank() -> reason
        uncertainBlocks>0 -> tr(R.string.ui_0315, uncertainBlocks)
        else -> tr(R.string.ui_0316)
    }
}

/** Only bounded, independently signalled VOD inserts are eligible. No video pixels are inspected. */
object HlsAdFilter {
    private data class Segment(val index:Int,val duration:Double,val start:Double,val fromLine:Int,val extLine:Int,val uriLine:Int,val uri:String,val group:Int)
    private data class Block(val segments:List<Segment>){val duration get()=segments.sumOf{it.duration};val first get()=segments.first().index;val last get()=segments.last().index}
    private val adPath=Regex("/(?:ads?|adverts?|advertisements?|commercials?)(?:/|[_.-])",RegexOption.IGNORE_CASE)
    private val globalTags=listOf("#EXTM3U","#EXT-X-VERSION:","#EXT-X-TARGETDURATION:","#EXT-X-MEDIA-SEQUENCE:","#EXT-X-DISCONTINUITY-SEQUENCE:","#EXT-X-PLAYLIST-TYPE:","#EXT-X-INDEPENDENT-SEGMENTS","#EXT-X-START:","#EXT-X-DATERANGE:","#EXT-X-ENDLIST")
    fun clean(text:String,baseUrl:String,knownAdSource:Boolean=false):AdFilterResult {
        val lines=text.removePrefix("\uFEFF").split('\n').map{it.trimEnd('\r')}
        val media=lines.any{it.startsWith("#EXTINF:")}
        fun unchanged(reason:String="",total:Double=0.0,discs:Int=0,uncertain:Int=0)=AdFilterResult(text,media,originalSeconds=total,discontinuities=discs,reason=reason,uncertainBlocks=uncertain)
        if(!media)return unchanged()
        if(lines.firstOrNull()?.trim()!="#EXTM3U")return unchanged(tr(R.string.ui_0317))
        if(lines.none{it.trim()=="#EXT-X-ENDLIST"})return unchanged(tr(R.string.ui_0318))
        if(lines.any{it.startsWith("#EXT-X-KEY:") && !Regex("(?:^|,)METHOD=NONE(?:,|$)").containsMatchIn(it.substringAfter(':'))})return unchanged(tr(R.string.ui_0319))
        if(lines.any{line->listOf("#EXT-X-BYTERANGE:","#EXT-X-MAP:","#EXT-X-PART:","#EXT-X-SKIP:","#EXT-X-PRELOAD-HINT:","#EXT-X-I-FRAMES-ONLY").any{line.startsWith(it)}})return unchanged(tr(R.string.ui_0320))
        val segments=mutableListOf<Segment>();val cueRanges=mutableListOf<IntRange>()
        var previousUri=-1;var ext=-1;var duration=0.0;var time=0.0;var group=0;var discs=0
        var cueStart:Int?=null;var cueExpected:Double?=null;var invalidCue=false
        for((i,raw) in lines.withIndex()) {
            val line=raw.trim()
            when {
                line=="#EXT-X-DISCONTINUITY"->{group++;discs++}
                line=="#EXT-X-CUE-OUT" || line.startsWith("#EXT-X-CUE-OUT:")->{
                    if(cueStart!=null)invalidCue=true
                    cueStart=segments.size
                    cueExpected=if(':' !in line)null else line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                        ?: Regex("(?:^|,)DURATION=([0-9.]+)").find(line.substringAfter(':'))?.groupValues?.get(1)?.toDoubleOrNull()
                    if(':' in line&&(cueExpected==null||cueExpected?.isFinite()!=true||cueExpected!!<=0))invalidCue=true
                }
                line=="#EXT-X-CUE-IN" || line.startsWith("#EXT-X-CUE-IN:")->{
                    val start=cueStart
                    if(start==null||start>=segments.size)invalidCue=true else {
                        val range=start until segments.size;val actual=range.sumOf{segments[it].duration}
                        val tolerance=(range.maxOf{segments[it].duration}).coerceIn(1.0,5.0)
                        if(cueExpected!=null&&kotlin.math.abs(actual-cueExpected!!)>tolerance)invalidCue=true else cueRanges.add(range)
                    }
                    cueStart=null;cueExpected=null
                }
                line.startsWith("#EXTINF:")->{if(ext>=0)return unchanged(tr(R.string.ui_0321));ext=i;duration=line.substringAfter(':').substringBefore(',').toDoubleOrNull() ?: return unchanged(tr(R.string.ui_0322));if(!duration.isFinite()||duration<=0)return unchanged(tr(R.string.ui_0323))}
                line.isNotBlank()&&!line.startsWith('#')->{
                    if(ext<0)return unchanged(tr(R.string.ui_0324))
                    val uri=runCatching{URI(baseUrl).resolve(line)}.getOrNull() ?: return unchanged(tr(R.string.ui_0325))
                    if(uri.scheme !in listOf("https","http","file","asset"))return unchanged(tr(R.string.ui_0326))
                    segments.add(Segment(segments.size,duration,time,previousUri+1,ext,i,uri.toString(),group));time+=duration;previousUri=i;ext=-1
                }
            }
        }
        if(ext>=0||segments.isEmpty())return unchanged(tr(R.string.ui_0327))
        if(cueStart!=null||invalidCue)return unchanged(tr(R.string.ui_0328),time,discs)
        val blocks=segments.groupBy{it.group}.values.map{Block(it)}
        val selected=linkedMapOf<Int,String>()
        for(range in cueRanges){val seconds=range.sumOf{segments[it].duration};if(seconds in 0.1..180.0)range.forEach{selected[it]="paired-cue"}}
        fun family(segment:Segment):String=runCatching{URI(segment.uri).let{(it.host ?: "")+it.path.substringBeforeLast('/')}}.getOrDefault("")
        fun blockFamily(block:Block)=block.segments.map{family(it)}.distinct().singleOrNull()
        var uncertain=0
        for((i,block) in blocks.withIndex()) {
            if(block.duration !in 0.5..120.0 || block.segments.size>=30)continue
            val explicitPath=block.segments.all{adPath.containsMatchIn(runCatching{URI(it.uri).path}.getOrDefault(""))}
            val hasBoundary=blocks.size>1 && (i>0 || segments.getOrNull(block.last+1)?.group!=block.segments.last().group)
            val before=blocks.getOrNull(i-1);val after=blocks.getOrNull(i+1)
            val island=knownAdSource&&block.duration>=5&&before!=null&&after!=null&&before.duration>=120&&after.duration>=120&&
                blockFamily(before)!=null&&blockFamily(before)==blockFamily(after)&&blockFamily(block)!=null&&blockFamily(block)!=blockFamily(before)
            when {
                explicitPath&&hasBoundary->block.segments.forEach{selected[it.index]="bounded-ad-path"}
                island->block.segments.forEach{selected[it.index]="known-source-island"}
                else->uncertain++
            }
        }
        if(selected.isEmpty())return unchanged(total=time,discs=discs,uncertain=if(discs>0)uncertain else 0)
        val removedTime=selected.keys.sumOf{segments[it].duration}
        if(selected.size>=segments.size||removedTime/time>0.2||removedTime>600)return unchanged(tr(R.string.ui_0329),time,discs,uncertain)
        val keep=BooleanArray(lines.size){true};val inserts=mutableSetOf<Int>()
        for(index in selected.keys){val s=segments[index];for(i in s.fromLine..s.uriLine){if(globalTags.none{lines[i].startsWith(it)})keep[i]=false}}
        var previousKept:Int?=null
        val retained=segments.filter{it.index !in selected}
        for(s in retained){val previous=previousKept;if(previous!=null&&s.index>previous+1&&(s.fromLine until s.extLine).none{lines[it].trim()=="#EXT-X-DISCONTINUITY"})inserts.add(s.extLine);previousKept=s.index}
        val first=retained.first();val initialSequence=lines.find{it.startsWith("#EXT-X-MEDIA-SEQUENCE:")}?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        val oldDiscSeq=lines.find{it.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")}?.substringAfter(':')?.trim()?.toLongOrNull() ?: 0L
        val droppedDiscs=(0 until first.fromLine).count{!keep[it]&&lines[it].trim()=="#EXT-X-DISCONTINUITY"}
        val output=mutableListOf<String>()
        for((i,line)in lines.withIndex()) {
            if(!keep[i])continue
            if(line.startsWith("#EXT-X-CUE-OUT")||line.startsWith("#EXT-X-CUE-IN"))continue
            if(i in inserts)output.add("#EXT-X-DISCONTINUITY")
            output.add(when {
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:")->"#EXT-X-MEDIA-SEQUENCE:${initialSequence+first.index}"
                line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")->"#EXT-X-DISCONTINUITY-SEQUENCE:${oldDiscSeq+droppedDiscs}"
                else->line
            })
        }
        if(first.index>0&&lines.none{it.startsWith("#EXT-X-MEDIA-SEQUENCE:")})output.add(1,"#EXT-X-MEDIA-SEQUENCE:${first.index}")
        if(droppedDiscs>0&&lines.none{it.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")})output.add(1,"#EXT-X-DISCONTINUITY-SEQUENCE:$droppedDiscs")
        val ranges=mutableListOf<AdRange>();var start:Int?=null;var end=-1;var evidence=""
        for(index in selected.keys.sorted()) {
            if(start!=null && (index!=end+1||selected[index]!=evidence)){ranges.add(AdRange(segments[start].start,segments[end].start+segments[end].duration,evidence));start=null}
            if(start==null){start=index;evidence=selected.getValue(index)};end=index
        }
        if(start!=null)ranges.add(AdRange(segments[start].start,segments[end].start+segments[end].duration,evidence))
        return AdFilterResult(output.joinToString("\n"),true,ranges,selected.size,time,discs,uncertainBlocks=uncertain)
    }
}

object AdSourceHints {
    private val flaggedHosts=setOf("ffzy5.tv","bfzyapi.com","cj.lziapi.com")
    fun known(url:String)=runCatching{URI(url).host?.lowercase() in flaggedHosts}.getOrDefault(false)
}
