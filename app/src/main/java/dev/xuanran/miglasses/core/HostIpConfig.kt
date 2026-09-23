package dev.xuanran.miglasses.core

object HostIpConfig {
    const val MODULE_PACKAGE = "dev.xuanran.miglasses"
    const val PREFS = "captured_host"
    const val ACTION_CAPTURE_HOST_IP = "dev.xuanran.miglasses.action.CAPTURE_HOST_IP"
    const val RECEIVER_CLASS = "dev.xuanran.miglasses.receiver.HostIpReceiver"
    const val KEY_HOST_IP = "last_glasses_host_ip"
    const val KEY_SOURCE = "last_glasses_host_ip_source"
    const val KEY_CAPTURED_AT = "last_glasses_host_ip_captured_at"
}
