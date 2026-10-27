package app.shiguang.mobile

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Android string resources are also available to workers and foreground services. */
object AppLanguage {
    @Volatile private var app:Context?=null
    @Volatile private var localized:Resources?=null
    fun selection(context:Context):String = if(Build.VERSION.SDK_INT>=33)
        context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().let{if(it.startsWith("zh"))"zh-CN" else if(it.startsWith("en"))"en" else "system"}
        else context.getSharedPreferences("app-language",0).getString("language","system")?:"system"
    fun wrap(context:Context):Context {
        val selected=selection(context)
        val locales=if(selected=="system")Resources.getSystem().configuration.locales else LocaleList.forLanguageTags(selected)
        // Override only locales: copying the full configuration would pin orientation,
        // screen size and font scale when the player rotates or the device changes them.
        return context.createConfigurationContext(Configuration().apply{setLocales(locales)})
    }
    fun initialize(context:Context){app=context.applicationContext;refresh()}
    fun refresh(){app?.let{localized=wrap(it).resources}}
    val locale:Locale get()=localized?.configuration?.locales?.get(0)?:Locale.CHINESE
    val tag:String get()=locale.toLanguageTag()
    fun text(@StringRes id:Int,vararg args:Any?):String {
        val values=args.map{it?.toString().orEmpty()}.toTypedArray()
        localized?.let{return if(values.isEmpty())it.getString(id)else it.getString(id,*values)}
        val fallback=textFallbacks[id]?:""
        return if(values.isEmpty())fallback else String.format(Locale.CHINESE,fallback,*values)
    }
    fun choose(activity:Activity,value:String){
        require(value in setOf("system","en","zh-CN"))
        if(selection(activity)==value)return
        if(Build.VERSION.SDK_INT>=33){
            activity.getSystemService(LocaleManager::class.java).applicationLocales=if(value=="system")LocaleList.getEmptyLocaleList()else LocaleList.forLanguageTags(value)
            refresh()
        }else{
            activity.getSharedPreferences("app-language",0).edit().putString("language",value).apply()
            refresh();activity.recreate()
        }
    }
    /** Earlier versions stored translated refresh messages. Recognize errors in either language. */
    fun refreshFailed(message:String)=message.startsWith("刷新失败")||message.startsWith("Refresh failed")
}

fun tr(@StringRes id:Int,vararg args:Any?):String=AppLanguage.text(id,*args)

@Composable fun LanguageDialog(onClose:()->Unit){
    val context=LocalContext.current
    val selected=AppLanguage.selection(context)
    AlertDialog(onDismissRequest=onClose,title={Text(tr(R.string.language_title))},text={Column{
        listOf("system" to tr(R.string.language_system),"en" to "English","zh-CN" to "简体中文").forEach{(value,label)->
            TextButton(onClick={onClose();(context as? Activity)?.let{AppLanguage.choose(it,value)}},modifier=Modifier.fillMaxWidth()){
                RadioButton(selected==value,onClick=null);Spacer(Modifier.width(10.dp));Text(label,Modifier.weight(1f))
            }
        }
        Text(tr(R.string.language_content_note),style=MaterialTheme.typography.bodySmall)
    }},confirmButton={TextButton(onClick=onClose){Text(tr(R.string.ui_0189))}})
}

object NarrationLanguage {
    /** Content language is independent of the interface language. */
    fun code(text:String):String {
        val sample=text.take(4000)
        val han=sample.count{it in '\u3400'..'\u9fff'}
        val latin=sample.count{it in 'a'..'z'||it in 'A'..'Z'}
        return if(han>0&&han*2>=latin)"zh" else "en"
    }
}
