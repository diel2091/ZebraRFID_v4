package com.zebra.rfidscanner.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
    private var eanMode = false

    // SAF launcher for choosing export location
    private var pendingCsvContent: String = ""
    private var pendingCsvName: String = ""
    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(pendingCsvContent.toByteArray())
                }
                Toast.makeText(this, "✓ Exportado", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

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

        binding.btnEan.setOnClickListener {
            eanMode = !eanMode
            binding.btnEan.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (eanMode) android.graphics.Color.parseColor("#FF9800")
                else android.graphics.Color.parseColor("#69F0AE")
            )
            binding.btnEan.text = if (eanMode) "EAN ✓" else "EAN"
            refreshList()
        }

        binding.btnExport.setOnClickListener { showExportOptions() }
        binding.btnRetry.setOnClickListener { viewModel.retry() }
    }

    private fun showExportOptions() {
        val tags = viewModel.getTagsForExport()
        if (tags.isEmpty()) {
            Toast.makeText(this, "No hay tags para exportar", Toast.LENGTH_SHORT).show()
            return
        }
        val ts = CsvExporter.timestamp()
        if (eanMode) {
            pendingCsvContent = CsvExporter.buildEanCsv(tags)
            pendingCsvName = "rfid_ean_$ts.csv"
        } else {
            pendingCsvContent = CsvExporter.buildEpcCsv(tags)
            pendingCsvName = "rfid_epc_$ts.csv"
        }
        saveLauncher.launch(pendingCsvName)
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
                    is RfidManager.ConnectionState.Connecting   -> "🟡 Conectando..."
                    is RfidManager.ConnectionState.Connected    -> "🟢 ${state.readerName}"
                    is RfidManager.ConnectionState.Error        -> "🔴 ${state.message}"
                }
            }
        }
        lifecycleScope.launch {
            viewModel.tagCount.collect { binding.tvUnique.text = "Únicos: $it" }
        }
        lifecycleScope.launch {
            viewModel.totalReads.collect { binding.tvTotal.text = "Total: $it" }
        }
        lifecycleScope.launch {
            viewModel.readRate.collect {
                binding.tvRate.text = "Rate: ${"%.1f".format(it)}/s"
            }
        }
        lifecycleScope.launch {
            viewModel.tags.collect { refreshList() }
        }
        lifecycleScope.launch {
            viewModel.eanResults.collect { if (eanMode) refreshList() }
        }
    }

    private fun refreshList() {
        val rows = if (eanMode) {
            viewModel.eanResults.value.take(300).map {
                EpcAdapter.Row.EanRow(it, 1)
            }
        } else {
            viewModel.tags.value.take(300).map {
                EpcAdapter.Row.EpcRow(it.epc, it.readCount)
            }
        }
        adapter.submitList(rows)
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

    override fun onDestroy() {
        super.onDestroy()
        if (isScanning) viewModel.toggleScan()
    }
}
