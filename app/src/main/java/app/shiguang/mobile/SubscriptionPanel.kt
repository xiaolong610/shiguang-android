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
    // Source changes are delivered by LibraryEvents; no periodic database reads.
    fun run(id:String,action:suspend ()->String){busy=id;scope.launch{try{notify(action())}catch(e:CancellationException){throw e}catch(e:Exception){notify(ContentRepository.friendlyError(e))}finally{busy=null}}}
    Column {
        Row(verticalAlignment=Alignment.CenterVertically){Text(tr(R.string.ui_0797),fontSize=11.sp,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.weight(1f));IconButton(enabled=busy==null&&model.subscriptions.any{it.enabled},onClick={run("all"){val results=mutableListOf<String>();model.subscriptions.filter{it.enabled}.forEach{results+=model.refreshSubscription(it.id)};results.joinToString("；")}}){Icon(Icons.Outlined.Refresh,tr(R.string.ui_0798))}}
        if(model.subscriptions.isEmpty())Text(tr(R.string.ui_0799),fontSize=13.sp,modifier=Modifier.padding(vertical=24.dp))
        model.subscriptions.forEach{sub->
            Row(Modifier.fillMaxWidth().heightIn(min=88.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f).padding(vertical=10.dp)){
                Text(sub.name,fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(if(sub.succeeded>0)tr(R.string.ui_0800, DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT,AppLanguage.locale).format(Date(sub.succeeded)))else sub.url,fontSize=10.sp,maxLines=1,overflow=TextOverflow.Ellipsis,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if(busy==sub.id)tr(R.string.ui_0801)else if(!sub.enabled)tr(R.string.ui_0802)else sub.message,fontSize=11.sp,maxLines=2,overflow=TextOverflow.Ellipsis,color=if(AppLanguage.refreshFailed(sub.message))MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            };Switch(sub.enabled,onCheckedChange={model.toggleSubscription(sub)})
                Box{IconButton(onClick={menu=sub.id}){Icon(Icons.Outlined.MoreVert,tr(R.string.ui_0804, sub.name))};DropdownMenu(menu==sub.id,{menu=null}){
                    DropdownMenuItem(text={Text(tr(R.string.ui_0805))},enabled=busy==null,onClick={menu=null;run(sub.id){model.refreshSubscription(sub.id)}})
                    DropdownMenuItem(text={Text(tr(R.string.ui_0806))},enabled=busy==null&&sub.backup!="[]",onClick={menu=null;run(sub.id){model.restoreSubscription(sub.id)}})
                    DropdownMenuItem(text={Text(tr(R.string.ui_0807))},onClick={menu=null;removing=sub})
                }}
            };HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant.copy(alpha=.4f))
        }
    }
    removing?.let{sub->AlertDialog(onDismissRequest={removing=null},title={Text(tr(R.string.ui_0808))},text={Text(tr(R.string.ui_0809))},confirmButton={TextButton(onClick={model.removeSubscription(sub);removing=null}){Text(tr(R.string.ui_0807))}},dismissButton={TextButton(onClick={removing=null}){Text(tr(R.string.ui_0811))}})}
}
