package com.example.sender

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TrustedDevice(
    val id: String,
    var alias: String,
    val platform: String,
    var lastKnownIp: String,
    var lastSeen: Long,
    var isBlocked: Boolean = false
)

class DeviceManager(context: Context) {
    private val prefs = context.getSharedPreferences("trusted_devices", Context.MODE_PRIVATE)

    private fun load(): MutableMap<String, TrustedDevice> {
        val map = mutableMapOf<String, TrustedDevice>()
        val arr = try { JSONArray(prefs.getString("devices", "[]") ?: "[]") } catch (_: Exception) { return map }
        for (i in 0 until arr.length()) {
            try {
                val o = arr.getJSONObject(i)
                val d = TrustedDevice(
                    id          = o.getString("id"),
                    alias       = o.getString("alias"),
                    platform    = o.optString("platform", "Unknown"),
                    lastKnownIp = o.optString("lastKnownIp", ""),
                    lastSeen    = o.optLong("lastSeen", 0L),
                    isBlocked   = o.optBoolean("isBlocked", false)
                )
                map[d.id] = d
            } catch (_: Exception) {}
        }
        return map
    }

    private fun persist(map: Map<String, TrustedDevice>) {
        val arr = JSONArray()
        map.values.forEach { d ->
            arr.put(JSONObject().apply {
                put("id", d.id)
                put("alias", d.alias)
                put("platform", d.platform)
                put("lastKnownIp", d.lastKnownIp)
                put("lastSeen", d.lastSeen)
                put("isBlocked", d.isBlocked)
            })
        }
        prefs.edit().putString("devices", arr.toString()).apply()
    }

    fun getAll(): List<TrustedDevice> =
        load().values.sortedByDescending { it.lastSeen }

    fun get(id: String): TrustedDevice? = load()[id]

    fun isKnown(id: String): Boolean = load().containsKey(id)

    fun isTrusted(id: String): Boolean = load()[id]?.let { !it.isBlocked } ?: false

    fun isBlocked(id: String): Boolean = load()[id]?.isBlocked == true

    fun trust(device: TrustedDevice) {
        val map = load()
        map[device.id] = device
        persist(map)
    }

    fun forget(id: String) {
        val map = load()
        map.remove(id)
        persist(map)
    }

    fun block(id: String) {
        val map = load()
        map[id]?.isBlocked = true
        persist(map)
    }

    fun unblock(id: String) {
        val map = load()
        map[id]?.isBlocked = false
        persist(map)
    }

    fun rename(id: String, alias: String) {
        val map = load()
        map[id]?.alias = alias
        persist(map)
    }

    fun isTrustExpired(id: String, trustDays: Long): Boolean {
        if (trustDays <= 0L) return false
        val d = load()[id] ?: return false
        if (d.lastSeen <= 0L) return false
        return System.currentTimeMillis() > d.lastSeen + trustDays * 86_400_000L
    }

    fun updateLastSeen(id: String, ip: String) {
        val map = load()
        map[id]?.let {
            it.lastKnownIp = ip
            it.lastSeen = System.currentTimeMillis()
        }
        persist(map)
    }
}
