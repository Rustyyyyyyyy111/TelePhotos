package com.laughing.telephotos.ui.screens

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
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
  media: MediaEntity?,
  onBack: () -> Unit,
  onMoveToTrash: (Long) -> Unit,
  onRestoreFromTrash: (Long) -> Unit,
  onDownloadVideoOnDemand: (Int) -> Flow<FileDownloadProgress>,
  onSaveDownloadedVideoPath: (Long, String) -> Unit,
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
  var saveProgressFraction by remember { mutableStateOf(0f) }
  var saveProgressText by remember { mutableStateOf<String?>(null) }

  val hasLocalDeviceCopy = remember(media?.id, media?.localUri) {
    media != null && MediaStoreScanner.isLocalUriAccessible(context, media.localUri)
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color.Black),
  ) {
    if (media == null) {
      CircularProgressIndicator(
        modifier = Modifier.align(Alignment.Center),
        color = Color.White,
      )
    } else {
      if (media.mediaType == MediaType.VIDEO) {
        OnDemandVideoPlayerContent(
          media = media,
          onDownloadVideoOnDemand = onDownloadVideoOnDemand,
          onSaveDownloadedVideoPath = onSaveDownloadedVideoPath,
          modifier = Modifier.fillMaxSize(),
        )
      } else {
        val localFullPhotoModel = remember(media.id, media.localUri, media.cachedLocalVideoPath) {
          val localReadable = media.localUri?.takeIf {
            MediaStoreScanner.isLocalUriAccessible(context, it)
          }
          localReadable ?: media.cachedLocalVideoPath
            ?.takeIf { it.isNotBlank() && File(it).exists() }
            ?.let { File(it) }
        }
        if (localFullPhotoModel != null) {
          AsyncImage(
            model = localFullPhotoModel,
            contentDescription = media.fileName,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
          )
        } else {
          OnDemandPhotoDownloadFallback(
            media = media,
            onDownloadOnDemand = onDownloadVideoOnDemand,
            onSaveDownloadedPath = onSaveDownloadedVideoPath,
            modifier = Modifier.fillMaxSize(),
          )
        }
      }

      TopAppBar(
        title = {
          Text(
            text = media.fileName,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        },
        navigationIcon = {
          IconButton(onClick = onBack, modifier = Modifier.testTag("viewer_back_button")) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại", tint = Color.White)
          }
        },
        actions = {
          if (!hasLocalDeviceCopy && media.telegramFileId != null) {
            IconButton(
              enabled = !isSavingToDevice,
              onClick = {
                isSavingToDevice = true
                saveProgressFraction = 0.05f
                saveProgressText = "Đang tải tệp gốc về máy..."
                coroutineScope.launch {
                  onSaveToDevice(media)
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
                          val folder = if (media.mediaType == MediaType.VIDEO) {
                            "Movies/Tele Photos"
                          } else {
                            "Pictures/Tele Photos"
                          }
                          onNotifyMessage("Đã lưu «${media.fileName}» vào $folder")
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

          if (hasLocalDeviceCopy && media.uploadStatus == UploadStatus.UPLOADED && !media.isTrashed) {
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

          if (media.isTrashed) {
            IconButton(
              onClick = {
                onRestoreFromTrash(media.id)
                onBack()
              },
              modifier = Modifier.testTag("viewer_restore_button"),
            ) {
              Icon(Icons.Filled.RestoreFromTrash, contentDescription = "Khôi phục", tint = Color.White)
            }
          } else {
            IconButton(
              onClick = {
                onMoveToTrash(media.id)
                onBack()
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
            Text(media.fileName, style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))
            val sourceLabel = when {
              hasLocalDeviceCopy && media.uploadStatus == UploadStatus.UPLOADED ->
                "Có bản trên máy & Telegram"
              !hasLocalDeviceCopy && media.uploadStatus == UploadStatus.UPLOADED ->
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
              text = "Thời gian: ${Formatters.formatFullDateTime(media.takenAtEpochMs)}",
              style = MaterialTheme.typography.bodySmall,
              color = Color.White.copy(alpha = 0.8f),
            )
            Text(
              text = "Dung lượng: ${Formatters.formatFileSize(media.sizeBytes)} • Trạng thái: ${media.uploadStatus.labelVi}",
              style = MaterialTheme.typography.bodySmall,
              color = Color.White.copy(alpha = 0.8f),
            )
            Text(
              text = "SHA-256: ${media.contentHash.take(24)}...",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF38BDF8),
            )
            if (media.telegramMessageId != null) {
              Text(
                text = "Telegram Chat: ${media.telegramChatId} • Msg #${media.telegramMessageId} • Topic: ${media.topicId ?: "Chung"}",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
              )
            }
            if (media.isTrashed) {
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Trong Đã xóa gần đây • ${Formatters.formatRemainingTrashDays(media.trashedAtEpochMs)}",
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
              text = "Bản gốc trên bộ nhớ máy (${Formatters.formatFileSize(media.sizeBytes)}) sẽ bị xoá để tiết kiệm dung lượng. Bản sao lưu trên Telegram vẫn được giữ nguyên để bạn xem trực tuyến hoặc tải lại bất cứ lúc nào.",
              style = MaterialTheme.typography.bodyMedium,
            )
          },
          confirmButton = {
            Button(
              onClick = {
                showFreeUpConfirmDialog = false
                onFreeUpSpace(media.id)
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
private fun OnDemandVideoPlayerContent(
  media: MediaEntity,
  onDownloadVideoOnDemand: (Int) -> Flow<FileDownloadProgress>,
  onSaveDownloadedVideoPath: (Long, String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val existingLocalUri = remember(media.id, media.localUri, media.cachedLocalVideoPath) {
    val localReadableUri = media.localUri?.takeIf {
      MediaStoreScanner.isLocalUriAccessible(context, it)
    }?.let { Uri.parse(it) }
    localReadableUri ?: media.cachedLocalVideoPath
      ?.takeIf { it.isNotBlank() && File(it).exists() }
      ?.let { Uri.fromFile(File(it)) }
  }

  var playableUri by remember(media.id) { mutableStateOf(existingLocalUri) }
  var downloadProgress by remember(media.id) { mutableStateOf<FileDownloadProgress?>(null) }
  var downloadError by remember(media.id) { mutableStateOf<String?>(null) }
  var retryTrigger by remember { mutableStateOf(0) }

  LaunchedEffect(media.id, existingLocalUri, retryTrigger) {
    if (existingLocalUri != null) {
      playableUri = existingLocalUri
      return@LaunchedEffect
    }
    if (playableUri == null && media.telegramFileId != null) {
      downloadError = null
      onDownloadVideoOnDemand(media.telegramFileId)
        .catch { err -> downloadError = err.message ?: "Không thể tải video từ Telegram" }
        .collect { progress ->
          downloadProgress = progress
          if (progress.isCompleted && !progress.localPath.isNullOrBlank()) {
            onSaveDownloadedVideoPath(media.id, progress.localPath)
            playableUri = Uri.fromFile(File(progress.localPath))
          }
        }
    }
  }

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    val activeUri = playableUri
    if (activeUri != null) {
      Media3ExoPlayerBox(
        uri = activeUri,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      val previewThumb = media.cachedThumbPath?.takeIf { it.isNotBlank() && File(it).exists() }
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
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        ) {
          Text(
            text = downloadError ?: "Lỗi tải video",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
          )
          Spacer(modifier = Modifier.height(12.dp))
          Button(onClick = { retryTrigger++ }) {
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
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val exoPlayer = remember(uri) {
    ExoPlayer.Builder(context).build().apply {
      setMediaItem(MediaItem.fromUri(uri))
      prepare()
      playWhenReady = true
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
  onDownloadOnDemand: (Int) -> Flow<FileDownloadProgress>,
  onSaveDownloadedPath: (Long, String) -> Unit,
  modifier: Modifier = Modifier,
) {
  var localPath by remember(media.id) { mutableStateOf<String?>(null) }
  var progressFraction by remember(media.id) { mutableStateOf(0f) }
  var statusMessage by remember(media.id) { mutableStateOf<String?>(null) }
  var downloadError by remember(media.id) { mutableStateOf<String?>(null) }
  var retryTrigger by remember { mutableStateOf(0) }

  LaunchedEffect(media.id, retryTrigger) {
    val fileId = media.telegramFileId ?: return@LaunchedEffect
    downloadError = null
    statusMessage = null
    onDownloadOnDemand(fileId)
      .catch { err ->
        downloadError = err.message ?: "Không thể tải ảnh từ Telegram"
      }
      .collect { prog ->
        progressFraction = prog.fraction
        statusMessage = prog.statusMessageVi
        if (prog.isCompleted && !prog.localPath.isNullOrBlank()) {
          onSaveDownloadedPath(media.id, prog.localPath)
          localPath = prog.localPath
        }
      }
  }

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    val path = localPath
    if (path != null) {
      AsyncImage(
        model = File(path),
        contentDescription = media.fileName,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      val previewThumb = media.cachedThumbPath?.takeIf { it.isNotBlank() && File(it).exists() }
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
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .padding(24.dp),
        ) {
          Text(
            text = downloadError ?: "Không thể tải ảnh từ Telegram",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
          )
          Spacer(modifier = Modifier.height(12.dp))
          Button(onClick = { retryTrigger++ }) {
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
          )
        }
      }
    }
  }
}
