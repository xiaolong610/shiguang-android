package app.shiguang.mobile

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.*
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import android.app.Activity
import android.view.View
import android.view.ViewGroup
import java.lang.ref.WeakReference

object ResolverWindow {var activity:WeakReference<Activity>?=null}

data class ResolvedVideo(val url:String,val headers:Map<String,String>,val mime:String="")

/** Web content can only report candidate URLs; it has no file/system bridge. */
class WebVideoResolver(private val context:Context,private val repo:ContentRepository=ContentRepository()) {
    companion object {
        private val slots=Semaphore(2)
        fun direct(url:String)=runCatching{URI(url).path.lowercase().let{p->listOf(".m3u8",".mp4",".m4v",".webm",".mpd").any{p.endsWith(it)}}}.getOrDefault(false)
        private val adPath=Regex("(?:googleads|doubleclick|/ads?/|/advert|/commercial)",RegexOption.IGNORE_CASE)
        private const val script="""
        (function(){
          if(window.__shiguangResolver)return;window.__shiguangResolver=true;
          function send(u,t){try{u=new URL(u,document.baseURI).href;if(/^https?:/.test(u))ShiguangMedia.found(u,location.href,t||'');}catch(e){}}
          function scan(){document.querySelectorAll('video,video source,audio source').forEach(function(v){var u=v.currentSrc||v.src;if(u&&!u.startsWith('blob:'))send(u,v.type);});}
          new MutationObserver(scan).observe(document,{subtree:true,childList:true,attributes:true,attributeFilter:['src']});
          document.addEventListener('loadedmetadata',scan,true);document.addEventListener('play',scan,true);setInterval(scan,700);scan();
          var oldFetch=window.fetch;if(oldFetch)window.fetch=function(){return oldFetch.apply(this,arguments).then(function(r){var ct=r.headers.get('content-type')||'';if(/mpegurl|video\//i.test(ct))send(r.url,ct);else if(/text|json/.test(ct)){var c=r.clone();if(c.body&&c.body.getReader){var reader=c.body.getReader();reader.read().then(function(v){var t=new TextDecoder().decode(v.value||new Uint8Array());if(t.trim().indexOf('#EXTM3U')===0)send(r.url,'application/vnd.apple.mpegurl');reader.cancel().catch(function(){});}).catch(function(){});}}return r;});};
          var oldOpen=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){this.addEventListener('load',function(){try{var ct=this.getResponseHeader('content-type')||'';if(/mpegurl|video\//i.test(ct))send(this.responseURL||u,ct);else if((!this.responseType||this.responseType==='text')&&this.responseText.trim().indexOf('#EXTM3U')===0)send(this.responseURL||u,'application/vnd.apple.mpegurl');}catch(e){}});return oldOpen.apply(this,arguments);};
          try{new PerformanceObserver(function(list){list.getEntries().forEach(function(e){if(/\.(m3u8|mp4|webm|mpd)(\?|$)/i.test(e.name))send(e.name,'');});}).observe({entryTypes:['resource']});}catch(e){}
        })();
        """
    }
    suspend fun resolve(s:Source?,page:String,onStatus:(String)->Unit={}):ResolvedVideo {
        if(page.startsWith("asset:///"))return ResolvedVideo(page,emptyMap())
        SourceCodec.httpUrl(page)
        val configured=s?.let{repo.headers(it)} ?: emptyMap()
        if(direct(page))return ResolvedVideo(page,withCookies(page,configured),if(Uri.parse(page).path?.endsWith("m3u8",true)==true)"application/x-mpegURL" else "")
        return slots.withPermit {withContext(Dispatchers.Main){withTimeout(40000){coroutineScope {
            onStatus(tr(R.string.ui_0825))
            val result=CompletableDeferred<ResolvedVideo>();val validationJob=SupervisorJob(coroutineContext[Job]);val scope=CoroutineScope(coroutineContext+validationJob)
            val seen=mutableSetOf<String>();var lastError="";var web:WebView?=null
            fun offer(raw:String,referer:String,mime:String,requestHeaders:Map<String,String> = emptyMap()) {
                scope.launch(Dispatchers.Main) {
                    if(result.isCompleted||!ContentRepository.isHttp(raw)||adPath.containsMatchIn(raw))return@launch
                    if(!seen.add(raw)||seen.size>30)return@launch
                    val headerMap=configured.toMutableMap()
                    requestHeaders.filterKeys{it.equals("Referer",true)||it.equals("User-Agent",true)||it.equals("Origin",true)||it.equals("Cookie",true)}.forEach{(k,v)->headerMap[k]=v}
                    if(headerMap.keys.none{it.equals("Referer",true)})headerMap["Referer"]=referer.takeIf{ContentRepository.isHttp(it)} ?: page
                    if(headerMap.keys.none{it.equals("User-Agent",true)})headerMap["User-Agent"]=web?.settings?.userAgentString.orEmpty()
                    val headers=withCookies(raw,headerMap)
                    val candidate=runCatching {validate(raw,headers,mime)}
                    candidate.onSuccess{if(!result.isCompleted)result.complete(it)}.onFailure{lastError=ContentRepository.friendlyError(it)}
                }
            }
            val bridge=object {
                @JavascriptInterface fun found(url:String,referer:String,type:String) {offer(url,referer,type)}
            }
            @SuppressLint("SetJavaScriptEnabled")
            fun createWeb():WebView=WebView(ResolverWindow.activity?.get()?.takeUnless{it.isDestroyed||it.isFinishing} ?: context).apply {
                settings.javaScriptEnabled=true;settings.domStorageEnabled=true;settings.allowFileAccess=false;settings.allowContentAccess=false
                settings.javaScriptCanOpenWindowsAutomatically=false;settings.setSupportMultipleWindows(false)
                settings.mediaPlaybackRequiresUserGesture=true;settings.loadsImagesAutomatically=false
                settings.mixedContentMode=WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                configured.entries.firstOrNull{it.key.equals("User-Agent",true)}?.let{settings.userAgentString=it.value}
                CookieManager.getInstance().setAcceptThirdPartyCookies(this,true)
                addJavascriptInterface(bridge,"ShiguangMedia")
                val startScript=WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if(startScript)WebViewCompat.addDocumentStartJavaScript(this,script,setOf("*"))
                webViewClient=object:WebViewClient() {
                    override fun shouldOverrideUrlLoading(view:WebView,request:WebResourceRequest)=!ContentRepository.isHttp(request.url.toString())
                    override fun shouldInterceptRequest(view:WebView,request:WebResourceRequest):WebResourceResponse? {
                        val url=request.url.toString()
                        if(!ContentRepository.isHttp(url)||adPath.containsMatchIn(url))return WebResourceResponse("text/plain","utf-8",java.io.ByteArrayInputStream(byteArrayOf()))
                        if(direct(url))offer(url,request.requestHeaders.entries.firstOrNull{it.key.equals("Referer",true)}?.value ?: page,"",request.requestHeaders)
                        return null
                    }
                    override fun onPageStarted(view:WebView,url:String?,favicon:android.graphics.Bitmap?){if(!startScript)view.evaluateJavascript(script,null)}
                    override fun onPageFinished(view:WebView,url:String?){view.evaluateJavascript(script,null)}
                    override fun onReceivedError(view:WebView,request:WebResourceRequest,error:WebResourceError){if(BuildConfig.DEBUG)android.util.Log.d("ShiguangResolver","network ${request.url.host}, main=${request.isForMainFrame}: ${error.errorCode}")}
                    override fun onReceivedSslError(view:WebView,handler:SslErrorHandler,error:android.net.http.SslError){handler.cancel();lastError=tr(R.string.ui_0252)}
                }
                webChromeClient=object:WebChromeClient(){override fun onPermissionRequest(request:PermissionRequest){request.deny()}}
                setDownloadListener{_,_,_,_,_->}
            }
            try {
                web=createWeb()
                // Give layout-dependent players a live rendering context without covering the app UI.
                val host=ResolverWindow.activity?.get()?.takeUnless{it.isDestroyed||it.isFinishing}
                val root=host?.window?.decorView as? ViewGroup
                web!!.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                web!!.isFocusable=false;web!!.isClickable=false;web!!.alpha=0.01f
                if(root!=null)root.addView(web,0,ViewGroup.LayoutParams(320,180))
                else {web!!.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(180,View.MeasureSpec.EXACTLY));web!!.layout(0,0,320,180)}
                web!!.onResume();web!!.loadUrl(page,configured);result.await()
            }
            catch(e:TimeoutCancellationException){error(tr(R.string.ui_0827, lastError.take(70)))}
            finally {validationJob.cancel();web?.let{it.stopLoading();it.removeJavascriptInterface("ShiguangMedia");(it.parent as? ViewGroup)?.removeView(it);it.loadUrl("about:blank");it.destroy()};result.cancel()}
        }}}}
    }
    private fun withCookies(url:String,headers:Map<String,String>):Map<String,String> {
        val out=headers.toMutableMap()
        if(out.keys.none{it.equals("Cookie",true)})CookieManager.getInstance().getCookie(url)?.takeIf{it.isNotBlank()}?.let{out["Cookie"]=it}
        return out
    }
    private suspend fun validate(url:String,headers:Map<String,String>,hint:String):ResolvedVideo=withContext(Dispatchers.IO) {
        val request=Request.Builder().url(url).header("Range","bytes=0-65535")
        headers.forEach{(k,v)->request.header(k,v)}
        repo.client.newCall(request.build()).execute().use{response->
            require(response.isSuccessful){tr(R.string.ui_0828, response.code)}
            val body=response.body ?: error(tr(R.string.ui_0829))
            val bytes=body.byteStream().use{input->val buffer=ByteArray(65536);var length=0;while(length<buffer.size){val n=input.read(buffer,length,buffer.size-length);if(n<0)break;length+=n};buffer.copyOf(length)}
            val text=String(bytes,Charsets.UTF_8).trimStart('\uFEFF',' ','\r','\n','\t')
            val ct=response.header("Content-Type").orEmpty()
            val mime=when {
                text.startsWith("#EXTM3U")->"application/x-mpegURL"
                text.contains("<MPD")&&text.take(100).contains('<')->"application/dash+xml"
                bytes.size>=12&&String(bytes,4,4,Charsets.US_ASCII)=="ftyp"->"video/mp4"
                ct.startsWith("video/")&&!text.startsWith('<')->ct.substringBefore(';')
                else->error(tr(R.string.ui_0830))
            }
            // Preserve the URL observed by the page so its cookies/redirect policy stay scoped.
            ResolvedVideo(url,headers,mime)
        }
    }
}
