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

enum class NovelTurnMode(private val labelId:Int) { SCROLL(R.string.ui_0693), SLIDE(R.string.ui_0694), TAP(R.string.ui_0695);
    val label:String get()=tr(labelId)
    companion object { fun parse(value:String?)=entries.firstOrNull{it.name==value}?:SCROLL }
}
enum class ComicTurnMode(private val labelId:Int,private val hintId:Int) {
    SCROLL(R.string.ui_0696,R.string.ui_0587),
    LEFT(R.string.ui_0698,R.string.ui_0699),
    RIGHT(R.string.ui_0700,R.string.ui_0701);
    val label:String get()=tr(labelId)
    val hint:String get()=tr(hintId)
    companion object { fun parse(value:String?)=entries.firstOrNull{it.name==value}?:SCROLL }
}

// Display indices always refer to the original chapter list, including when reversed.
fun chapterDisplayIndices(count:Int,reverse:Boolean):List<Int> = (0 until count).toList().let{if(reverse)it.asReversed()else it}

@Composable fun FavoriteButton(saved:Boolean,onClick:()->Unit){
    val color=if(saved)Color(0xFF287451)else Color(0xFF3975C6)
    Button(onClick=onClick,shape=RoundedCornerShape(10.dp),contentPadding=PaddingValues(horizontal=12.dp),
        colors=ButtonDefaults.buttonColors(containerColor=color,contentColor=Color.White),
        modifier=Modifier.padding(end=8.dp).heightIn(min=42.dp).semantics{stateDescription=if(saved)tr(R.string.ui_0702)else tr(R.string.ui_0703)}){
        Icon(if(saved)Icons.Outlined.Check else Icons.Outlined.Add,null,Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp));Text(if(saved)tr(R.string.ui_0704)else tr(R.string.ui_0705),fontSize=13.sp)
    }
}

@Composable fun ChapterSortButton(reverse:Boolean,onClick:()->Unit){
    TextButton(onClick=onClick,modifier=Modifier.semantics{contentDescription=tr(R.string.ui_0706);stateDescription=if(reverse)tr(R.string.ui_0707)else tr(R.string.ui_0708)}){
        Icon(Icons.Outlined.SwapVert,null,Modifier.size(18.dp));Spacer(Modifier.width(4.dp));Text(if(reverse)tr(R.string.ui_0676)else tr(R.string.ui_0677),fontSize=12.sp)
    }
}
