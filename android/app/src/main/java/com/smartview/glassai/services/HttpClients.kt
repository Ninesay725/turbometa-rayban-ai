package com.smartview.glassai.services

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * Process-wide OkHttp clients (iOS 2.0 stability fix 4.1 equivalent). One client per purpose:
 * each OkHttpClient owns a Dispatcher, a ConnectionPool and threads, so creating one per service
 * instance leaked idle resources for 60 s after every Live AI session.
 */
object HttpClients {
    /** Cloud WebSockets (DashScope Omni, Gemini Live, Fun-ASR): keepalive pings, no read timeout. */
    val websocket: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .build()
    }
}
