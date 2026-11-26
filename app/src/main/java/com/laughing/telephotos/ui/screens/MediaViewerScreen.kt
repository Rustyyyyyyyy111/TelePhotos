package com.laughing.telephotos.ui.screens

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.core.MediaStoreScanner
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.data.local.UploadStatus
import com.laughing.telephotos.data.repository.SaveToDeviceProgress
import com.laughing.telephotos.data.telegram.FileDownloadProgress
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaViewerScreen(
  mediaCollection: List<MediaEntity>,
  initialMediaId: Long,
  onBack: () -> Unit,
  onMoveToTrash: (Long) -> Unit,
  onRestoreFromTrash: (Long) -> Unit,
  onDownloadMediaOnDemand: (MediaEntity) -> Flow<FileDownloadProgress>,
  onSaveDownloadedVideoPath: (Long, String) -> Unit,
  onClearStaleCachedPath: (Long) -> Unit = {},
  onSaveToDevice: (MediaEntity) -> Flow<SaveToDeviceProgress>,
  onFreeUpSpace: (Long) -> Unit,
  onNotifyMessage: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val coroutineScope = rememberCoroutineScope()
  var showInfoPanel by remember { mutableStateOf(false) }
  var showFreeUpConfirmDialog by remember { mutableStateOf(false) }
  var isSavingToDevice by remember { mutableStateOf(false) }
  var saveProgressFraction by remember { mutableFloatStateOf(0f) }
  var saveProgressText by remember { mutableStateOf<String?>(null) }
  var isCurrentPageZoomed by remember { mutableStateOf(false) }

  var hasSnappedToInitial by remember(initialMediaId) { mutableStateOf(false) }
  var activeMediaId by remember(initialMediaId) { mutableLongStateOf(initialMediaId) }

  val pagerState = rememberPagerState(
    initialPage = 0,
    pageCount = { mediaCollection.size },
  )

  LaunchedEffect(mediaCollection, initialMediaId) {
    if (mediaCollection.isEmpty()) return@LaunchedEffect
    if (!hasSnappedToInitial) {
      val initialIdx = mediaCollection.indexOfFirst { it.id == initialMediaId }.coerceAtLeast(0)
      pagerState.scrollToPage(initialIdx)
      activeMediaId = mediaCollection[initialIdx].id
      hasSnappedToInitial = true
    } else {
      val sameIdx = mediaCollection.indexOfFirst { it.id == activeMediaId }
      if (sameIdx >= 0 && sameIdx != pagerState.currentPage && !pagerState.isScrollInProgress) {
        pagerState.scrollToPage(sameIdx)
      }
    }
  }

  LaunchedEffect(pagerState.currentPage, mediaCollection) {
    isCurrentPageZoomed = false
    mediaCollection.getOrNull(pagerState.currentPage)?.let { item ->
      if (hasSnappedToInitial) {
        activeMediaId = item.id
      }
    }
  }

  val currentMedia = mediaCollection.getOrNull(pagerState.currentPage)
  val hasLocalDeviceCopy = remember(currentMedia?.id, currentMedia?.localUri) {
    currentMedia != null && MediaStoreScanner.isLocalUriAccessible(context, currentMedia.localUri)
  }
  val hasTelegramSource = remember(
    currentMedia?.id,
    currentMedia?.telegramFileId,
    currentMedia?.telegramRemoteFileId,
    currentMedia?.telegramChatId,
    currentMedia?.telegramMessageId,
  ) {
    currentMedia != null && (
      currentMedia.telegramFileId != null ||
        !currentMedia.telegramRemoteFileId.isNullOrBlank() ||
        (currentMedia.telegramChatId != null && currentMedia.telegramMessageId != null)
      )
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black),
  ) {
    if (mediaCollection.isEmpty() || currentMedia == null) {
      CircularProgressIndicator(
        modifier = Modifier.align(Alignment.Center),
        color = Color.White,
      )
    } else {
      HorizontalPager(
        state = pagerState,
        userScrollEnabled = !isCurrentPageZoomed,
        key = { page -> mediaCollection[page].id },
        modifier = Modifier
          .fillMaxSize()
          .testTag("media_viewer_pager"),
      ) { page ->
        val pageMedia = mediaCollection[page]
        val isActivePage = page == pagerState.currentPage
        if (pageMedia.mediaType == MediaType.VIDEO) {
          OnDemandVideoPlayerContent(
            media = pageMedia,
            isActivePage = isActivePage,
            onDownloadMediaOnDemand = onDownloadMediaOnDemand,
            onSaveDownloadedVideoPath = onSaveDownloadedVideoPath,
            onClearStaleCachedPath = onClearStaleCachedPath,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          PhotoViewerPage(
            media = pageMedia,
            isActivePage = isActivePage,
            onZoomChanged = { zoomed ->
              if (isActivePage) {
                isCurrentPageZoomed = zoomed
              }
            },
            onDownloadMediaOnDemand = onDownloadMediaOnDemand,
            onSaveDownloadedPath = onSaveDownloadedVideoPath,
            onClearStaleCachedPath = onClearStaleCachedPath,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }

      TopAppBar(
        title = {
          Column {
            Text(
              text = currentMedia.fileName,
              style = MaterialTheme.typography.titleSmall,
              color = Color.White,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
            if (mediaCollection.size > 1) {
              Text(
                text = "${pagerState.currentPage + 1}/${mediaCollection.size}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.72f),
              )
            }
          }
        },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("viewer_back_button")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = Color.White)
          }
        },
        actions = {
          if (!hasLocalDeviceCopy && hasTelegramSource) {
            IconButton(
              enabled = !isSavingToDevice,
              onClick = {
                val targetMedia = currentMedia
                isSavingToDevice = true
                saveProgressFraction = 0.05f
                saveProgressText = "Đang tải tệp gốc về máy..."
                coroutineScope.launch {
                  onSaveToDevice(targetMedia)
                    .catch { err ->
                      isSavingToDevice = false
                      saveProgressText = null
                      onNotifyMessage(err.message ?: "Không thể tải tệp về máy")
                    }
                    .collect { state ->
                      when (state) {
                        is SaveToDeviceProgress.Downloading -> {
                          saveProgressFraction = state.fraction
                          saveProgressText = state.statusVi
                        }
                        is SaveToDeviceProgress.Completed -> {
                          isSavingToDevice = false
                          saveProgressText = null
                          val folder = if (targetMedia.mediaType == MediaType.VIDEO) {
                            "Movies/Tele Photos"
                          } else {
                            "Pictures/Tele Photos"
                          }
                          onNotifyMessage("Đã lưu «${targetMedia.fileName}» vào $folder")
                        }
                      }
                    }
                }
              },
              modifier = Modifier.testTag("viewer_save_to_device_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.Download,
                contentDescription = "Tải về máy",
                tint = Color.White,
              )
            }
          }

          if (hasLocalDeviceCopy && currentMedia.uploadStatus == UploadStatus.UPLOADED && !currentMedia.isTrashed) {
            IconButton(
              onClick = { showFreeUpConfirmDialog = true },
              modifier = Modifier.testTag("viewer_free_up_space_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.CleaningServices,
                contentDescription = "Giải phóng dung lượng",
                tint = Color.White,
              )
            }
          }

          IconButton(
            onClick = { showInfoPanel = !showInfoPanel },
            modifier = Modifier.testTag("viewer_info_button"),
          ) {
            Icon(Icons.Filled.Info, contentDescription = "Thông tin tệp", tint = Color.White)
          }

          if (currentMedia.isTrashed) {
            IconButton(
              onClick = {
                onRestoreFromTrash(currentMedia.id)
                if (mediaCollection.size <= 1) onBack()
              },
              modifier = Modifier.testTag("viewer_restore_button"),
            ) {
              Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Khôi phục", tint = Color.White)
            }
          } else {
            IconButton(
              onClick = {
                onMoveToTrash(currentMedia.id)
                if (mediaCollection.size <= 1) onBack()
              },
              modifier = Modifier.testTag("viewer_trash_button"),
            ) {
              Icon(Icons.Filled.DeleteOutline, contentDescription = "Chuyển vào Đã xoá gần đây", tint = Color.White)
            }
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = Color.Black.copy(alpha = 0.45f),
        ),
      )

      if (isSavingToDevice) {
        Surface(
          shape = RoundedCornerShape(14.dp),
          color = Color(0xEE111824),
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(20.dp)
            .fillMaxWidth()
            .testTag("viewer_save_progress_card"),
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Text(
              text = saveProgressText ?: "Đang tải về máy...",
              style = MaterialTheme.typography.titleSmall,
              color = Color.White,
            )
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
              progress = { saveProgressFraction.coerceIn(0.05f, 1f) },
              modifier = Modifier.fillMaxWidth(),
            )
          }
        }
      }

      if (showInfoPanel) {
        Surface(
          shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
          color = Color(0xEE111824),
          modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .testTag("viewer_metadata_panel"),
        ) {
          Column(modifier = Modifier.padding(20.dp)) {
            Text(currentMedia.fileName, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))
            val sourceLabel = when {
              hasLocalDeviceCopy && currentMedia.uploadStatus == UploadStatus.UPLOADED ->
                "Có bản trên máy & Telegram"
              !hasLocalDeviceCopy && currentMedia.uploadStatus == UploadStatus.UPLOADED ->
                "Chỉ trực tuyến (Telegram)"
              else ->
                "Chỉ có trên máy"
            }
            Text(
              text = "Nguồn lưu trữ: $sourceLabel",
              style = MaterialTheme.typography.bodySmall,
              color = Color(0xFF4ADE80),
            )
            Text(
              text = "Thời gian: ${Formatters.formatFullDateTime(currentMedia.takenAtEpochMs)}",
              style = MaterialTheme.typography.bodySmall,
              color = Color.White.copy(alpha = 0.8f),
            )
            Text(
              text = "Dung lượng: ${Formatters.formatFileSize(currentMedia.sizeBytes)} • Trạng thái: ${currentMedia.uploadStatus.labelVi}",
              style = MaterialTheme.typography.bodySmall,
              color = Color.White.copy(alpha = 0.8f),
            )
            val identityDisplay = when {
              !currentMedia.contentHash.isNullOrBlank() ->
                "SHA-256: ${currentMedia.contentHash.take(24)}..."
              !currentMedia.shortHashHint.isNullOrBlank() ->
                "Mã rút gọn: ${currentMedia.shortHashHint}"
              !currentMedia.telegramFileUniqueId.isNullOrBlank() ->
                "Telegram File UID: ${currentMedia.telegramFileUniqueId}"
              else ->
                "SHA-256: Chưa tính (chờ đồng bộ)"
            }
            Text(
              text = identityDisplay,
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF38BDF8),
            )
            if (currentMedia.telegramMessageId != null) {
              Text(
                text = "Telegram Chat: ${currentMedia.telegramChatId} • Msg #${currentMedia.telegramMessageId} • Topic: ${currentMedia.topicId ?: "Chung"}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
              )
            }
            if (currentMedia.isTrashed) {
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Trong Đã xóa gần đây • ${Formatters.formatRemainingTrashDays(currentMedia.trashedAtEpochMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFBBF24),
              )
            }
          }
        }
      }

      if (showFreeUpConfirmDialog) {
        AlertDialog(
          onDismissRequest = { showFreeUpConfirmDialog = false },
          title = { Text("Giải phóng dung lượng máy?") },
          text = {
            Text(
              text = "Bản gốc trên bộ nhớ máy (${Formatters.formatFileSize(currentMedia.sizeBytes)}) sẽ bị xoá để tiết kiệm dung lượng. Bản sao lưu trên Telegram vẫn được giữ nguyên để bạn xem trực tuyến hoặc tải lại bất cứ lúc nào.",
              style = MaterialTheme.typography.bodyMedium,
            )
          },
          confirmButton = {
            Button(
              onClick = {
                showFreeUpConfirmDialog = false
                onFreeUpSpace(currentMedia.id)
              },
              modifier = Modifier.testTag("confirm_viewer_free_up_button"),
            ) {
              Text("Giải phóng")
            }
          },
          dismissButton = {
            TextButton(onClick = { showFreeUpConfirmDialog = false }) {
              Text("Huỷ")
            }
          },
        )
      }
    }
  }
}

