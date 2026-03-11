package com.zebra.rfidscanner.rfid

import android.content.Context
import android.util.Log
import com.zebra.rfid.api3.*
import com.zebra.rfidscanner.data.RfidRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RfidManager"

@Singleton
class RfidManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RfidRepository
) : RfidEventsListener {

    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val readerName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var readers: Readers? = null
    private var reader: RFIDReader? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initialize() {
        scope.launch { tryConnect() }
    }

    private suspend fun tryConnect(attempt: Int = 1) {
        Log.i(TAG, "Intento $attempt de conexión")
        _connectionState.value = ConnectionState.Connecting

        try {
            try { readers?.Dispose() } catch (_: Exception) {}
            readers = null
            reader = null

            delay(500)

            readers = Readers(context, ENUM_TRANSPORT.ALL)

            val readerList = readers?.GetAvailableRFIDReaderList()
            Log.i(TAG, "Lectores encontrados: ${readerList?.size ?: 0}")
            readerList?.forEachIndexed { i, r ->
                Log.i(TAG, "  Reader[$i]: name=${r.name}")
            }

            if (readerList.isNullOrEmpty()) {
                if (attempt < 5) {
                    _connectionState.value = ConnectionState.Error(
                        "Buscando RFD4030... (intento $attempt/5)"
                    )
                    delay(3000)
                    tryConnect(attempt + 1)
                } else {
                    _connectionState.value = ConnectionState.Error(
                        "No se encontró el RFD4030.\n• Verifique que esté encendido\n• Presione Reintentar"
                    )
                }
                return
            }

            val readerDevice = readerList[0]
            reader = readerDevice.rfidReader

            if (reader == null) {
                _connectionState.value = ConnectionState.Error("reader null — reinicie el RFD4030")
                return
            }

            if (reader?.isConnected == true) {
                configureReader()
                _connectionState.value = ConnectionState.Connected(readerDevice.name ?: "RFD4030")
                return
            }

            Log.i(TAG, "Conectando a: ${readerDevice.name}")
            reader?.connect()
            configureReader()
            _connectionState.value = ConnectionState.Connected(readerDevice.name ?: "RFD4030")
            Log.i(TAG, "¡Conectado!")

        } catch (e: InvalidUsageException) {
            Log.e(TAG, "InvalidUsage $attempt: ${e.message}", e)
            retryOrFail(attempt, "InvalidUsage: ${e.message}")
        } catch (e: OperationFailureException) {
            Log.e(TAG, "OperationFailure $attempt: ${e.results}", e)
            retryOrFail(attempt, "Fallo: ${e.results}")
        } catch (e: Exception) {
            Log.e(TAG, "Error $attempt: ${e.message}", e)
            retryOrFail(attempt, "Error: ${e.message}")
        }
    }

    private suspend fun retryOrFail(attempt: Int, msg: String) {
        if (attempt < 3) {
            delay(2000)
            tryConnect(attempt + 1)
        } else {
            _connectionState.value = ConnectionState.Error(msg)
        }
    }

    private fun configureReader() {
        try {
            reader?.Events?.addEventsListener(this)
            reader?.Events?.setTagReadEvent(true)
            reader?.Events?.setReaderDisconnectEvent(true)
            reader?.Events?.setAttachTagDataWithReadEvent(false)

            val triggerInfo = TriggerInfo()
            triggerInfo.StartTrigger.triggerType = START_TRIGGER_TYPE.START_TRIGGER_TYPE_IMMEDIATE
            triggerInfo.StopTrigger.triggerType = STOP_TRIGGER_TYPE.STOP_TRIGGER_TYPE_IMMEDIATE
            reader?.Config?.startTrigger = triggerInfo.StartTrigger
            reader?.Config?.stopTrigger = triggerInfo.StopTrigger

            Log.i(TAG, "Reader configurado OK")
        } catch (e: Exception) {
            Log.e(TAG, "configureReader error", e)
        }
    }

    override fun eventReadNotify(e: RfidReadEvents?) {
        try {
            val tags = reader?.Actions?.getReadTags(100)
            tags?.forEach { tag ->
                val epc = tag?.tagID
                if (!epc.isNullOrBlank()) repository.onEpcReceived(epc)
            }
        } catch (ex: Exception) {
            Log.e(TAG, "eventReadNotify error", ex)
        }
    }

    override fun eventStatusNotify(e: RfidStatusEvents?) {
        val type = e?.StatusEventData?.statusEventType
        Log.d(TAG, "Status: $type")
        when (type) {
            STATUS_EVENT_TYPE.DISCONNECTION_EVENT -> {
                _connectionState.value = ConnectionState.Disconnected
                reader = null
                scope.launch { delay(3000); tryConnect() }
            }
            else -> {}
        }
    }

    fun retry() { scope.launch { tryConnect() } }

    fun startInventory(): Boolean = try {
        reader?.Actions?.Inventory?.perform(); true
    } catch (e: Exception) { Log.e(TAG, "startInventory error", e); false }

    fun stopInventory(): Boolean = try {
        reader?.Actions?.Inventory?.stop(); true
    } catch (e: Exception) { Log.e(TAG, "stopInventory error", e); false }

    fun isConnected(): Boolean = reader?.isConnected == true

    fun release() {
        scope.cancel()
        try {
            reader?.Events?.removeEventsListener(this)
            reader?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "release error", e)
        } finally {
            reader = null
            try { readers?.Dispose() } catch (_: Exception) {}
            readers = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }
}
