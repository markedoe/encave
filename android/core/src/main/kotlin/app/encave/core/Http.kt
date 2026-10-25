package app.encave.core

import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import okhttp3.Interceptor

/**
 * Every request the apps make goes through here, so it says only "Encave/<version>". Without
 * it Android sends "Dalvik/… (Linux; U; Android 16; <device model> Build/…)" to the AI
 * provider, the search service, the relay and the hosts of pictures.
 */
object Http {
    /** Set once at launch by the app, from its version name. */
    @Volatile var userAgent: String = "Encave"
        private set

    fun setVersion(versionName: String) {
        userAgent = "Encave/$versionName"
    }

    /** An HTTP connection that sends [userAgent]. */
    fun open(url: URL): HttpURLConnection =
        (url.openConnection() as HttpURLConnection).apply { setRequestProperty("User-Agent", userAgent) }

    /**
     * The same for a URL checked by LocalName (relay, AI, web search): plain HTTP, only ever
     * to the local network, never goes through a proxy, which would read it in clear text.
     * HTTPS uses the system's proxy as usual.
     */
    fun openChecked(url: URL): HttpURLConnection =
        (if (url.protocol == "http") url.openConnection(Proxy.NO_PROXY) else url.openConnection()).let { it as HttpURLConnection }
            .apply { setRequestProperty("User-Agent", userAgent) }

    /** The same for OkHttp clients (WebDAV), in place of "okhttp/<version>". */
    val userAgentInterceptor = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
    }
}
