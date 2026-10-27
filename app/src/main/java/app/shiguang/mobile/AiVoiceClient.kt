package app.shiguang.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AiVoiceConfig(val url:String,val token:String,val style:String="natural",val voice:String="synthetic-female",val revision:String="CosyVoice3-0.5B") {
    fun validate(){
        SourceCodec.httpUrl(url);val uri=URI(url)
        val host=uri.host.orEmpty().lowercase()
        val octets=host.split('.').takeIf{it.size==4}?.map{it.toIntOrNull()}?.takeIf{it.all{n->n!=null&&n in 0..255}}
        val privateHttp=host=="localhost"||host=="[::1]"||host=="::1"||octets?.let{it[0]==127||it[0]==10||(it[0]==192&&it[1]==168)||(it[0]==172&&it[1]!! in 16..31)}==true
        require(uri.scheme.lowercase()=="https"||privateHttp){tr(R.string.ui_0014)}
        require(uri.query==null&&uri.fragment==null){tr(R.string.ui_0015)}
        require(token.length>=24&&!token.contains('\n')&&!token.contains('\r')){tr(R.string.ui_0016)}
        require(style in setOf("natural","happy","sad","angry")){tr(R.string.ui_0017)}
    }
}

/** Optional private AI endpoint; the default system narration path does not instantiate this client. */
class AiVoiceBusyException:IOException(tr(R.string.ui_0018))
class AiVoiceClient(private val config:AiVoiceConfig,private val directory:File) {
    val configuredStyle get()=config.style
    val configuredVoice get()=config.voice
    private val client=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).connectTimeout(10,TimeUnit.SECONDS).readTimeout(120,TimeUnit.SECONDS).callTimeout(125,TimeUnit.SECONDS).build()
    init{config.validate()}
    private suspend fun bytes(path:String,body:JSONObject?=null,max:Int):ByteArray=suspendCancellableCoroutine{continuation->
        val builder=Request.Builder().url(config.url.trimEnd('/')+path).header("Authorization","Bearer ${config.token}")
        if(body!=null)builder.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call=client.newCall(builder.build());continuation.invokeOnCancellation{call.cancel()}
        call.enqueue(object:Callback{
            override fun onFailure(call:Call,e:IOException){if(continuation.isActive)continuation.resumeWithException(IOException(tr(R.string.ui_0019),e))}
            override fun onResponse(call:Call,response:Response){
                try{val value=response.use{r->when(r.code){401,403->error(tr(R.string.ui_0020));429->throw AiVoiceBusyException()};require(r.isSuccessful){tr(R.string.ui_0021, r.code)};val b=r.body?:error(tr(R.string.ui_0022));require(b.contentLength()<0||b.contentLength()<=max){tr(R.string.ui_0023)};b.byteStream().use{it.readBytesLimited(max)}};if(continuation.isActive)continuation.resume(value)}catch(e:Exception){if(continuation.isActive)continuation.resumeWithException(e)}
            }
        })
    }
    suspend fun health():JSONObject=JSONObject(String(bytes("/health",max=128*1024),Charsets.UTF_8)).also{require(it.optBoolean("ready")){tr(R.string.ui_0024)}}
    suspend fun synthesize(text:String,rate:Float=1f):File {
        require(text.isNotBlank()&&text.length<=280){tr(R.string.ui_0025)}
        require(rate in .5f..2f)
        val key=SourceCodec.stableId("${config.url}|${config.revision}|${config.voice}|${config.style}|$rate|$text")
        val file=File(directory,"$key.wav")
        withContext(Dispatchers.IO){directory.mkdirs()}
        if(withContext(Dispatchers.IO){if(file.isFile&&file.length()>44){file.setLastModified(System.currentTimeMillis());true}else false})return file
        val audio=bytes("/v1/speech",JSONObject().put("text",text).put("style",config.style).put("voice",config.voice).put("speed",rate),8*1024*1024)
        require(audio.size>44&&String(audio,0,4,Charsets.US_ASCII)=="RIFF"&&String(audio,8,4,Charsets.US_ASCII)=="WAVE"){tr(R.string.ui_0026)}
        return withContext(Dispatchers.IO){
            coroutineContext.ensureActive()
            val tmp=File.createTempFile("speech-",".tmp",directory)
            try{tmp.writeBytes(audio);check(tmp.renameTo(file)){tr(R.string.ui_0027)}}finally{tmp.delete()}
            var total=directory.listFiles().orEmpty().sumOf{it.length()}
            directory.listFiles().orEmpty().filter{it.extension=="wav"&&it!=file}.sortedBy{it.lastModified()}.forEach{if(total>24*1024*1024){val size=it.length();if(it.delete())total-=size}}
            file
        }
    }
}
