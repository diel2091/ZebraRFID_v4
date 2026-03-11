package com.zebra.rfidscanner.data

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RfidRepository @Inject constructor(private val tagDao: TagDao) {
    private val tagMap = ConcurrentHashMap<String, Boolean>(8192)
    private val _tagCount = MutableStateFlow(0)
    val tagCount: StateFlow<Int> = _tagCount.asStateFlow()
    val allTags: Flow<List<TagEntry>> = tagDao.getAllTags()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun onEpcReceived(epc: String) {
        val isNew = tagMap.putIfAbsent(epc, true) == null
        _tagCount.value = tagMap.size
        scope.launch {
            try {
                if (isNew) tagDao.insert(TagEntry(epc = epc))
                else tagDao.incrementCount(epc)
            } catch (e: Exception) {
                Log.e("Repository", "DB error", e)
            }
        }
    }

    fun getTagList(): List<String> = tagMap.keys().toList()

    suspend fun clearAll() {
        tagMap.clear()
        _tagCount.value = 0
        tagDao.deleteAll()
    }
}
