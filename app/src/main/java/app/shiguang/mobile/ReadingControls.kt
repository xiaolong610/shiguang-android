package app.shiguang.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class NovelTurnMode(val label:String) { SCROLL("上下滚动"), SLIDE("左右滑动"), TAP("点击翻页");
    companion object { fun parse(value:String?)=entries.firstOrNull{it.name==value}?:SCROLL }
}
enum class ComicTurnMode(val label:String,val hint:String) {
    SCROLL("上下条漫","上下滑动连续阅读"),
    LEFT("左右翻页","向左滑下一页；点右侧下一页"),
    RIGHT("日漫右翻","向右滑下一页；点左侧下一页");
    companion object { fun parse(value:String?)=entries.firstOrNull{it.name==value}?:SCROLL }
}

// Display indices always refer to the original chapter list, including when reversed.
fun chapterDisplayIndices(count:Int,reverse:Boolean):List<Int> = (0 until count).toList().let{if(reverse)it.asReversed()else it}

@Composable fun FavoriteButton(saved:Boolean,onClick:()->Unit){
    val color=if(saved)Color(0xFF287451)else Color(0xFF3975C6)
    Button(onClick=onClick,shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=12.dp),
        colors=ButtonDefaults.buttonColors(containerColor=color,contentColor=Color.White),
        modifier=Modifier.padding(end=8.dp).heightIn(min=42.dp).semantics{stateDescription=if(saved)"已收藏，点击取消收藏"else"尚未收藏，点击加入收藏"}){
        Icon(if(saved)Icons.Outlined.Check else Icons.Outlined.Add,null,Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp));Text(if(saved)"已收藏"else"加入收藏",fontSize=13.sp)
    }
}

@Composable fun ChapterSortButton(reverse:Boolean,onClick:()->Unit){
    TextButton(onClick=onClick,modifier=Modifier.semantics{contentDescription="切换章节排序";stateDescription=if(reverse)"倒序"else"正序"}){
        Icon(Icons.Outlined.SwapVert,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text(if(reverse)"倒序 ↓"else"正序 ↑",fontSize=12.sp)
    }
}
