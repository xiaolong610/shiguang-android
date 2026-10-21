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

private val AiStyles=linkedMapOf("natural" to "自然","happy" to "开心","sad" to "伤感","angry" to "愤怒")

@Composable fun NarrationPanel(speech:NarrationState,matching:Boolean,canStart:Boolean,onStart:(String)->Unit,onCatalog:()->Unit){
    val context=LocalContext.current
    var setup by remember{mutableStateOf(false)}
    var player by remember{mutableStateOf(false)}
    var opening by remember{mutableStateOf(false)}
    LaunchedEffect(opening,matching,speech.entry){if(opening&&matching){opening=false;player=true}}
    val muted=MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.verticalScroll(rememberScrollState())){
        Text("听这本书",fontSize=23.sp)
        Text(if(matching&&speech.mode=="ai")"AI 情感语音 · ${AiStyles[speech.style]?:"自然"}"else "手机离线语音 · 文字不发送到云端",fontSize=12.sp,color=muted,modifier=Modifier.padding(top=8.dp,bottom=18.dp))
        if(matching){
            Text(speech.message,fontSize=13.sp,color=muted)
            Spacer(Modifier.height(12.dp))
            Button(onClick={player=true},modifier=Modifier.fillMaxWidth()){Text("打开听书页")}

        }
        Spacer(Modifier.height(12.dp))
        if(!matching)Button(enabled=canStart,onClick={opening=true;onStart("system")},modifier=Modifier.fillMaxWidth()){Text("从当前阅读位置开始")}
        TextButton(onClick={runCatching{context.startActivity(Intent("com.android.settings.TTS_SETTINGS"))}.recoverCatching{context.startActivity(Intent(Settings.ACTION_SETTINGS))}}){Text("打开手机语音设置")}
        Text("首次使用请在系统中安装中文离线语音包，音质由手机语音引擎决定。",fontSize=12.sp,color=muted,lineHeight=20.sp)
        HorizontalDivider(Modifier.padding(vertical=18.dp))
        OutlinedButton(enabled=canStart,onClick={setup=true},modifier=Modifier.fillMaxWidth()){Text("AI 情感听书")}
        Text("可连接自己的电脑语音服务，选择不同朗读风格。只有主动开启后，朗读段落才会发送到该服务。",fontSize=12.sp,color=muted,lineHeight=20.sp,modifier=Modifier.padding(top=8.dp))
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
        try{val imported=withContext(Dispatchers.IO){context.contentResolver.openInputStream(uri)?.use{AiPairing.parse(String(it.readBytesLimited(32*1024),Charsets.UTF_8))}?:error("无法读取配对文件")};url=imported.url;token=imported.token;style=imported.style;status="已载入配对信息，请测试连接后开启";issue=false}
        catch(e:CancellationException){throw e}catch(e:Exception){status="无法读取配对文件，请确认它由电脑语音助手生成";issue=true}
    }}}
    fun connect(start:Boolean){
        if(busy)return
        focus.clearFocus()
        busy=true;status="正在连接语音服务…";issue=false
        scope.launch{
            try{
                val config=AiVoiceConfig(url.trim(),token.trim(),style)
                val health=AiVoiceClient(config,File(context.cacheDir,"ai-speech")).health()
                val styles=health.optJSONArray("styles")
                require(styles!=null&&(0 until styles.length()).any{styles.optString(it)==style}){"此服务不支持所选朗读风格"}
                val voices=health.optJSONArray("voices")
                require(voices!=null&&(0 until voices.length()).any{voices.optString(it)==config.voice}){"此服务没有提供兼容的朗读音色"}
                status="连接成功 · ${health.optString("model","AI 语音")}";issue=false
                if(start){settings.save(config.copy(revision=health.optString("model",config.revision)));onStart()}
            }catch(e:CancellationException){throw e}catch(e:Exception){issue=true;status=ContentRepository.friendlyError(e)}finally{busy=false}
        }
    }
    AlertDialog(onDismissRequest=onClose,title={Text("AI 情感听书")},text={Column(Modifier.heightIn(max=500.dp).verticalScroll(rememberScrollState())){
        Text("配对你自己的语音服务。开启后会向下方地址发送当前朗读段落；手机系统听书仍可离线使用。",fontSize=13.sp,lineHeight=21.sp)
        TextButton(enabled=!busy,onClick={pairingFile.launch(arrayOf("application/json","text/plain"))}){Text("导入电脑配对文件")}
        OutlinedTextField(value=url,onValueChange={url=it;status=""},enabled=!busy,label={Text("语音服务地址")},placeholder={Text("http://电脑局域网IP:8765",fontSize=12.sp)},singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=14.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri))
        OutlinedTextField(value=token,onValueChange={token=it;status=""},enabled=!busy,label={Text("设备配对密钥")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth().padding(top=10.dp),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Password))
        Text("朗读风格",fontSize=13.sp,modifier=Modifier.padding(top=16.dp))
        FlowRow(horizontalArrangement=Arrangement.spacedBy(5.dp)){AiStyles.forEach{(value,label)->FilterChip(selected=style==value,onClick={style=value;status=""},enabled=!busy,label={Text(label,fontSize=12.sp)})}}
        if(status.isNotBlank())Text(status,fontSize=12.sp,lineHeight=20.sp,color=if(issue)MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,modifier=Modifier.padding(top=12.dp))
        TextButton(enabled=!busy,onClick={connect(false)}){Text("测试连接")}
        if(existing!=null)TextButton(enabled=!busy,onClick={if(Narration.state.value.mode=="ai"&&Narration.state.value.entry!=null)Narration.command(context,"stop");settings.clear();url="";token="";status="已解除配对，系统语音不受影响";issue=false}){Text("解除配对")}
        Text("密钥加密保存在此设备。公网服务须使用 HTTPS；局域网地址应填写电脑实际 IP。",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,lineHeight=19.sp)
    }},confirmButton={TextButton(enabled=!busy,onClick={connect(true)}){Text(if(busy)"正在连接…"else"保存并开始AI听书")}},dismissButton={TextButton(onClick=onClose){Text("取消")}})
}
