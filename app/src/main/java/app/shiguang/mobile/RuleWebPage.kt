package app.shiguang.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

data class RulePage(val url:String,val html:String,val images:List<String> = emptyList())

/** A page-only browser: no JavascriptInterface, file access, downloads or native APIs. */
class RuleWebPage(private val context:Context) {
    companion object {private val slots=Semaphore(2)}
    suspend fun load(url:String,headers:Map<String,String>,actions:String="",query:String="",imagesScript:String="",waitSelector:String=""):RulePage=slots.withPermit {
        withContext(Dispatchers.Main) {
            withTimeout(35000) {
                val host=ResolverWindow.activity?.get()?.takeUnless{it.isDestroyed||it.isFinishing}
                var pageLoaded=false
                @SuppressLint("SetJavaScriptEnabled")
                val web=WebView(host ?: context).apply {
                    settings.javaScriptEnabled=true;settings.domStorageEnabled=true
                    settings.allowFileAccess=false;settings.allowContentAccess=false
                    settings.javaScriptCanOpenWindowsAutomatically=false;settings.setSupportMultipleWindows(false)
                    settings.loadsImagesAutomatically=false;settings.mediaPlaybackRequiresUserGesture=true
                    settings.mixedContentMode=WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    headers.entries.firstOrNull{it.key.equals("User-Agent",true)}?.let{settings.userAgentString=it.value}
                    webViewClient=object:WebViewClient(){
                        override fun onPageStarted(view:WebView,url:String,favicon:android.graphics.Bitmap?){pageLoaded=false}
                        override fun onPageFinished(view:WebView,url:String){pageLoaded=url!="about:blank"}
                        override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=!ContentRepository.isHttp(request.url.toString())
                        override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:android.net.http.SslError){handler.cancel()}
                    }
                    webChromeClient=object:WebChromeClient(){override fun onPermissionRequest(request:PermissionRequest){request.deny()}}
                    setDownloadListener{_,_,_,_,_->};alpha=0.01f;isClickable=false;isFocusable=false
                    importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
                suspend fun js(script:String):String=suspendCancellableCoroutine{c->web.evaluateJavascript(script){if(c.isActive)c.resume(it ?: "null")}}
                suspend fun waitFor(selector:String) {
                    repeat(60){if(js("!!document.querySelector(${JSONObject.quote(selector)})")=="true")return;delay(150)}
                    error(tr(R.string.ui_0711))
                }
                suspend fun navigate(target:String) {
                    SourceCodec.httpUrl(target);pageLoaded=false;web.loadUrl(target,headers)
                    delay(500)
                    repeat(100){if(pageLoaded&&js("document.readyState")=="\"complete\"")return;delay(150)}
                    error(tr(R.string.ui_0712))
                }
                try {
                    (host?.window?.decorView as? ViewGroup)?.addView(web,0,ViewGroup.LayoutParams(360,640))
                    web.onResume()
                    if(actions.isBlank())navigate(url) else {
                        val a=JSONArray(actions);require(a.length()<=30){tr(R.string.ui_0713)}
                        if(a.length()==0||!a.getJSONObject(0).has("goto"))navigate(url)
                        for(i in 0 until a.length()) {
                            val action=a.getJSONObject(i)
                            when {
                                action.has("goto")->navigate(action.getString("goto"))
                                action.has("wait")->if(action.opt("wait") is Number)delay(action.getLong("wait").coerceIn(0,7000)) else waitFor(action.getString("wait"))
                                action.has("typeSearchKey")->{val selector=action.getString("typeSearchKey");waitFor(selector);js("(function(){var e=document.querySelector(${JSONObject.quote(selector)});e.value=${JSONObject.quote(query)};e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));})()")}
                                action.has("click")||action.has("clickIfExist")->{val optional=action.has("clickIfExist");val selector=action.getString(if(optional)"clickIfExist" else "click");if(!optional)waitFor(selector);js("document.querySelector(${JSONObject.quote(selector)})?.click()");delay(600)}
                                else->error(tr(R.string.ui_0714, action.keys().asSequence().firstOrNull()))
                            }
                        }
                    }
                    if(waitSelector.isNotBlank())waitFor(waitSelector)
                    var images=emptyList<String>()
                    if(imagesScript.isNotBlank()) {
                        // Result remains inside this isolated page; the page receives no Android bridge.
                        js("window.__sgRuleResult=null;window.__sgRuleError=null;Promise.resolve().then(function(){return ($imagesScript);}).then(function(v){window.__sgRuleResult=v;}).catch(function(){window.__sgRuleError=true;});")
                        for(i in 0..70) {
                            val raw=js("JSON.stringify(window.__sgRuleResult)")
                            if(raw!="\"null\""&&raw!="null") {
                                val decoded=org.json.JSONTokener(raw).nextValue().toString()
                                val array=runCatching{JSONArray(decoded)}.getOrNull()
                                if(array!=null)images=(0 until array.length()).map{array.optString(it)}.filter{ContentRepository.isHttp(it)}.distinct()
                                if(images.isNotEmpty())break
                            }
                            if(js("window.__sgRuleError===true")=="true")error(tr(R.string.ui_0715))
                            js("window.scrollBy(0,640)");delay(150)
                        }
                    }
                    val raw=js("document.documentElement.outerHTML")
                    val html=org.json.JSONTokener(raw).nextValue().toString()
                    require(html.length<=8*1024*1024){tr(R.string.ui_0716)}
                    RulePage(web.url ?: url,html,images)
                } finally {
                    web.stopLoading();(web.parent as? ViewGroup)?.removeView(web);web.loadUrl("about:blank");web.destroy()
                }
            }
        }
    }
}
