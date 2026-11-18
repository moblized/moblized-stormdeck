package com.moblized.stormdeck.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One HTTP client for the whole app, with a disk cache. Every public weather source here is free and keyless. */
object Http {
    // The National Weather Service asks every app to identify itself.
    const val USER_AGENT = "StormDeck/1.0 (+https://github.com/moblized/moblized-stormdeck)"

    @Volatile
    private var _client: OkHttpClient? = null

    val client: OkHttpClient
        get() = _client ?: synchronized(this) {
            _client ?: builder().build().also { _client = it }
        }

    private fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val req = chain.request()
            val withUa = if (req.header("User-Agent") == null) {
                req.newBuilder().header("User-Agent", USER_AGENT).build()
            } else {
                req
            }
            chain.proceed(withUa)
        }

    fun init(context: Context) {
        _client = builder().cache(Cache(File(context.cacheDir, "http"), 64L * 1024 * 1024)).build()
    }

    suspend fun text(url: String, accept: String? = null): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url)
        if (accept != null) b.header("Accept", accept)
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("${hostOf(url)} answered ${r.code}")
            r.body?.string() ?: throw IOException("Empty answer from ${hostOf(url)}")
        }
    }

    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IOException("${hostOf(url)} answered ${r.code}")
            r.body?.bytes() ?: throw IOException("Empty answer from ${hostOf(url)}")
        }
    }

    /** Blocking variant for code that already runs on a background thread (tile renderer). */
    fun bytesBlocking(url: String): ByteArray? = runCatching {
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (r.isSuccessful) r.body?.bytes() else null
        }
    }.getOrNull()

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')
}

/** Turns network failures into a short sentence for the screen. */
fun friendlyError(e: Throwable): String = when (e) {
    is java.net.UnknownHostException -> "No connection. Check your internet."
    is java.net.SocketTimeoutException -> "The server took too long to answer."
    else -> e.message?.takeIf { it.isNotBlank() } ?: "Something went wrong."
}
