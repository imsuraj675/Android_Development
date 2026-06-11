package com.example.sender

import android.app.Application
import android.net.wifi.WifiManager

class SenderApp : Application() {

    val server by lazy { KtorServer(this) }
    private var multicastLock: WifiManager.MulticastLock? = null
    private val statePrefs by lazy { getSharedPreferences("app_state", MODE_PRIVATE) }

    // Persists whether the server was running so the app restores its last state on launch
    var wasServerRunning: Boolean
        get() = statePrefs.getBoolean("server_running", true)
        set(value) = statePrefs.edit().putBoolean("server_running", value).apply()

    fun startServer() {
        if (server.isRunning.value) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wm.createMulticastLock("sender_mdns").also { it.acquire() }
        server.start()
        wasServerRunning = true
    }

    fun stopServer() {
        if (!server.isRunning.value) return
        server.stop()
        multicastLock?.release()
        multicastLock = null
        wasServerRunning = false
    }
}
