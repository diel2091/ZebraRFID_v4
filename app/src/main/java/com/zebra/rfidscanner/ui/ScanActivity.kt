package com.zebra.rfidscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.zebra.rfidscanner.databinding.ActivityScanBinding
import com.zebra.rfidscanner.rfid.RfidManager
import com.zebra.rfidscanner.utils.CsvExporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScanBinding
    private val viewModel: ScanViewModel by viewModels()
    private val adapter = EpcAdapter()
    private var isScanning = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        if (perms.values.all { it }) viewModel.initialize()
        else Toast.makeText(this, "Se requieren permisos Bluetooth", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupRecyclerView()
        setupButtons()
        observeState()
        checkPermissionsAndInit()
    }

    private fun setupRecyclerView() {
        binding.rvTags.apply {
            layoutManager = LinearLayoutManager(this@ScanActivity)
            adapter = this@ScanActivity.adapter
            itemAnimator = null
        }
    }

    private fun setupButtons() {
        binding.btnScan.setOnClickListener {
            isScanning = viewModel.toggleScan()
            binding.btnScan.text = if (isScanning) "DETENER" else "ESCANEAR"
            binding.btnScan.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (isScanning) android.graphics.Color.parseColor("#FF5252")
                else android.graphics.Color.parseColor("#00E5FF")
            )
        }
        binding.btnClear.setOnClickListener {
            viewModel.clearAll()
            isScanning = false
            binding.btnScan.text = "ESCANEAR"
        }
        binding.btnExport.setOnClickListener { exportCsv() }
        binding.btnRetry.setOnClickListener { viewModel.retry() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                val isError = state is RfidManager.ConnectionState.Error
                val isConnected = state is RfidManager.ConnectionState.Connected
                binding.btnRetry.isVisible = isError
                binding.btnScan.isEnabled = isConnected
                binding.tvStatus.text = when (state) {
                    is RfidManager.ConnectionState.Disconnected -> "⚫ Desconectado"
                    is RfidManager.ConnectionState.Connecting -> "🟡 Conectando..."
                    is RfidManager.ConnectionState.Connected -> "🟢 ${state.readerName}"
                    is RfidManager.ConnectionState.Error -> "🔴 ${state.message}"
                }
            }
        }
        lifecycleScope.launch {
            viewModel.tagCount.collect { count ->
                binding.tvCount.text = "Tags: $count"
            }
        }
        lifecycleScope.launch {
            viewModel.tags.collect { tags ->
                adapter.submitList(tags.take(300))
            }
        }
    }

    private fun checkPermissionsAndInit() {
        val needed = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) viewModel.initialize()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun exportCsv() {
        val tags = viewModel.getTagsForExport()
        if (tags.isEmpty()) {
            Toast.makeText(this, "No hay tags para exportar", Toast.LENGTH_SHORT).show()
            return
        }
        val path = CsvExporter.export(this, tags)
        if (path != null) Toast.makeText(this, "✓ Exportado: $path", Toast.LENGTH_LONG).show()
        else Toast.makeText(this, "Error al exportar", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) viewModel.toggleScan()
    }
}
