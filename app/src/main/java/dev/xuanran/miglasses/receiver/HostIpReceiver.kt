package dev.xuanran.miglasses.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.xuanran.miglasses.core.HostIpConfig

class HostIpReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
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
}