@Composable
private fun PhotoViewerPage(
  media: MediaEntity,
  isActivePage: Boolean,
  onZoomChanged: (Boolean) -> Unit,
  onDownloadMediaOnDemand: (MediaEntity) -> Flow<FileDownloadProgress>,
  onSaveDownloadedPath: (Long, String) -> Unit,
  onClearStaleCachedPath: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val hasUsableCache = remember(media.id, media.cachedLocalVideoPath) {
    MediaStoreScanner.isUsableLocalFile(media.cachedLocalVideoPath)
  }

  LaunchedEffect(media.id, media.cachedLocalVideoPath) {
    if (!media.cachedLocalVideoPath.isNullOrBlank() && !hasUsableCache) {
      onClearStaleCachedPath(media.id)
    }
  }

  val localFullPhotoModel = remember(media.id, media.localUri, media.cachedLocalVideoPath, hasUsableCache) {
    val localReadable = media.localUri?.takeIf {
      MediaStoreScanner.isLocalUriAccessible(context, it)
    }
    localReadable ?: media.cachedLocalVideoPath
      ?.takeIf { hasUsableCache }
      ?.let { File(it) }
  }

  var localModelFailed by remember(media.id, localFullPhotoModel) { mutableStateOf(false) }

  if (localFullPhotoModel != null && !localModelFailed) {
    ZoomablePhotoBox(
      isActivePage = isActivePage,
      onZoomChanged = onZoomChanged,
      modifier = modifier,
    ) {
      AsyncImage(
        model = localFullPhotoModel,
        contentDescription = media.fileName,
        contentScale = ContentScale.Fit,
        onError = {
          localModelFailed = true
          if (!media.cachedLocalVideoPath.isNullOrBlank()) {
            onClearStaleCachedPath(media.id)
          }
        },
        modifier = Modifier.fillMaxSize(),
      )
    }
  } else {
    OnDemandPhotoDownloadFallback(
      media = media,
      isActivePage = isActivePage,
      onZoomChanged = onZoomChanged,
      onDownloadOnDemand = onDownloadMediaOnDemand,
      onSaveDownloadedPath = onSaveDownloadedPath,
      onClearStaleCachedPath = onClearStaleCachedPath,
      modifier = modifier,
    )
  }
}

