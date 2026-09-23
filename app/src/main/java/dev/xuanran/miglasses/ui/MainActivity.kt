package dev.xuanran.miglasses.ui

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.xuanran.miglasses.R
import dev.xuanran.miglasses.core.HostIpConfig
import dev.xuanran.miglasses.core.SavePathConfig
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

class MainActivity : AppCompatActivity(), XposedServiceHelper.OnServiceListener {
    private var service: XposedService? = null
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var save: Button
    private lateinit var hostIp: TextView
    private lateinit var refreshHostIp: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        input = findViewById(R.id.path_input)
        save = findViewById(R.id.save_button)
        hostIp = findViewById(R.id.host_ip)
        refreshHostIp = findViewById(R.id.refresh_host_ip)
        input.setText(SavePathConfig.DEFAULT_PATH)
        save.isEnabled = false
        save.setOnClickListener { savePath() }
        refreshHostIp.setOnClickListener { refreshHostIp() }
        refreshHostIp()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        this.service = service
        runOnUiThread {
            status.text = "已激活：${service.frameworkName} ${service.frameworkVersion}"
            val prefs = service.getRemotePreferences(SavePathConfig.GROUP)
            input.setText(prefs.getString(SavePathConfig.KEY_PATH, SavePathConfig.DEFAULT_PATH))
            save.isEnabled = true
            refreshHostIp()
        }
    }

    override fun onServiceDied(service: XposedService) {
        this.service = null
        runOnUiThread {
            status.setText(R.string.not_activated)
            save.isEnabled = false
        }
    }

    private fun savePath() {
        val normalized = SavePathConfig.normalize(input.text?.toString())
        if (normalized == null) {
            Toast.makeText(this, R.string.invalid_path, Toast.LENGTH_LONG).show()
            return
        }
        val prefs = service?.getRemotePreferences(SavePathConfig.GROUP) ?: run {
            Toast.makeText(this, R.string.not_activated, Toast.LENGTH_SHORT).show()
            return
        }
        prefs.edit().putString(SavePathConfig.KEY_PATH, normalized).apply()
        input.setText(normalized)
        Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
    }

    private fun refreshHostIp() {
        val prefs = getSharedPreferences(HostIpConfig.PREFS, MODE_PRIVATE)
        val ip = prefs.getString(HostIpConfig.KEY_HOST_IP, null)
        val source = prefs.getString(HostIpConfig.KEY_SOURCE, null)
        val capturedAt = prefs.getLong(HostIpConfig.KEY_CAPTURED_AT, 0L)
        hostIp.text = if (ip.isNullOrBlank()) {
            getString(R.string.host_ip_waiting)
        } else {
            getString(R.string.host_ip_value, ip, capturedAt, source.orEmpty())
        }
    }
}
