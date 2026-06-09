package com.example.sender

import android.app.Application
import android.net.wifi.WifiManager

class SenderApp : Application() {

    val server by lazy { KtorServer(this) }
    private var multicastLock: WifiManager.MulticastLock? = null

    fun startServer() {
        if (server.isRunning.value) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("sender_mdns").also { it.acquire() }
        server.start()
    }

    fun stopServer() {
        if (!server.isRunning.value) return
        server.stop()
        multicastLock?.release()
        multicastLock = null
    }
}
