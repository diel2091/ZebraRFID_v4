package com.zebra.rfidscanner.rfid

import android.content.Context
import android.util.Log
import com.zebra.rfidscanner.data.RfidRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.reflect.Proxy
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RfidManager"

@Singleton
class RfidManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: RfidRepository
) {
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val readerName: String) : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private var emdkManager: Any? = null
    private var rfidReader: Any? = null

    fun initialize() {
        _connectionState.value = ConnectionState.Connecting
        try {
            val emdkManagerClass = Class.forName("com.symbol.emdk.EMDKManager")
            val listenerClass = Class.forName("com.symbol.emdk.EMDKManager\$EMDKListener")

            val proxy = Proxy.newProxyInstance(
                listenerClass.classLoader, arrayOf(listenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onOpened" -> onOpened(args?.get(0))
                    "onClosed" -> onClosed()
                }
                null
            }

            val getEMDKManager = emdkManagerClass.getMethod(
                "getEMDKManager", Context::class.java, listenerClass
            )
            getEMDKManager.invoke(null, context, proxy)
            Log.i(TAG, "EMDK init requested")

        } catch (e: ClassNotFoundException) {
            Log.e(TAG, "EMDK not found", e)
            _connectionState.value = ConnectionState.Error("EMDK no disponible")
        } catch (e: Exception) {
            Log.e(TAG, "EMDK init error", e)
            _connectionState.value = ConnectionState.Error("Error: ${e.message}")
        }
    }

    private fun onOpened(manager: Any?) {
        emdkManager = manager
        Log.i(TAG, "EMDK opened")
        connectReader()
    }

    private fun onClosed() {
        Log.w(TAG, "EMDK closed")
        rfidReader = null
        emdkManager = null
        _connectionState.value = ConnectionState.Disconnected
    }

    private fun connectReader() {
        try {
            val featureTypeClass = Class.forName("com.symbol.emdk.EMDKManager\$FEATURE_TYPE")
            val rfidFeature = featureTypeClass.enumConstants?.firstOrNull {
                (it as Enum<*>).name == "RFID"
            }
            val getInstance = emdkManager!!.javaClass.getMethod("getInstance", featureTypeClass)
            val rfidMgr = getInstance.invoke(emdkManager, rfidFeature)

            val readerList = rfidMgr?.javaClass
                ?.getMethod("getSupportedRFIDReaderList")
                ?.invoke(rfidMgr) as? List<*>

            if (readerList.isNullOrEmpty()) {
                _connectionState.value = ConnectionState.Error("No hay lectores RFID disponibles")
                return
            }

            val readerInfo = readerList[0]!!
            val readerId = readerInfo.javaClass.getMethod("getReaderID").invoke(readerInfo)
            val readerName = readerInfo.javaClass.getMethod("getName").invoke(readerInfo) as? String ?: "RFD40"

            val getRFIDReader = rfidMgr!!.javaClass.getMethod("getRFIDReader", readerId!!.javaClass)
            rfidReader = getRFIDReader.invoke(rfidMgr, readerId)

            rfidReader!!.javaClass.getMethod("connect").invoke(rfidReader)
            setupEventListener()
            _connectionState.value = ConnectionState.Connected(readerName)
            Log.i(TAG, "Connected: $readerName")

        } catch (e: Exception) {
            Log.e(TAG, "Connect error", e)
            _connectionState.value = ConnectionState.Error("Error conexión: ${e.message}")
        }
    }

    private fun setupEventListener() {
        try {
            val listenerClass = Class.forName("com.symbol.emdk.rfid.RfidEventsListener")
            val eventsObj = rfidReader?.javaClass?.getMethod("getEvents")?.invoke(rfidReader)

            val proxy = Proxy.newProxyInstance(
                listenerClass.classLoader, arrayOf(listenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "eventReadNotify" -> handleTagRead(args?.get(0))
                    "eventStatusNotify" -> handleStatus(args?.get(0))
                }
                null
            }

            eventsObj?.javaClass?.getMethod("addEventsListener", listenerClass)?.invoke(eventsObj, proxy)
            eventsObj?.javaClass?.getMethod("setTagReadEvent", Boolean::class.java)?.invoke(eventsObj, true)
            eventsObj?.javaClass?.getMethod("setReaderDisconnectEvent", Boolean::class.java)?.invoke(eventsObj, true)

        } catch (e: Exception) {
            Log.e(TAG, "Listener setup error", e)
        }
    }

    private fun handleTagRead(event: Any?) {
        try {
            val data = event?.javaClass?.getMethod("getReadEventData")?.invoke(event)
            val tags = data?.javaClass?.getMethod("getTagData")?.invoke(data) as? Array<*>
            tags?.forEach { tag ->
                val epc = tag?.javaClass?.getMethod("getTagID")?.invoke(tag) as? String
                if (!epc.isNullOrBlank()) repository.onEpcReceived(epc)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tag read error", e)
        }
    }

    private fun handleStatus(event: Any?) {
        try {
            val statusData = event?.javaClass?.getMethod("getStatusEventData")?.invoke(event)
            val type = statusData?.javaClass?.getMethod("getStatusEventType")?.invoke(statusData)
            if (type?.toString() == "DISCONNECTION_EVENT") {
                _connectionState.value = ConnectionState.Disconnected
                rfidReader = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Status error", e)
        }
    }

    fun startInventory(): Boolean = try {
        val actions = rfidReader?.javaClass?.getMethod("getActions")?.invoke(rfidReader)
        val inv = actions?.javaClass?.getMethod("getInventory")?.invoke(actions)
        inv?.javaClass?.getMethod("perform")?.invoke(inv)
        true
    } catch (e: Exception) { false }

    fun stopInventory(): Boolean = try {
        val actions = rfidReader?.javaClass?.getMethod("getActions")?.invoke(rfidReader)
        val inv = actions?.javaClass?.getMethod("getInventory")?.invoke(actions)
        inv?.javaClass?.getMethod("stop")?.invoke(inv)
        true
    } catch (e: Exception) { false }

    fun isConnected(): Boolean = try {
        rfidReader?.javaClass?.getMethod("isConnected")?.invoke(rfidReader) as? Boolean ?: false
    } catch (e: Exception) { false }

    fun release() {
        try { rfidReader?.javaClass?.getMethod("disconnect")?.invoke(rfidReader) } catch (e: Exception) { }
        rfidReader = null
        emdkManager = null
        _connectionState.value = ConnectionState.Disconnected
    }
}
