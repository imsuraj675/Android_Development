package com.example.sender

/**
 * Provides current version info and a stub hook for future update checking.
 *
 * versionCode and versionName are read from BuildConfig, which is generated
 * from the values in app/build.gradle.kts — bump them there for each release.
 */
object UpdateManager {
    val currentVersionCode: Int
        get() = BuildConfig.VERSION_CODE

    val currentVersionName: String
        get() = BuildConfig.VERSION_NAME

    /**
     * Stub for server-based update checking. Replace the body with real
     * network logic when a distribution channel is set up.
     *
     * @param onResult  called with (hasUpdate=false, latestVersion=null) until implemented
     */
    fun checkForUpdates(onResult: (hasUpdate: Boolean, latestVersion: String?) -> Unit) {
        onResult(false, null)
    }
}
