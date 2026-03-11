package com.zebra.rfidscanner.rfid

import android.content.Context
import android.util.Log
import com.zebra.rfid.api3.*
import com.zebra.rfidscanner.data.RfidRepository
import dagger.hilt.android.qualifiers.ApplicationContext
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

    fun initialize() {
        _connectionState.value = ConnectionState.Connecting
        try {
            readers = Readers(context, ENUM_TRANSPORT.ALL)
            connectReader()
        } catch (e: Exception) {
            Log.e(TAG, "init error", e)
            _connectionState.value = ConnectionState.Error("Init error: ${e.message}")
        }
    }

    private fun connectReader() {
        try {
            val readerList = readers?.GetAvailableRFIDReaderList()
            Log.i(TAG, "Lectores disponibles: ${readerList?.size ?: 0}")
            readerList?.forEachIndexed { i, r -> Log.i(TAG, "Reader[$i]: ${r.name}") }

            if (readerList.isNullOrEmpty()) {
                _connectionState.value = ConnectionState.Error(
                    "No se encontró RFD4030.\nAsegúrese de que esté encendido y emparejado por Bluetooth."
                )
                return
            }

            val readerDevice = readerList[0]
            reader = readerDevice.rfidReader

            if (reader?.isConnected == true) {
                Log.i(TAG, "Ya conectado: ${readerDevice.name}")
                configureReader()
                _connectionState.value = ConnectionState.Connected(readerDevice.name ?: "RFD4030")
                return
            }

            Log.i(TAG, "Conectando a: ${readerDevice.name}")
            reader?.connect()
            configureReader()
            _connectionState.value = ConnectionState.Connected(readerDevice.name ?: "RFD4030")
            Log.i(TAG, "Conectado OK")

        } catch (e: InvalidUsageException) {
            Log.e(TAG, "InvalidUsage: ${e.message}", e)
            _connectionState.value = ConnectionState.Error("InvalidUsage: ${e.message}")
        } catch (e: OperationFailureException) {
            Log.e(TAG, "OperationFailure: ${e.results}", e)
            _connectionState.value = ConnectionState.Error("OperationFailure: ${e.results}")
        } catch (e: Exception) {
            Log.e(TAG, "connect error", e)
            _connectionState.value = ConnectionState.Error("Error: ${e.message}")
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
                if (!epc.isNullOrBlank()) {
                    repository.onEpcReceived(epc)
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "eventReadNotify error", ex)
        }
    }

    override fun eventStatusNotify(e: RfidStatusEvents?) {
        val type = e?.StatusEventData?.statusEventType
        Log.d(TAG, "Status event: $type")
        when (type) {
            STATUS_EVENT_TYPE.DISCONNECTION_EVENT -> {
                Log.w(TAG, "Reader desconectado")
                _connectionState.value = ConnectionState.Disconnected
                reader = null
            }
            else -> Log.d(TAG, "Status ignorado: $type")
        }
    }

    fun startInventory(): Boolean = try {
        reader?.Actions?.Inventory?.perform()
        Log.i(TAG, "Inventory started")
        true
    } catch (e: Exception) {
        Log.e(TAG, "startInventory error", e)
        false
    }

    fun stopInventory(): Boolean = try {
        reader?.Actions?.Inventory?.stop()
        true
    } catch (e: Exception) {
        Log.e(TAG, "stopInventory error", e)
        false
    }

    fun isConnected(): Boolean = reader?.isConnected == true

    fun release() {
        try {
            reader?.Events?.removeEventsListener(this)
            reader?.disconnect()
        } catch (e: Exception) {
            Log.e(TAG, "release error", e)
        } finally {
            reader = null
            readers?.Dispose()
            readers = null
            _connectionState.value = ConnectionState.Disconnected
        }
    }
}
