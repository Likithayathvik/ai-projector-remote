package com.boat.cineagent

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.boat.cineagent.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val refreshHandler = Handler(Looper.getMainLooper())

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestRuntimePermissions()

        binding.btnToggleService.setOnClickListener {
            if (CineAgentService.isRunning) {
                CineAgentService.stop(this)
            } else {
                CineAgentService.start(this)
            }
            refreshHandler.postDelayed({ refreshStatus() }, 300)
        }

        binding.btnUsageAccess.setOnClickListener {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }

        if (!CineAgentService.isRunning) {
            CineAgentService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun refreshStatus() {
        // Re-asserted on every tick, not just once at launch: writing the setting correctly
        // doesn't guarantee the OS has actually bound the service yet (it can lag well behind
        // the write, especially soon after a reboot), so this keeps nudging for as long as the
        // app stays open instead of gambling on a single attempt at onCreate.
        AccessibilityServiceGuard.ensureEnabled(this)

        val running = CineAgentService.isRunning
        binding.statusText.text = if (running) getString(R.string.status_running) else getString(R.string.status_stopped)
        binding.statusText.setTextColor(
            getColor(if (running) R.color.cine_status_ok else R.color.cine_status_off)
        )
        binding.btnToggleService.text = getString(if (running) R.string.btn_stop else R.string.btn_start)

        binding.rowDeviceName.text = "${getString(R.string.label_device_name)}: ${CineAgentService.deviceName}"
        binding.rowIp.text = "${getString(R.string.label_ip_address)}: ${CineAgentService.ipAddress}"
        binding.rowSerial.text = "${getString(R.string.label_serial)}: ${CineAgentService.serialNumber}"
        binding.rowUdpPort.text = "${getString(R.string.label_udp_port)}: ${BuildConfig.UDP_DISCOVERY_PORT}"
        binding.rowTcpPort.text = "${getString(R.string.label_tcp_port)}: ${BuildConfig.TCP_COMMAND_PORT}"
        binding.rowClients.text = "${getString(R.string.label_clients)}: ${CineAgentService.connectedClientCount}"
    }
}
