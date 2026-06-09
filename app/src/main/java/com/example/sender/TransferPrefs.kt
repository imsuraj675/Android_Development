package com.example.sender

import android.content.Context

data class TransferPrefs(
    val autoDownload: Boolean = true,
    val approvalThresholdBytes: Long = 52_428_800L,
    val downloadLocationUri: String? = null
)

class TransferPrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("transfer_prefs", Context.MODE_PRIVATE)

    fun load(): TransferPrefs = TransferPrefs(
        autoDownload           = prefs.getBoolean("autoDownload", true),
        approvalThresholdBytes = prefs.getLong("approvalThresholdBytes", 52_428_800L),
        downloadLocationUri    = prefs.getString("downloadLocationUri", null)
    )

    fun save(p: TransferPrefs) {
        prefs.edit()
            .putBoolean("autoDownload", p.autoDownload)
            .putLong("approvalThresholdBytes", p.approvalThresholdBytes)
            .putString("downloadLocationUri", p.downloadLocationUri)
            .apply()
    }
}
