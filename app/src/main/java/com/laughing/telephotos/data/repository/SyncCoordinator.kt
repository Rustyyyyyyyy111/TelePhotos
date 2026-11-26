package com.laughing.telephotos.data.repository

import com.laughing.telephotos.data.local.AlbumSyncStatus
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TopicSyncKey(
  val chatId: Long,
  val messageThreadId: Long,
)

data class TopicSyncUiState(
  val status: AlbumSyncStatus = AlbumSyncStatus.IDLE,
  val lastError: String? = null,
  val lastUpdatedEpochMs: Long = 0L,
)

/**
 * Serializes history sync and manual backfill per (chatId, messageThreadId) topic so concurrent
 * refreshes, backfills, or UploadWorker pre-sync calls never race on checkpoints or Room writes,
 * while allowing unrelated topics to proceed independently.
 */
class SyncCoordinator {

  private val mutexes = ConcurrentHashMap<TopicSyncKey, Mutex>()
  private val _topicStates = MutableStateFlow<Map<TopicSyncKey, TopicSyncUiState>>(emptyMap())
  val topicStates: StateFlow<Map<TopicSyncKey, TopicSyncUiState>> = _topicStates.asStateFlow()

  private fun mutexFor(key: TopicSyncKey): Mutex =
    mutexes.computeIfAbsent(key) { Mutex() }

  fun currentState(key: TopicSyncKey): TopicSyncUiState =
    _topicStates.value[key] ?: TopicSyncUiState()

  suspend fun <T> runSerialized(
    key: TopicSyncKey,
    mode: AlbumSyncStatus,
    albumId: Long? = null,
    onPersistStatus: suspend (Long, AlbumSyncStatus, String?) -> Unit = { _, _, _ -> },
    block: suspend () -> T,
  ): T {
    val mutex = mutexFor(key)
    return mutex.withLock {
      setTopicState(key, TopicSyncUiState(status = mode, lastError = null, lastUpdatedEpochMs = System.currentTimeMillis()))
      if (albumId != null) {
        runCatching { onPersistStatus(albumId, mode, null) }
      }
      try {
        val result = block()
        setTopicState(
          key,
          TopicSyncUiState(
            status = AlbumSyncStatus.IDLE,
            lastError = null,
            lastUpdatedEpochMs = System.currentTimeMillis(),
          ),
        )
        if (albumId != null) {
          runCatching { onPersistStatus(albumId, AlbumSyncStatus.IDLE, null) }
        }
        result
      } catch (ce: CancellationException) {
        withContext(NonCancellable) {
          setTopicState(
            key,
            TopicSyncUiState(
              status = AlbumSyncStatus.IDLE,
              lastError = null,
              lastUpdatedEpochMs = System.currentTimeMillis(),
            ),
          )
          if (albumId != null) {
            runCatching { onPersistStatus(albumId, AlbumSyncStatus.IDLE, null) }
          }
        }
        throw ce
      } catch (t: Throwable) {
        val errMsg = t.message?.takeIf { it.isNotBlank() } ?: "Không thể đồng bộ chủ đề"
        withContext(NonCancellable) {
          setTopicState(
            key,
            TopicSyncUiState(
              status = AlbumSyncStatus.ERROR,
              lastError = errMsg,
              lastUpdatedEpochMs = System.currentTimeMillis(),
            ),
          )
          if (albumId != null) {
            runCatching { onPersistStatus(albumId, AlbumSyncStatus.ERROR, errMsg) }
          }
        }
        throw t
      }
    }
  }

  fun clearAllInMemoryStates() {
    _topicStates.value = emptyMap()
  }

  private fun setTopicState(key: TopicSyncKey, state: TopicSyncUiState) {
    _topicStates.update { current ->
      current + (key to state)
    }
  }
}
