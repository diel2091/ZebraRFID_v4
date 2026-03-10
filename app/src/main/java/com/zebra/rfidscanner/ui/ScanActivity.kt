package com.zebra.rfidscanner.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            viewModel.initialize()
        } else {
            Toast.makeText(this, "Permisos Bluetooth requeridos", Toast.LENGTH_LONG).show()
        }
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
        binding.btnScan.setOnClickListener { viewModel.toggleScan() }
        binding.btnClear.setOnClickListener { viewModel.clearAll() }
        binding.btnExport.setOnClickListener { exportCsv() }
    }

    private fun observeState() {
        lifecycleScope.launch {
            viewModel.connectionState.collect { state ->
                binding.tvStatus.text = when (state) {
                    is RfidManager.ConnectionState.Disconnected -> "Desconectado"
                    is RfidManager.ConnectionState.Connecting -> "Conectando..."
                    is RfidManager.ConnectionState.Connected -> "✓ ${state.readerName}"
                    is RfidManager.ConnectionState.Error -> "Error: ${state.message}"
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
        val needed = mutableListOf<String>()
        val perms = listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
        perms.forEach { p ->
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p)
            }
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
        if (path != null) {
            Toast.makeText(this, "Exportado: $path", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Error al exportar", Toast.LENGTH_SHORT).show()
        }
    }
}
