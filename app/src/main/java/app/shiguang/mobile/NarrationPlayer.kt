package app.shiguang.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Cover-led listening page; progress is measured in chapter text, never invented audio duration. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NarrationPlayer(onClose:()->Unit,onCatalog:()->Unit){
    val context=LocalContext.current
    val speech by Narration.state.collectAsState()
    val entry=speech.entry
    var rateDialog by remember{mutableStateOf(false)}
    var timerDialog by remember{mutableStateOf(false)}
    var settings by remember{mutableStateOf(false)}
    var voices by remember{mutableStateOf(false)}
    var pairing by remember{mutableStateOf(false)}
    var issue by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope()
    fun changeMode(mode:String){scope.launch{try{Narration.changeMode(context,mode);settings=false}catch(e:CancellationException){throw e}catch(e:Exception){issue=ContentRepository.friendlyError(e)}}}
    if(entry==null){LaunchedEffect(Unit){onClose()};return}
    val night=MaterialTheme.colorScheme.surface.luminance()<.2f
    MaterialTheme(colorScheme=MaterialTheme.colorScheme.copy(primary=if(night)Color(0xFF8AB6F0)else Color(0xFF3975C6),onPrimary=if(night)Color(0xFF132F52)else Color.White)){
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        Surface(Modifier.fillMaxSize(),color=MaterialTheme.colorScheme.surface){
            Column(Modifier.fillMaxSize().systemBarsPadding()){
                TopAppBar(title={Text("听书",fontSize=16.sp)},navigationIcon={IconButton(onClick=onClose){Icon(Icons.Outlined.ExpandMore,"收起听书页")}},actions={IconButton(onClick={settings=true}){Icon(Icons.Outlined.MoreVert,"听书设置")}},windowInsets=WindowInsets(0,0,0,0))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()){
                    val coverHeight=(maxHeight*.52f).coerceIn(80.dp,202.dp)
                    Column(Modifier.fillMaxWidth().align(Alignment.Center).verticalScroll(rememberScrollState()).padding(horizontal=28.dp),horizontalAlignment=Alignment.CenterHorizontally){
                        Spacer(Modifier.height(8.dp))
                        Cover(entry,Modifier.width(coverHeight*.7f).height(coverHeight))
                        Text(entry.title,fontSize=23.sp,lineHeight=31.sp,fontWeight=FontWeight.Medium,textAlign=TextAlign.Center,modifier=Modifier.padding(top=16.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(if(speech.mode=="ai")"AI 情感语音 · ${when(speech.style){"happy"->"开心";"sad"->"伤感";"angry"->"愤怒";else->"自然"}}"else"手机离线语音",fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                        Text(speech.chapterTitle,fontSize=14.sp,textAlign=TextAlign.Center,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=14.dp,bottom=14.dp))
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal=28.dp)){
                    var target by remember(entry.key,speech.chapter){mutableFloatStateOf(speech.offset.toFloat())}
                    var dragging by remember{mutableStateOf(false)}
                    LaunchedEffect(speech.offset){if(!dragging)target=speech.offset.toFloat()}
                    Slider(value=target.coerceIn(0f,speech.characters.coerceAtLeast(1).toFloat()),onValueChange={dragging=true;target=it},onValueChangeFinished={dragging=false;Narration.command(context,"seek",target.toInt().toString())},valueRange=0f..speech.characters.coerceAtLeast(1).toFloat(),enabled=speech.characters>0,modifier=Modifier.semantics{contentDescription="听书进度"})
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text("第 ${speech.chapter+1} / ${speech.chapterCount} 章",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text("本章文字进度 ${if(speech.characters>0)(speech.offset*100/speech.characters).coerceIn(0,100)else 0}%",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Row(Modifier.fillMaxWidth().padding(top=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly){
                        IconButton(enabled=speech.chapter>0,onClick={Narration.command(context,"previous")},modifier=Modifier.size(52.dp)){Icon(Icons.Outlined.SkipPrevious,"上一章",Modifier.size(34.dp))}
                        FilledIconButton(onClick={Narration.command(context,if(speech.active)"pause"else"play")},modifier=Modifier.size(80.dp).semantics{contentDescription=if(speech.active)"暂停听书"else"继续听书"}){
                            if(speech.loading&&speech.active)CircularProgressIndicator(Modifier.size(30.dp),color=MaterialTheme.colorScheme.onPrimary,strokeWidth=2.dp)
                            else Icon(if(speech.active)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,null,Modifier.size(40.dp))
                        }
                        IconButton(enabled=speech.chapter<speech.chapterCount-1,onClick={Narration.command(context,"next")},modifier=Modifier.size(52.dp)){Icon(Icons.Outlined.SkipNext,"下一章",Modifier.size(34.dp))}
                    }
                    Text(speech.message,fontSize=12.sp,lineHeight=18.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(top=12.dp))
                    Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.SpaceEvenly){TextButton(onClick={rateDialog=true}){Text("${"%.1f".format(speech.rate)}×")};TextButton(onClick={timerDialog=true}){Icon(Icons.Outlined.Timer,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text(if(speech.sleepUntil>0)"已定时"else"定时")};TextButton(onClick={onClose();onCatalog()}){Icon(Icons.Outlined.FormatListBulleted,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text("目录")}}
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
    if(rateDialog){var rate by remember{mutableFloatStateOf(speech.rate)};AlertDialog(onDismissRequest={rateDialog=false},title={Text("朗读速度")},text={Column{Text("${"%.1f".format(rate)}×");Slider(value=rate,onValueChange={rate=it},valueRange=.5f..2f)}},confirmButton={TextButton(onClick={Narration.command(context,"rate",rate.toString());rateDialog=false}){Text("确定")}},dismissButton={TextButton(onClick={rateDialog=false}){Text("取消")}})}
    if(timerDialog)AlertDialog(onDismissRequest={timerDialog=false},title={Text("定时关闭")},text={Column{listOf(15,30,60).forEach{minutes->TextButton(onClick={Narration.command(context,"sleep",minutes.toString());timerDialog=false}){Text("${minutes}分钟后停止")}};TextButton(onClick={Narration.command(context,"sleep","0");timerDialog=false}){Text("取消定时")}}},confirmButton={TextButton(onClick={timerDialog=false}){Text("关闭")}})
    if(settings)ModalBottomSheet(onDismissRequest={settings=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)){
            Text("听书设置",fontSize=21.sp)
            TextButton(onClick={voices=true}){Text(if(speech.mode=="ai")"AI 情感语音"else"手机离线音色")}
            TextButton(onClick={rateDialog=true}){Text("朗读速度 · ${"%.1f".format(speech.rate)}×")}
            TextButton(onClick={timerDialog=true}){Text("定时关闭")}
            if(speech.mode=="ai")TextButton(onClick={changeMode("system")}){Text("切换到手机离线语音")}
            TextButton(onClick={pairing=true}){Text("配对电脑 AI 语音")}
            TextButton(onClick={Narration.command(context,"stop");settings=false;onClose()}){Text("结束听书")}
        }
    }
    if(voices)AlertDialog(onDismissRequest={voices=false},title={Text("朗读音色")},text={Column(Modifier.heightIn(max=340.dp).verticalScroll(rememberScrollState())){if(speech.mode=="system")speech.voices.forEach{voice->TextButton(onClick={Narration.command(context,"voice",voice);voices=false}){Text((if(voice==speech.voice)"✓ "else"")+voice,fontSize=12.sp)}}else Text("朗读风格可在电脑语音配对中更改。")}},confirmButton={TextButton(onClick={voices=false}){Text("关闭")}})
    if(pairing)AiVoiceSetupDialog(onClose={pairing=false},onStart={pairing=false;changeMode("ai")})
    issue?.let{AlertDialog(onDismissRequest={issue=null},title={Text("无法切换语音")},text={Text(it)},confirmButton={TextButton(onClick={issue=null}){Text("关闭")}})}
    }
}