@Composable
private fun ZoomablePhotoBox(
  isActivePage: Boolean,
  onZoomChanged: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  var scale by remember { mutableFloatStateOf(1f) }
  var offset by remember { mutableStateOf(Offset.Zero) }
  var containerSize by remember { mutableStateOf(IntSize.Zero) }
  val latestOnZoomChanged by rememberUpdatedState(onZoomChanged)

  LaunchedEffect(isActivePage) {
    if (!isActivePage) {
      scale = 1f
      offset = Offset.Zero
      latestOnZoomChanged(false)
    }
  }

  LaunchedEffect(scale, isActivePage) {
    if (isActivePage) {
      latestOnZoomChanged(scale > 1.02f)
    }
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .onSizeChanged { containerSize = it }
      .pointerInput(Unit) {
        detectTapGestures(
          onDoubleTap = {
            if (scale > 1.02f) {
              scale = 1f
              offset = Offset.Zero
            } else {
              scale = 2.5f
            }
          }
        )
      }
      .pointerInput(Unit) {
        awaitEachGesture {
          awaitFirstDown(requireUnconsumed = false)
          do {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            val handleTransform = pressedCount >= 2 || scale > 1.02f
            if (handleTransform) {
              val nextScale = (scale * zoomChange).coerceIn(1f, 4f)
              val nextOffset = if (nextScale <= 1.02f) {
                Offset.Zero
              } else {
                val maxX = ((containerSize.width * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                val maxY = ((containerSize.height * (nextScale - 1f)) / 2f).coerceAtLeast(0f)
                Offset(
                  x = (offset.x + panChange.x).coerceIn(-maxX, maxX),
                  y = (offset.y + panChange.y).coerceIn(-maxY, maxY),
                )
              }
              scale = nextScale
              offset = nextOffset
              event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
          } while (event.changes.any { it.pressed })
        }
      }
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
        translationX = offset.x
        translationY = offset.y
      },
    contentAlignment = Alignment.Center,
  ) {
    content()
  }
}

@Composable
private fun OnDemandVideoPlayerContent(
  media: MediaEntity,
  isActivePage: Boolean,
  onDownloadMediaOnDemand: (MediaEntity) -> Flow<FileDownloadProgress>,
  onSaveDownloadedVideoPath: (Long, String) -> Unit,
  onClearStaleCachedPath: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val hasUsableCache = remember(media.id, media.cachedLocalVideoPath) {
    MediaStoreScanner.isUsableLocalFile(media.cachedLocalVideoPath)
  }
  LaunchedEffect(media.id, media.cachedLocalVideoPath) {
    if (!media.cachedLocalVideoPath.isNullOrBlank() && !hasUsableCache) {
      onClearStaleCachedPath(media.id)
    }
  }

  val existingLocalUri = remember(media.id, media.localUri, media.cachedLocalVideoPath, hasUsableCache) {
    val localReadableUri = media.localUri?.takeIf {
      MediaStoreScanner.isLocalUriAccessible(context, it)
    }?.let { Uri.parse(it) }
    localReadableUri ?: media.cachedLocalVideoPath
      ?.takeIf { hasUsableCache }
      ?.let { Uri.fromFile(File(it)) }
  }

  var playableUri by remember(media.id) { mutableStateOf(existingLocalUri) }
  var downloadProgress by remember(media.id) { mutableStateOf<FileDownloadProgress?>(null) }
  var downloadError by remember(media.id) { mutableStateOf<String?>(null) }
  var retryTrigger by remember(media.id) { mutableIntStateOf(0) }

  LaunchedEffect(media.id, existingLocalUri, retryTrigger) {
    if (existingLocalUri != null) {
      playableUri = existingLocalUri
      downloadError = null
      return@LaunchedEffect
    }
    val hasSource = media.telegramFileId != null ||
      !media.telegramRemoteFileId.isNullOrBlank() ||
      (media.telegramChatId != null && media.telegramMessageId != null)
    if (!hasSource) {
      downloadError = "Không tìm thấy tệp video trên máy hoặc Telegram"
      return@LaunchedEffect
    }
    if (playableUri == null) {
      downloadError = null
      downloadProgress = null
      onDownloadMediaOnDemand(media)
        .catch { err -> downloadError = err.message ?: "Không thể tải video từ Telegram" }
        .collect { progress ->
          downloadProgress = progress
          if (progress.isCompleted && MediaStoreScanner.isUsableLocalFile(progress.localPath)) {
            val validPath = progress.localPath!!
            onSaveDownloadedVideoPath(media.id, validPath)
            playableUri = Uri.fromFile(File(validPath))
          }
        }
    }
  }

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    val activeUri = playableUri
    if (activeUri != null) {
      Media3ExoPlayerBox(
        uri = activeUri,
        playWhenActive = isActivePage,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      val previewThumb = media.cachedThumbPath?.takeIf { MediaStoreScanner.isUsableLocalFile(it) }
      if (previewThumb != null) {
        AsyncImage(
          model = File(previewThumb),
          contentDescription = media.fileName,
          contentScale = ContentScale.Fit,
          alpha = 0.42f,
          modifier = Modifier.fillMaxSize(),
        )
      }

      if (downloadError != null) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .padding(24.dp)
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        ) {
          Text(
            text = downloadError ?: "Lỗi tải video",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(12.dp))
          Button(
            onClick = {
              playableUri = null
              retryTrigger++
            },
            modifier = Modifier.testTag("viewer_retry_download_button"),
          ) {
            Text("Thử tải lại")
          }
        }
      } else {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
          modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(28.dp)
            .testTag("video_download_progress"),
        ) {
          Icon(
            imageVector = Icons.Filled.CloudDownload,
            contentDescription = null,
            tint = Color(0xFF38BDF8),
            modifier = Modifier.size(44.dp),
          )
          Spacer(modifier = Modifier.height(12.dp))
          val pct = ((downloadProgress?.fraction ?: 0f) * 100).toInt()
          Text(
            text = downloadProgress?.statusMessageVi ?: "Đang tải video gốc từ Telegram... $pct%",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(10.dp))
          LinearProgressIndicator(
            progress = { downloadProgress?.fraction ?: 0f },
            modifier = Modifier.fillMaxWidth(0.7f),
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "Thời lượng: ${Formatters.formatVideoDuration(media.durationSec)} • ${
              Formatters.formatFileSize(media.sizeBytes)
            }",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.75f),
          )
        }
      }
    }
  }
}

