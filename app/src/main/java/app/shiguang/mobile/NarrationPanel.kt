package app.shiguang.mobile

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val AiStyles get()=linkedMapOf("natural" to tr(R.string.ui_0439),"happy" to tr(R.string.ui_0440),"sad" to tr(R.string.ui_0441),"angry" to tr(R.string.ui_0442))

@Composable fun NarrationPanel(speech:NarrationState,matching:Boolean,canStart:Boolean,onStart:(String)->Unit,onCatalog:()->Unit){
    val context=LocalContext.current
    var setup by remember{mutableStateOf(false)}
    var player by remember{mutableStateOf(false)}
    var opening by remember{mutableStateOf(false)}
    LaunchedEffect(opening,matching,speech.entry){if(opening&&matching){opening=false;player=true}}
    val muted=MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.verticalScroll(rememberScrollState())){
        Text(tr(R.string.ui_0443),fontSize=23.sp)
        Text(if(matching&&speech.mode=="ai")tr(R.string.ui_0444, AiStyles[speech.style]?:tr(R.string.ui_0439))else tr(R.string.ui_0446),fontSize=12.sp,color=muted,modifier=Modifier.padding(top=8.dp,bottom=18.dp))
        if(matching){
            Text(speech.message,fontSize=13.sp,color=muted)
            Spacer(Modifier.height(12.dp))
            Button(onClick={player=true},modifier=Modifier.fillMaxWidth()){Text(tr(R.string.ui_0447))}

        }
        Spacer(Modifier.height(12.dp))
        if(!matching)Button(enabled=canStart,onClick={opening=true;onStart("system")},modifier=Modifier.fillMaxWidth()){Text(tr(R.string.ui_0448))}
        TextButton(onClick={runCatching{context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}.recoverCatching{context.startActivity(Intent(Settings.ACTION_SETTINGS))}}){Text(tr(R.string.ui_0449))}
        Text(tr(R.string.ui_0450),fontSize=12.sp,color=muted,lineHeight=20.sp)
        HorizontalDivider(Modifier.padding(vertical=18.dp))
        OutlinedButton(enabled=canStart,onClick={setup=true},modifier=Modifier.fillMaxWidth()){Text(tr(R.string.ui_0451))}
        Text(tr(R.string.ui_0452),fontSize=12.sp,color=muted,lineHeight=20.sp,modifier=Modifier.padding(top=8.dp))
    }
    if(setup)AiVoiceSetupDialog(onClose={setup=false},onStart={setup=false;opening=true;onStart("ai")})
    if(player)NarrationPlayer(onClose={player=false},onCatalog=onCatalog)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun AiVoiceSetupDialog(onClose:()->Unit,onStart:()->Unit){
    val context=LocalContext.current
    val focus=LocalFocusManager.current
    val settings=remember{AiVoiceSettings(context)}
    val existing=remember{settings.load()}
    var url by remember{mutableStateOf(existing?.url.orEmpty())}
    var token by remember{mutableStateOf(existing?.token.orEmpty())}
    var style by remember{mutableStateOf(existing?.style?:"natural")}
    var busy by remember{mutableStateOf(false)}
    var status by remember{mutableStateOf("")}
    var issue by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val pairingFile=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null){scope.launch{
        try{val imported=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use{AiPairing.parse(String(it.readBytesLimited(32*1024),Charsets.UTF_8))}?:error(tr(R.string.ui_0453))};url=imported.url;token=imported.token;style=imported.style;status=tr(R.string.ui_0454);issue=false}
        catch(e:CancellationException){throw e}catch(e:Exception){status=tr(R.string.ui_0455);issue=true}
    }}}
    fun connect(start:Boolean){
        if(busy)return
        focus.clearFocus()
        busy=true;status=tr(R.string.ui_0456);issue=false
        scope.launch{
            try{
                val config=AiVoiceConfig(url.trim(),token.trim(),style)
                val health=AiVoiceClient(config,File(context.cacheDir,"ai-speech")).health()
                val styles=health.optJSONArray("styles")
                require(styles!=null&&(0 until styles.length()).any{styles.optString(it)==style}){tr(R.string.ui_0457)}
                val voices=health.optJSONArray("voices")
                require(voices!=null&&(0 until voices.length()).any{voices.optString(it)==config.voice}){tr(R.string.ui_0458)}
                status=tr(R.string.ui_0459, health.optString("model",tr(R.string.ui_0460)));issue=false
                if(start){settings.save(config.copy(revision=health.optString("model",config.revision)));onStart()}
            }catch(e:CancellationException){throw e}catch(e:Exception){issue=true;status=ContentRepository.friendlyError(e)}finally{busy=false}
        }
    }
    AlertDialog(onDismissRequest=onClose,title={Text(tr(R.string.ui_0451))},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState())){
        Text(tr(R.string.ui_0462),fontSize=13.sp,lineHeight=21.sp)
        TextButton(enabled=!busy,onClick={pairingFile.launch(arrayOf("application/json","text/plain"))}){Text(tr(R.string.ui_0463))}
        OutlinedTextField(value=url,onValueChange={url=it;status=""},enabled=!busy,label={Text(tr(R.string.ui_0464))},placeholder={Text(tr(R.string.ui_0465),fontSize=12.sp)},singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=14.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
        OutlinedTextField(value=token,onValueChange={token=it;status=""},enabled=!busy,label={Text(tr(R.string.ui_0466))},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=10.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
        Text(tr(R.string.ui_0467),fontSize=13.sp,modifier=Modifier.padding(top=16.dp))
        FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp)){AiStyles.forEach{(value,label)->FilterChip(selected=style==value,onClick={style=value;status=""},enabled=!busy,label={Text(label,fontSize=12.sp)})}}
        if(status.isNotBlank())Text(status,fontSize=12.sp,lineHeight=20.sp,color=if(issue)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,modifier=Modifier.padding(top=12.dp))
        TextButton(enabled=!busy,onClick={connect(false)}){Text(tr(R.string.ui_0178))}
        if(existing!=null)TextButton(enabled=!busy,onClick={if(Narration.state.value.mode=="ai"&&Narration.state.value.entry!=null)Narration.command(context,"stop");settings.clear();url="";token="";status=tr(R.string.ui_0469);issue=false}){Text(tr(R.string.ui_0470))}
        Text(tr(R.string.ui_0471),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,lineHeight=19.sp)
    }},confirmButton={TextButton(enabled=!busy,onClick={connect(true)}){Text(if(busy)tr(R.string.ui_0472)else tr(R.string.ui_0473))}},dismissButton={TextButton(onClick=onClose){Text(tr(R.string.ui_0189))}})
}
