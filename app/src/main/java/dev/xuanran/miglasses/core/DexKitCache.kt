package dev.xuanran.miglasses.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.luckypray.dexkit.DexKitCacheBridge

@OptIn(org.luckypray.dexkit.annotations.DexKitExperimentalApi::class)
class DexKitCache private constructor(
    private val prefs: SharedPreferences
) : DexKitCacheBridge.Cache {

    override fun getString(key: String, default: String?): String? =
        prefs.getString(key, default)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getStringList(key: String, default: List<String>?): List<String>? {
        val raw = prefs.getString(key, null) ?: return default
        return runCatching {
            val json = JSONArray(raw)
            List(json.length()) { index -> json.getString(index) }
        }.getOrDefault(default)
    }

    override fun putStringList(key: String, value: List<String>) {
        prefs.edit().putString(key, JSONArray(value).toString()).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun getAllKeys(): Collection<String> = prefs.all.keys

    override fun clearAll() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS = "mi_glasses_dexkit_cache"
        private const val KEY_GENERATION = "__generation"

        fun create(context: Context, queryVersion: Int): DexKitCache {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            val generation = "${packageInfo.longVersionCode}:${packageInfo.lastUpdateTime}:$queryVersion"
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(KEY_GENERATION, null) != generation) {
                prefs.edit().clear().putString(KEY_GENERATION, generation).commit()
            }
            return DexKitCache(prefs)
        }
    }
}
