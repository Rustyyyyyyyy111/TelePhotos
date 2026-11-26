package com.laughing.telephotos.data.telegram

import kotlinx.coroutines.delay

data class HistoryRawMessage(
  val messageId: Long,
  val isCreationMarker: Boolean,
  val media: ScannedTelegramMedia?,
)

object HistoryPaginationEngine {

  suspend fun fetchIncrementalForward(
    sinceMessageId: Long,
    startFromMessageId: Long = 0L,
    maxPages: Int = 10,
    pageSize: Int = 30,
    pageDelayMs: Long = 250L,
    fetchPage: suspend (fromMessageId: Long, limit: Int) -> List<HistoryRawMessage>,
  ): HistoryScanPageResult {
    val safeMaxPages = maxPages.coerceIn(1, 15)
    val safePageSize = pageSize.coerceIn(10, 40)
    val safeDelayMs = pageDelayMs.coerceIn(0L, 1_500L)

    val collected = mutableListOf<ScannedTelegramMedia>()
    val seenMsgIds = HashSet<Long>()
    var cursor = startFromMessageId.coerceAtLeast(0L)
    var pageAttempts = 0
    var highestSeen = 0L
    var lowestRawSeen = 0L
    var hitKnownCheckpoint = false
    var sawCreationMarker = false
    var endSignal = HistoryEndSignal.NONE

    while (pageAttempts < safeMaxPages) {
      pageAttempts++
      val batch = fetchPage(cursor, safePageSize)
      if (batch.isEmpty()) {
        endSignal = if (sawCreationMarker) {
          HistoryEndSignal.CONFIRMED_CREATION_END
        } else {
          HistoryEndSignal.UNCERTAIN_EMPTY_OR_SHORT
        }
        break
      }

      var minBatchMsgId = Long.MAX_VALUE
      for (raw in batch) {
        if (raw.messageId > highestSeen) {
          highestSeen = raw.messageId
        }
        if (raw.messageId > 0L) {
          if (raw.messageId < minBatchMsgId) minBatchMsgId = raw.messageId
          if (lowestRawSeen == 0L || raw.messageId < lowestRawSeen) {
            lowestRawSeen = raw.messageId
          }
        }
        if (raw.isCreationMarker) {
          sawCreationMarker = true
        }
        if (sinceMessageId > 0L && raw.messageId in 1..sinceMessageId) {
          hitKnownCheckpoint = true
          break
        }
        if (startFromMessageId > 0L && raw.messageId >= startFromMessageId) {
          continue
        }
        if (raw.messageId > 0L && seenMsgIds.add(raw.messageId) && raw.media != null) {
          collected.add(raw.media)
        }
      }

      if (hitKnownCheckpoint) {
        endSignal = HistoryEndSignal.REACHED_KNOWN_CHECKPOINT
        break
      }

      val oldestInBatch = if (minBatchMsgId == Long.MAX_VALUE) 0L else minBatchMsgId
      if (sawCreationMarker && (oldestInBatch == 0L || (cursor > 0L && oldestInBatch >= cursor))) {
        endSignal = HistoryEndSignal.CONFIRMED_CREATION_END
        break
      }
      if (oldestInBatch <= 0L || (cursor > 0L && oldestInBatch >= cursor)) {
        endSignal = HistoryEndSignal.STALLED_CURSOR
        break
      }

      cursor = oldestInBatch
      if (pageAttempts < safeMaxPages && safeDelayMs > 0L) {
        delay(safeDelayMs)
      }
    }

    val reachedEnd = endSignal == HistoryEndSignal.CONFIRMED_CREATION_END
    return HistoryScanPageResult(
      items = collected,
      highestMessageId = highestSeen,
      lowestMessageId = lowestRawSeen,
      hitKnownCheckpoint = hitKnownCheckpoint,
      endSignal = endSignal,
      reachedHistoryEnd = reachedEnd,
    )
  }

  suspend fun fetchOlderBackfill(
    beforeMessageId: Long,
    maxPages: Int = 2,
    pageSize: Int = 25,
    pageDelayMs: Long = 350L,
    fetchPage: suspend (fromMessageId: Long, limit: Int) -> List<HistoryRawMessage>,
  ): HistoryScanPageResult {
    if (beforeMessageId <= 0L) {
      return fetchIncrementalForward(
        sinceMessageId = 0L,
        startFromMessageId = 0L,
        maxPages = maxPages,
        pageSize = pageSize,
        pageDelayMs = pageDelayMs,
        fetchPage = fetchPage,
      )
    }

    val safeMaxPages = maxPages.coerceIn(1, 5)
    val safePageSize = pageSize.coerceIn(10, 40)
    val safeDelayMs = pageDelayMs.coerceIn(0L, 1_500L)

    val collected = mutableListOf<ScannedTelegramMedia>()
    val seenMsgIds = HashSet<Long>()
    var cursor = beforeMessageId
    var pageAttempts = 0
    var highestSeen = 0L
    var lowestOlderSeen = 0L
    var sawCreationMarker = false
    var endSignal = HistoryEndSignal.NONE

    while (pageAttempts < safeMaxPages) {
      pageAttempts++
      val batch = fetchPage(cursor, safePageSize)
      if (batch.isEmpty()) {
        endSignal = if (sawCreationMarker) {
          HistoryEndSignal.CONFIRMED_CREATION_END
        } else {
          HistoryEndSignal.UNCERTAIN_EMPTY_OR_SHORT
        }
        break
      }

      var minBatchMsgId = Long.MAX_VALUE
      for (raw in batch) {
        if (raw.messageId > highestSeen) {
          highestSeen = raw.messageId
        }
        if (raw.messageId > 0L && raw.messageId < minBatchMsgId) {
          minBatchMsgId = raw.messageId
        }
        if (raw.isCreationMarker) {
          sawCreationMarker = true
        }
        if (raw.messageId in 1 until beforeMessageId) {
          if (lowestOlderSeen == 0L || raw.messageId < lowestOlderSeen) {
            lowestOlderSeen = raw.messageId
          }
          if (seenMsgIds.add(raw.messageId) && raw.media != null) {
            collected.add(raw.media)
          }
        }
      }

      val oldestInBatch = if (minBatchMsgId == Long.MAX_VALUE) 0L else minBatchMsgId
      if (sawCreationMarker && (oldestInBatch <= 0L || oldestInBatch >= cursor || batch.size < safePageSize)) {
        endSignal = HistoryEndSignal.CONFIRMED_CREATION_END
        break
      }
      if (oldestInBatch <= 0L || oldestInBatch >= cursor) {
        endSignal = HistoryEndSignal.STALLED_CURSOR
        break
      }

      cursor = oldestInBatch
      if (pageAttempts < safeMaxPages && safeDelayMs > 0L) {
        delay(safeDelayMs)
      }
    }

    val reachedEnd = endSignal == HistoryEndSignal.CONFIRMED_CREATION_END
    return HistoryScanPageResult(
      items = collected,
      highestMessageId = highestSeen,
      lowestMessageId = lowestOlderSeen,
      hitKnownCheckpoint = false,
      endSignal = endSignal,
      reachedHistoryEnd = reachedEnd,
    )
  }
}
