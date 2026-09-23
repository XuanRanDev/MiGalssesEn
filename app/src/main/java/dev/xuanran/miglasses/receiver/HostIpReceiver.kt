package dev.xuanran.miglasses.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.xuanran.miglasses.core.HostIpConfig
import dev.xuanran.miglasses.core.P2PControlConfig

class HostIpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == P2PControlConfig.ACTION_NETWORK_TRACE) {
            saveMessage(context, P2PControlConfig.KEY_LAST_TRACE, intent)
            return
        }
        if (intent.action == P2PControlConfig.ACTION_RESULT) {
            saveMessage(context, P2PControlConfig.KEY_LAST_RESULT, intent)
            return
        }
        if (intent.action != HostIpConfig.ACTION_CAPTURE_HOST_IP) return
        val hostIp = intent.getStringExtra(HostIpConfig.KEY_HOST_IP)?.trim()
        if (hostIp.isNullOrEmpty() || hostIp.length > 255) return

        context.getSharedPreferences(HostIpConfig.PREFS, Context.MODE_PRIVATE).edit()
            .putString(HostIpConfig.KEY_HOST_IP, hostIp)
            .putString(
                HostIpConfig.KEY_SOURCE,
                intent.getStringExtra(HostIpConfig.KEY_SOURCE).orEmpty()
            )
            .putLong(
                HostIpConfig.KEY_CAPTURED_AT,
                intent.getLongExtra(HostIpConfig.KEY_CAPTURED_AT, System.currentTimeMillis())
            )
            .apply()
    }

    private fun saveMessage(context: Context, key: String, intent: Intent) {
        context.getSharedPreferences(HostIpConfig.PREFS, Context.MODE_PRIVATE).edit()
            .putString(key, intent.getStringExtra(P2PControlConfig.EXTRA_MESSAGE).orEmpty())
            .apply()
    }
}
