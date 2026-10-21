package app.shiguang.mobile

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable fun SubscriptionPanel(model:AppModel,notify:(String)->Unit) {
    val scope=rememberCoroutineScope();var busy by remember{mutableStateOf<String?>(null)};var removing by remember{mutableStateOf<SourceSubscription?>(null)};var menu by remember{mutableStateOf<String?>(null)}
    LaunchedEffect(Unit){while(true){model.refreshSources();delay(5000)}}
    fun run(id:String,action:suspend ()->String){busy=id;scope.launch{try{notify(action())}catch(e:CancellationException){throw e}catch(e:Exception){notify(ContentRepository.friendlyError(e))}finally{busy=null}}}
    Column {
        Row(verticalAlignment=Alignment.CenterVertically){Text("自动检查更新 · 约每12小时",fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f));IconButton(enabled=busy==null&&model.subscriptions.any{it.enabled},onClick={run("all"){val results=mutableListOf<String>();model.subscriptions.filter{it.enabled}.forEach{results+=model.refreshSubscription(it.id)};results.joinToString("；")}}){Icon(Icons.Outlined.Refresh,"刷新全部订阅")}}
        if(model.subscriptions.isEmpty())Text("还没有订阅，点击右上角“＋”添加。",fontSize=13.sp,modifier=Modifier.padding(vertical=24.dp))
        model.subscriptions.forEach{sub->
            Row(Modifier.fillMaxWidth().heightIn(min=88.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).padding(vertical=10.dp)){
                Text(sub.name,fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(if(sub.succeeded>0)"上次成功：${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(sub.succeeded))}"else sub.url,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if(busy==sub.id)"正在刷新…"else if(!sub.enabled)"自动更新已暂停"else sub.message,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=if(sub.message.startsWith("刷新失败"))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            };Switch(sub.enabled,onCheckedChange={model.toggleSubscription(sub)})
                Box{IconButton(onClick={menu=sub.id}){Icon(Icons.Outlined.MoreVert,"${sub.name}订阅操作")};DropdownMenu(menu==sub.id,{menu=null}){
                    DropdownMenuItem(text={Text("立即刷新")},enabled=busy==null,onClick={menu=null;run(sub.id){model.refreshSubscription(sub.id)}})
                    DropdownMenuItem(text={Text("恢复上一版")},enabled=busy==null&&sub.backup!="[]",onClick={menu=null;run(sub.id){model.restoreSubscription(sub.id)}})
                    DropdownMenuItem(text={Text("取消订阅")},onClick={menu=null;removing=sub})
                }}
            };HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
        }
    }
    removing?.let{sub->AlertDialog(onDismissRequest={removing=null},title={Text("取消订阅？")},text={Text("停止检查此链接，已导入的内容源和收藏会保留。")},confirmButton={TextButton(onClick={model.removeSubscription(sub);removing=null}){Text("取消订阅")}},dismissButton={TextButton(onClick={removing=null}){Text("保留")}})}
}
