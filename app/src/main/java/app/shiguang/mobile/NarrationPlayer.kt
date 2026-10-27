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
                TopAppBar(title={Text(tr(R.string.ui_0475),fontSize=16.sp)},navigationIcon={IconButton(onClick=onClose){Icon(Icons.Outlined.ExpandMore,tr(R.string.ui_0476))}},actions={IconButton(onClick={settings=true}){Icon(Icons.Outlined.MoreVert,tr(R.string.ui_0477))}},windowInsets=WindowInsets(0,0,0,0))
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()){
                    val coverHeight=(maxHeight*.52f).coerceIn(80.dp,202.dp)
                    Column(Modifier.fillMaxWidth().align(Alignment.Center).verticalScroll(rememberScrollState()).padding(horizontal=28.dp),horizontalAlignment=Alignment.CenterHorizontally){
                        Spacer(Modifier.height(8.dp))
                        Cover(entry,Modifier.width(coverHeight*.7f).height(coverHeight))
                        Text(entry.title,fontSize=23.sp,lineHeight=31.sp,fontWeight=FontWeight.Medium,textAlign=TextAlign.Center,modifier=Modifier.padding(top=16.dp),maxLines=2,overflow=TextOverflow.Ellipsis)
                        Text(if(speech.mode=="ai")tr(R.string.ui_0444, when(speech.style){"happy"->tr(R.string.ui_0440);"sad"->tr(R.string.ui_0441);"angry"->tr(R.string.ui_0442);else->tr(R.string.ui_0439)})else tr(R.string.ui_0483),fontSize=12.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=8.dp))
                        Text(speech.chapterTitle,fontSize=14.sp,textAlign=TextAlign.Center,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=14.dp,bottom=14.dp))
                    }
                }
                Column(Modifier.fillMaxWidth().padding(horizontal=28.dp)){
                    var target by remember(entry.key,speech.chapter){mutableFloatStateOf(speech.offset.toFloat())}
                    var dragging by remember{mutableStateOf(false)}
                    LaunchedEffect(speech.offset){if(!dragging)target=speech.offset.toFloat()}
                    Slider(value=target.coerceIn(0f,speech.characters.coerceAtLeast(1).toFloat()),onValueChange={dragging=true;target=it},onValueChangeFinished={dragging=false;Narration.command(context,"seek",target.toInt().toString())},valueRange=0f..speech.characters.coerceAtLeast(1).toFloat(),enabled=speech.characters>0,modifier=Modifier.semantics{contentDescription=tr(R.string.ui_0484)})
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){Text(tr(R.string.ui_0485, speech.chapter+1, speech.chapterCount),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant);Text(tr(R.string.ui_0486, if(speech.characters>0)(speech.offset*100/speech.characters).coerceIn(0,100)else 0),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Row(Modifier.fillMaxWidth().padding(top=18.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly){
                        IconButton(enabled=speech.chapter>0,onClick={Narration.command(context,"previous")},modifier=Modifier.size(52.dp)){Icon(Icons.Outlined.SkipPrevious,tr(R.string.ui_0487),Modifier.size(34.dp))}
                        FilledIconButton(onClick={Narration.command(context,if(speech.active)"pause"else"play")},modifier=Modifier.size(80.dp).semantics{contentDescription=if(speech.active)tr(R.string.ui_0374)else tr(R.string.ui_0375)}){
                            if(speech.loading&&speech.active)CircularProgressIndicator(Modifier.size(30.dp),color=MaterialTheme.colorScheme.onPrimary,strokeWidth=2.dp)
                            else Icon(if(speech.active)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,null,Modifier.size(40.dp))
                        }
                        IconButton(enabled=speech.chapter<speech.chapterCount-1,onClick={Narration.command(context,"next")},modifier=Modifier.size(52.dp)){Icon(Icons.Outlined.SkipNext,tr(R.string.ui_0490),Modifier.size(34.dp))}
                    }
                    Text(speech.message,fontSize=12.sp,lineHeight=18.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth().padding(top=12.dp))
                    Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.SpaceEvenly){TextButton(onClick={rateDialog=true}){Text("${"%.1f".format(speech.rate)}×")};TextButton(onClick={timerDialog=true}){Icon(Icons.Outlined.Timer,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text(if(speech.sleepUntil>0)tr(R.string.ui_0491)else tr(R.string.ui_0492))};TextButton(onClick={onClose();onCatalog()}){Icon(Icons.Outlined.FormatListBulleted,null,Modifier.size(18.dp));Spacer(Modifier.width(5.dp));Text(tr(R.string.ui_0228))}}
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
    if(rateDialog){var rate by remember{mutableFloatStateOf(speech.rate)};AlertDialog(onDismissRequest={rateDialog=false},title={Text(tr(R.string.ui_0494))},text={Column{Text("${"%.1f".format(rate)}×");Slider(value=rate,onValueChange={rate=it},valueRange=.5f..2f)}},confirmButton={TextButton(onClick={Narration.command(context,"rate",rate.toString());rateDialog=false}){Text(tr(R.string.ui_0495))}},dismissButton={TextButton(onClick={rateDialog=false}){Text(tr(R.string.ui_0189))}})}
    if(timerDialog)AlertDialog(onDismissRequest={timerDialog=false},title={Text(tr(R.string.ui_0497))},text={Column{listOf(15,30,60).forEach{minutes->TextButton(onClick={Narration.command(context,"sleep",minutes.toString());timerDialog=false}){Text(tr(R.string.ui_0498, minutes))}};TextButton(onClick={Narration.command(context,"sleep","0");timerDialog=false}){Text(tr(R.string.ui_0499))}}},confirmButton={TextButton(onClick={timerDialog=false}){Text(tr(R.string.ui_0116))}})
    if(settings)ModalBottomSheet(onDismissRequest={settings=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)){
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(20.dp)){
            Text(tr(R.string.ui_0477),fontSize=21.sp)
            TextButton(onClick={voices=true}){Text(if(speech.mode=="ai")tr(R.string.ui_0502)else tr(R.string.ui_0503))}
            TextButton(onClick={rateDialog=true}){Text(tr(R.string.ui_0504, "%.1f".format(speech.rate)))}
            TextButton(onClick={timerDialog=true}){Text(tr(R.string.ui_0497))}
            if(speech.mode=="ai")TextButton(onClick={changeMode("system")}){Text(tr(R.string.ui_0506))}
            TextButton(onClick={pairing=true}){Text(tr(R.string.ui_0507))}
            TextButton(onClick={Narration.command(context,"stop");settings=false;onClose()}){Text(tr(R.string.ui_0376))}
        }
    }
    if(voices)AlertDialog(onDismissRequest={voices=false},title={Text(tr(R.string.ui_0509))},text={Column(Modifier.heightIn(max=340.dp).verticalScroll(rememberScrollState())){if(speech.mode=="system")speech.voices.forEach{voice->TextButton(onClick={Narration.command(context,"voice",voice);voices=false}){Text((if(voice==speech.voice)"✓ "else"")+voice,fontSize=12.sp)}}else Text(tr(R.string.ui_0510))}},confirmButton={TextButton(onClick={voices=false}){Text(tr(R.string.ui_0116))}})
    if(pairing)AiVoiceSetupDialog(onClose={pairing=false},onStart={pairing=false;changeMode("ai")})
    issue?.let{AlertDialog(onDismissRequest={issue=null},title={Text(tr(R.string.ui_0512))},text={Text(it)},confirmButton={TextButton(onClick={issue=null}){Text(tr(R.string.ui_0116))}})}
    }
}