@Composable
private fun Media3ExoPlayerBox(
  uri: Uri,
  playWhenActive: Boolean,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val exoPlayer = remember(uri) {
    ExoPlayer.Builder(context).build().apply {
      setMediaItem(MediaItem.fromUri(uri))
      prepare()
      playWhenReady = playWhenActive
    }
  }

  LaunchedEffect(exoPlayer, playWhenActive) {
    if (!playWhenActive) {
      exoPlayer.pause()
    } else if (!exoPlayer.isPlaying) {
      exoPlayer.playWhenReady = true
    }
  }

  DisposableEffect(exoPlayer) {
    onDispose {
      exoPlayer.stop()
      exoPlayer.release()
    }
  }

  AndroidView(
    factory = { ctx ->
      PlayerView(ctx).apply {
        player = exoPlayer
        useController = true
        keepScreenOn = true
        layoutParams = FrameLayout.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT,
        )
      }
    },
    update = { view ->
      if (view.player !== exoPlayer) {
        view.player = exoPlayer
      }
    },
    modifier = modifier.testTag("media3_player_view"),
  )
}

@Composable
private fun OnDemandPhotoDownloadFallback(
  media: MediaEntity,
  isActivePage: Boolean,
  onZoomChanged: (Boolean) -> Unit,
  onDownloadOnDemand: (MediaEntity) -> Flow<FileDownloadProgress>,
  onSaveDownloadedPath: (Long, String) -> Unit,
  onClearStaleCachedPath: (Long) -> Unit,
  modifier: Modifier = Modifier,
) {
  var localPath by remember(media.id) { mutableStateOf<String?>(null) }
  var progressFraction by remember(media.id) { mutableFloatStateOf(0f) }
  var statusMessage by remember(media.id) { mutableStateOf<String?>(null) }
  var downloadError by remember(media.id) { mutableStateOf<String?>(null) }
  var retryTrigger by remember(media.id) { mutableIntStateOf(0) }

  LaunchedEffect(media.id, retryTrigger) {
    val hasSource = media.telegramFileId != null ||
      !media.telegramRemoteFileId.isNullOrBlank() ||
      (media.telegramChatId != null && media.telegramMessageId != null)
    if (!hasSource) {
      downloadError = "Không tìm thấy tệp ảnh trên máy hoặc Telegram"
      return@LaunchedEffect
    }
    downloadError = null
    statusMessage = null
    localPath = null
    onDownloadOnDemand(media)
      .catch { err ->
        downloadError = err.message ?: "Không thể tải ảnh từ Telegram"
      }
      .collect { prog ->
        progressFraction = prog.fraction
        statusMessage = prog.statusMessageVi
        if (prog.isCompleted && MediaStoreScanner.isUsableLocalFile(prog.localPath)) {
          val validPath = prog.localPath!!
          onSaveDownloadedPath(media.id, validPath)
          localPath = validPath
        }
      }
  }

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    val path = localPath?.takeIf { MediaStoreScanner.isUsableLocalFile(it) }
    if (path != null) {
      ZoomablePhotoBox(
        isActivePage = isActivePage,
        onZoomChanged = onZoomChanged,
        modifier = Modifier.fillMaxSize(),
      ) {
        AsyncImage(
          model = File(path),
          contentDescription = media.fileName,
          contentScale = ContentScale.Fit,
          onError = {
            localPath = null
            onClearStaleCachedPath(media.id)
            downloadError = "Tệp ảnh bộ nhớ đệm không hợp lệ"
          },
          modifier = Modifier.fillMaxSize(),
        )
      }
    } else {
      val previewThumb = media.cachedThumbPath?.takeIf { MediaStoreScanner.isUsableLocalFile(it) }
      if (previewThumb != null) {
        AsyncImage(
          model = File(previewThumb),
          contentDescription = media.fileName,
          contentScale = ContentScale.Fit,
          alpha = 0.55f,
          modifier = Modifier.fillMaxSize(),
        )
      }

      if (downloadError != null) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .padding(24.dp)
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        ) {
          Text(
            text = downloadError ?: "Không thể tải ảnh từ Telegram",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
          )
          Spacer(modifier = Modifier.height(12.dp))
          Button(
            onClick = { retryTrigger++ },
            modifier = Modifier.testTag("viewer_retry_download_button"),
          ) {
            Text("Thử tải lại")
          }
        }
      } else {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          modifier = Modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(20.dp),
        ) {
          Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color.Transparent)
          CircularProgressIndicator(progress = { progressFraction.coerceAtLeast(0.05f) }, color = Color.White)
          Spacer(modifier = Modifier.height(10.dp))
          Text(
            text = statusMessage ?: "Đang tải ảnh gốc từ Telegram...",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            textAlign = TextAlign.Center,
          )
        }
      }
    }
  }
}
