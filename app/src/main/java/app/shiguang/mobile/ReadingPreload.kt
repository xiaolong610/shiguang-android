package app.shiguang.mobile

import android.content.Context
import android.net.ConnectivityManager
import coil.ImageLoader
import coil.decode.SvgDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Dimension
import coil.size.Size

object ReadingPreload {
    fun allowed(context:Context,source:Source?):Boolean = source?.engine in setOf("demo","local") ||
        runCatching{context.getSystemService(ConnectivityManager::class.java)?.let{it.activeNetwork!=null&&!it.isActiveNetworkMetered}?:false}.getOrDefault(false)
    fun images(context:Context):ImageLoader=AppImages.get(context)
    fun image(context:Context,repo:ContentRepository,source:Source?,url:String,width:Int,retry:Boolean=false):ImageRequest {
        val fingerprint=source?.let{SourceCodec.stableId(it.json().toString())}.orEmpty()
        return ImageRequest.Builder(context).data(url).size(Size(Dimension.Pixels(width.coerceIn(1,1440)),Dimension.Pixels(8192)))
            .scale(coil.size.Scale.FIT).precision(coil.size.Precision.EXACT)
            .memoryCacheKey("$fingerprint:$url:$width").diskCacheKey("$fingerprint:$url")
            .apply{source?.let{repo.headers(it).forEach{(k,v)->addHeader(k,v)}};if(retry){memoryCachePolicy(CachePolicy.WRITE_ONLY);diskCachePolicy(CachePolicy.WRITE_ONLY)}}.build()
    }
}
