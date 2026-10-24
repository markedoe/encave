package app.encave.core

import java.net.HttpURLConnection
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

    /** The same for OkHttp clients (WebDAV), in place of "okhttp/<version>". */
    val userAgentInterceptor = Interceptor { chain ->
        chain.proceed(chain.request().newBuilder().header("User-Agent", userAgent).build())
    }
}
