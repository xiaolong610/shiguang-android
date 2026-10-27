package app.shiguang.mobile

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.launch

/** Non-overlapping, complete character ranges; each page ends on a measured line boundary. */
fun measuredPageRanges(textLength:Int,lineEnds:List<Int>,lineTops:List<Float>,lineBottoms:List<Float>,height:Float):List<IntRange>{
    require(height>0&&lineEnds.size==lineTops.size&&lineEnds.size==lineBottoms.size)
    if(textLength==0)return listOf(0 until 0)
    val out=mutableListOf<IntRange>();var line=0;var start=0
    while(line<lineEnds.size){val first=line;var last=line
        while(last+1<lineEnds.size&&lineBottoms[last+1]-lineTops[first]<=height)last++
        val end=lineEnds[last].coerceIn(start,textLength)
        if(end>start){out+=start until end;start=end};line=last+1
    }
    if(start<textLength)out+=start until textLength
    return out.ifEmpty{listOf(0 until textLength)}
}

@Composable fun PagedNovelBody(key:String,chapter:Int,title:String,paragraphs:List<String>,mode:NovelTurnMode,
    font:Float,spacing:Float,serif:Boolean,margin:Int,fg:Color,initialItem:Int,initialOffset:Int,
    requestedParagraph:Int?,requestId:Int,onPosition:(Int,Int,Int,Int)->Unit,onMenu:()->Unit,onPreviousChapter:()->Unit,onNextChapter:()->Unit,fontFamily:FontFamily?=null,turnRequest:Pair<Int,Boolean>?=null){
    val density=LocalDensity.current;val measurer=rememberTextMeasurer(cacheSize=2);val scope=rememberCoroutineScope()
    val starts=remember(title,paragraphs){val a=mutableListOf<Int>();var n=title.length+2;paragraphs.forEach{a+=n;n+=it.length+4};a}
    val text=remember(title,paragraphs){buildAnnotatedString{withStyle(SpanStyle(fontWeight=FontWeight.SemiBold)){append(title)};append("\n\n");paragraphs.forEachIndexed{i,p->if(i>0)append("\n\n");append("　　");append(p)}}}
    val style=TextStyle(fontSize=font.sp,lineHeight=(font*spacing).sp,fontFamily=fontFamily?:if(serif)FontFamily.Serif else FontFamily.Default,textAlign=TextAlign.Justify,color=fg)
    var anchor by rememberSaveable(key,chapter){mutableIntStateOf(-1)}
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal=margin.dp,vertical=6.dp)){
        val width=with(density){maxWidth.roundToPx()}.coerceAtLeast(1);val height=with(density){maxHeight.toPx()}.coerceAtLeast(1f)
        val layout=remember(text,style,width,density.density,density.fontScale){measurer.measure(text,style,constraints=Constraints(maxWidth=width))}
        val ranges=remember(layout,height){measuredPageRanges(text.length,(0 until layout.lineCount).map{layout.getLineEnd(it)},(0 until layout.lineCount).map{layout.getLineTop(it)},(0 until layout.lineCount).map{layout.getLineBottom(it)},height)}
        fun pageFor(offset:Int)=ranges.indexOfFirst{offset<=it.last}.let{if(it<0)ranges.lastIndex else it}
        fun offsetFor(item:Int,pixels:Int):Int{
            if(item<=0)return 0
            if(item>starts.size)return (text.length-1).coerceAtLeast(0)
            val firstLine=layout.getLineForOffset(starts[item-1].coerceAtMost(text.length))
            val targetLine=layout.getLineForVerticalPosition(layout.getLineTop(firstLine)+pixels)
            return layout.getLineStart(targetLine)
        }
        val pager=rememberPagerState(initialPage=pageFor(if(anchor>=0)anchor else offsetFor(initialItem,initialOffset))){ranges.size}
        fun turn(next:Boolean){scope.launch{val target=pager.currentPage+if(next)1 else -1
            when{target<0->onPreviousChapter();target>ranges.lastIndex->onNextChapter();mode==NovelTurnMode.TAP->pager.scrollToPage(target);else->pager.animateScrollToPage(target)}
        }}
        LaunchedEffect(turnRequest?.first){turnRequest?.let{turn(it.second)}}
        LaunchedEffect(layout,height){pager.scrollToPage(pageFor(if(anchor>=0)anchor else offsetFor(initialItem,initialOffset)))}
        LaunchedEffect(requestId){if(requestedParagraph!=null){val p=requestedParagraph.coerceIn(0,starts.lastIndex.coerceAtLeast(0));pager.scrollToPage(pageFor(starts.getOrElse(p){0}))}}
        val positionCallback by rememberUpdatedState(onPosition)
        LaunchedEffect(pager,layout,ranges){snapshotFlow{pager.settledPage}.collect{p->
            anchor=ranges[p.coerceIn(ranges.indices)].first
            val paragraph=starts.indexOfLast{it<=anchor}.coerceAtLeast(0)
            val item=if(anchor<(starts.firstOrNull()?:0))0 else paragraph+1
            val firstLine=layout.getLineForOffset(starts.getOrElse(paragraph){0}.coerceAtMost(text.length))
            val currentLine=layout.getLineForOffset(anchor.coerceAtMost(text.length))
            val pixels=kotlin.math.ceil(layout.getLineTop(currentLine)-layout.getLineTop(firstLine)).toInt().coerceAtLeast(0)
            positionCallback(item,pixels,p+1,ranges.size)
        }}
        HorizontalPager(state=pager,userScrollEnabled=mode==NovelTurnMode.SLIDE,modifier=Modifier.fillMaxSize().semantics{
            contentDescription=tr(R.string.ui_0644);customActions=listOf(CustomAccessibilityAction(tr(R.string.ui_0645)){turn(false);true},CustomAccessibilityAction(tr(R.string.ui_0646)){turn(true);true})
        }){p->val range=ranges[p]
            Box(Modifier.fillMaxSize().pointerInput(mode,p){detectTapGestures(onTap={point->when{point.x<size.width*.3f->turn(false);point.x>size.width*.7f->turn(true);else->onMenu()}})}){
                Text(text.subSequence(range.first,(range.last+1).coerceAtLeast(range.first)),style=style,modifier=Modifier.fillMaxWidth())
            }
        }
    }
}
