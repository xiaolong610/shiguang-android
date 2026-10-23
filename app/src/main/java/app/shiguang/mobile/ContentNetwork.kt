package app.shiguang.mobile

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object ContentNetwork {
    val foreground=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(18,TimeUnit.SECONDS).callTimeout(25,TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply{maxRequests=6;maxRequestsPerHost=3}).build()
    val search=foreground.newBuilder().connectTimeout(4,TimeUnit.SECONDS).readTimeout(6,TimeUnit.SECONDS).callTimeout(7,TimeUnit.SECONDS)
        .dispatcher(Dispatcher().apply{maxRequests=6;maxRequestsPerHost=2}).build()
}
