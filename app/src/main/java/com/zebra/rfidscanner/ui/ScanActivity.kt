package com.zebra.rfidscanner.ui
 
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
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
    private var searchQuery = ""
 
    companion object {
        // DataWedge Intent Output — configurar en DataWedge con este action
        const val DW_ACTION   = "com.zebra.rfidscanner.SCAN"
        const val DW_DATA_KEY = "com.symbol.datawedge.data_string"
    }
 
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
        setupSearch()
        observeState()
        checkPermissionsAndInit()
        handleDataWedgeIntent(intent)
    }
 
    // Recibe barcode cuando la app ya está abierta (requiere singleTop en Manifest)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDataWedgeIntent(intent)
    }
 
    private fun handleDataWedgeIntent(intent: Intent?) {
        val barcode = intent?.getStringExtra(DW_DATA_KEY) ?: return
        if (barcode.isBlank()) return
        val clean = barcode.trim()
        binding.etSearch.setText(clean)
        binding.etSearch.setSelection(clean.length)
    }
 
    // FIX GATILLO FÍSICO: interceptar teclas de hardware antes de que lleguen al EditText
    // El gatillo del RFD4030 envía KEYCODE_BUTTON_L1 o similar — lo ignoramos aquí
    // para que el SDK RFID lo maneje directamente via eventStatusNotify
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        // Teclas de scanner/gatillo de Zebra — dejar pasar al SDK, no al EditText
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1 ||
            keyCode == KeyEvent.KEYCODE_BUTTON_R1 ||
            keyCode == KeyEvent.KEYCODE_F1 ||
            keyCode == KeyEvent.KEYCODE_F12 ||
            keyCode == 280 || // KEYCODE_BARCODE_SCAN en algunos Zebra
            keyCode == 293    // Tecla de scan en TC-series
        ) {
            // No consumir — dejar que el SDK RFID lo maneje
            return false
        }
        return super.dispatchKeyEvent(event)
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
            binding.btnScan.text = if (isScanning) "Detener" else "Escanear"
            binding.btnScan.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (isScanning) android.graphics.Color.parseColor("#FF5252")
                else android.graphics.Color.parseColor("#00E5FF")
            )
            // Quitar foco del search para que el gatillo físico funcione
            binding.etSearch.clearFocus()
        }
 
        binding.btnClear.setOnClickListener {
            viewModel.clearAll()
            isScanning = false
            binding.btnScan.text = "Escanear"
            binding.etSearch.setText("")
            binding.etSearch.clearFocus()
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
 
        binding.btnRestart.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Reiniciar app")
                .setMessage("¿Desea reiniciar la aplicación? Esto reconectará el lector RFID.")
                .setPositiveButton("Reiniciar") { _, _ -> restartApp() }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }
 
    private fun restartApp() {
        // Primero lanzar la nueva instancia, luego liberar y matar
        val intent = packageManager.getLaunchIntentForPackage(packageName)!!
        intent.addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        startActivity(intent)
        // Dar tiempo a que el Intent se registre antes de matar el proceso
        Handler(Looper.getMainLooper()).postDelayed({
            viewModel.release()
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 500)
    }
 
    private fun setupSearch() {
        // Quitar foco automático al iniciar — el gatillo físico RFID necesita que
        // el EditText NO tenga foco para funcionar correctamente
        binding.etSearch.clearFocus()
        binding.rvTags.requestFocus()
 
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString()?.trim() ?: ""
                refreshList()
            }
        })
 
        // Al terminar de escribir en el buscador, quitar foco para liberar el gatillo
        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            binding.etSearch.clearFocus()
            binding.rvTags.requestFocus()
            true
        }
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
        val query = searchQuery.uppercase()
 
        val rows = if (eanMode) {
            viewModel.eanResults.value
                .filter { r ->
                    query.isEmpty() ||
                    r.epc.contains(query, ignoreCase = true) ||
                    r.ean13.contains(query, ignoreCase = true) ||
                    r.gtin14.contains(query, ignoreCase = true)
                }
                .take(300)
                .map { EpcAdapter.Row.EanRow(it, 1) }
        } else {
            viewModel.tags.value
                .filter { t ->
                    query.isEmpty() || t.epc.contains(query, ignoreCase = true)
                }
                .take(300)
                .map { EpcAdapter.Row.EpcRow(it.epc, it.readCount) }
        }
 
        adapter.submitList(rows)
        binding.tvSearchCount.text = if (query.isNotEmpty()) "${rows.size}" else ""
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
