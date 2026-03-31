package com.zebra.rfidscanner.ui
 
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.widget.EditText
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
import com.zebra.rfidscanner.utils.SmbExporter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
 
@AndroidEntryPoint
class ScanActivity : AppCompatActivity() {
 
    private lateinit var binding: ActivityScanBinding
    private val viewModel: ScanViewModel by viewModels()
    private val adapter = EpcAdapter()
    private var isScanning = false
    private var eanMode = false
    private var searchQuery = ""
 
    private var pendingCsvContent: String = ""
    private var pendingCsvName: String = ""
 
    // FIX PROBLEMA 2: Receptor para saber cuando la pantalla se enciende/apaga
    // Cuando la pantalla se enciende, DataWedge necesita que la Activity tome foco
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_ON) {
                // Re-registrar DataWedge cuando la pantalla vuelve
                Handler(Looper.getMainLooper()).postDelayed({
                    binding.rvTags.requestFocus()
                    notifyDataWedge()
                }, 300)
            }
        }
    }
 
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
 
        // Registrar receptor de pantalla
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))
    }
 
    override fun onResume() {
        super.onResume()
        // FIX PROBLEMA 2: Notificar a DataWedge cada vez que la Activity vuelve al frente
        // Esto resuelve el retraso de 3-5 min al abrir la app
        Handler(Looper.getMainLooper()).postDelayed({
            notifyDataWedge()
        }, 500)
    }
 
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // La Activity tiene foco — activar DataWedge
            notifyDataWedge()
            binding.rvTags.requestFocus()
        }
    }
 
    // Envía un Intent a DataWedge para activar el perfil de esta app
    private fun notifyDataWedge() {
        try {
            val dwIntent = Intent()
            dwIntent.action = "com.symbol.datawedge.api.ACTION"
            dwIntent.putExtra("com.symbol.datawedge.api.RESUME_PLUGIN", "")
            sendBroadcast(dwIntent)
        } catch (_: Exception) {}
    }
 
    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        if (isScanning) viewModel.toggleScan()
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
            binding.etSearch.clearFocus()
            binding.rvTags.requestFocus()
        }
 
        binding.btnClear.setOnClickListener {
            viewModel.clearAll()
            isScanning = false
            binding.btnScan.text = "Escanear"
            binding.etSearch.setText("")
            binding.etSearch.clearFocus()
            binding.rvTags.requestFocus()
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
 
    private fun setupSearch() {
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
 
        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            binding.etSearch.clearFocus()
            binding.rvTags.requestFocus()
            notifyDataWedge()
            true
        }
    }
 
    // FIX REINICIO para Enterprise Home Screen (EHS)
    // EHS no permite killProcess — en su lugar se usa recreate() para reiniciar la Activity
    // y se reconecta el lector RFID limpiamente
    private fun restartApp() {
        viewModel.release()
        Handler(Looper.getMainLooper()).postDelayed({
            viewModel.initialize()
            recreate()
        }, 800)
    }
 
    // ── EXPORT ──────────────────────────────────────────────────────────────
 
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
 
        val options = arrayOf("📁 Guardar en PDT", "🌐 Exportar a carpeta de red")
        AlertDialog.Builder(this)
            .setTitle("Exportar CSV")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> saveLauncher.launch(pendingCsvName)
                    1 -> exportToNetwork()
                }
            }
            .show()
    }
 
    private fun exportToNetwork() {
        val config = SmbExporter.loadConfig(this)
        if (config == null) {
            showSmbConfigDialog { exportToNetworkWithConfig() }
        } else {
            exportToNetworkWithConfig()
        }
    }
 
    private fun exportToNetworkWithConfig() {
        val config = SmbExporter.loadConfig(this) ?: return
 
        val progress = AlertDialog.Builder(this)
            .setMessage("Subiendo a carpeta de red...")
            .setCancelable(false)
            .create()
        progress.show()
 
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                SmbExporter.upload(config, pendingCsvName, pendingCsvContent)
            }
            progress.dismiss()
            if (error == null) {
                Toast.makeText(
                    this@ScanActivity,
                    "✓ Exportado a \\\\${config.host}\\${config.share}",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                AlertDialog.Builder(this@ScanActivity)
                    .setTitle("Error de red")
                    .setMessage("No se pudo subir el archivo:\n$error")
                    .setPositiveButton("Reintentar") { _, _ -> exportToNetworkWithConfig() }
                    .setNegativeButton("Configurar") { _, _ ->
                        showSmbConfigDialog { exportToNetworkWithConfig() }
                    }
                    .setNeutralButton("Cancelar", null)
                    .show()
            }
        }
    }
 
    private fun showSmbConfigDialog(onSaved: () -> Unit) {
        val current = SmbExporter.loadConfig(this)
 
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
 
        fun field(hint: String, value: String = "", password: Boolean = false) =
            EditText(this).apply {
                this.hint = hint
                setText(value)
                if (password) inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                layout.addView(this)
            }
 
        val etHost   = field("IP del servidor (ej: 10.150.1.24)", current?.host ?: "10.150.1.24")
        val etShare  = field("Carpeta compartida (ej: refId)",    current?.share ?: "refId")
        val etDomain = field("Dominio",                            current?.domain ?: "newwearcorp")
        val etUser   = field("Usuario de dominio",                 current?.user ?: "")
        val etPass   = field("Contraseña",                         current?.password ?: "", password = true)
 
        AlertDialog.Builder(this)
            .setTitle("Configurar carpeta de red")
            .setView(layout)
            .setPositiveButton("Guardar") { _, _ ->
                val config = SmbExporter.SmbConfig(
                    host     = etHost.text.toString().trim(),
                    share    = etShare.text.toString().trim(),
                    domain   = etDomain.text.toString().trim(),
                    user     = etUser.text.toString().trim(),
                    password = etPass.text.toString()
                )
                SmbExporter.saveConfig(this, config)
                onSaved()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
 
    // ── OBSERVE / LIST ───────────────────────────────────────────────────────
 
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
}
