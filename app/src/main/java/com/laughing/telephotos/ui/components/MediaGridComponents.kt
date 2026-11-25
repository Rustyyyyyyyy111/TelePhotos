package com.laughing.telephotos.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.laughing.telephotos.core.Formatters
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.data.local.UploadStatus
import com.laughing.telephotos.ui.theme.StatusErrorRed
import com.laughing.telephotos.ui.theme.StatusUploadedGreen
import com.laughing.telephotos.ui.theme.StatusWarningAmber
import com.laughing.telephotos.ui.viewmodel.TimelineSection
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@Composable
fun ChronologicalMediaGrid(
  sections: List<TimelineSection>,
  selectedIds: Set<Long>,
  isSelectionMode: Boolean,
  onItemClick: (MediaEntity) -> Unit,
  onItemLongPress: (MediaEntity) -> Unit,
  onToggleDateGroup: (List<Long>) -> Unit = {},
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
) {
  val gridState = rememberLazyGridState()
  val coroutineScope = rememberCoroutineScope()
  val currentItemClick by rememberUpdatedState(onItemClick)
  val currentItemLongPress by rememberUpdatedState(onItemLongPress)
  val currentToggleDateGroup by rememberUpdatedState(onToggleDateGroup)

  val headerIndexMap = remember(sections) {
    val map = ArrayList<Pair<Int, String>>(sections.size)
    var cursor = 0
    for (section in sections) {
      map.add(cursor to section.monthLabel)
      cursor += 1 + section.items.size
    }
    map
  }

  val totalGridEntries = remember(sections) {
    sections.sumOf { 1 + it.items.size }
  }

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    LazyVerticalGrid(
      columns = GridCells.Adaptive(minSize = 112.dp),
      state = gridState,
      contentPadding = contentPadding,
      verticalArrangement = Arrangement.spacedBy(3.dp),
      horizontalArrangement = Arrangement.spacedBy(3.dp),
      modifier = Modifier
        .fillMaxSize()
        .testTag("timeline_media_grid"),
    ) {
      for (section in sections) {
        item(
          key = "header_${section.headerTitle}",
          span = { GridItemSpan(maxLineSpan) },
          contentType = "timeline_header",
        ) {
          val sectionIds = remember(section.items) { section.items.map { it.id } }
          val selectedCountInSection = remember(sectionIds, selectedIds) {
            if (selectedIds.isEmpty()) 0 else sectionIds.count { it in selectedIds }
          }
          val allSelectedInSection = sectionIds.isNotEmpty() && selectedCountInSection == sectionIds.size

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = section.headerTitle,
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onBackground,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = if (selectedCountInSection > 0) {
                  "$selectedCountInSection/${section.items.size} mục"
                } else {
                  "${section.items.size} mục"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.width(2.dp))
              IconButton(
                onClick = { currentToggleDateGroup(sectionIds) },
                modifier = Modifier
                  .size(48.dp)
                  .testTag("date_group_select_${section.headerTitle}"),
              ) {
                Icon(
                  imageVector = if (allSelectedInSection) {
                    Icons.Filled.CheckCircle
                  } else {
                    Icons.Outlined.RadioButtonUnchecked
                  },
                  contentDescription = if (allSelectedInSection) {
                    "Bỏ chọn ngày ${section.headerTitle}"
                  } else {
                    "Chọn tất cả mục ngày ${section.headerTitle}"
                  },
                  tint = if (allSelectedInSection) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(
                      alpha = if (isSelectionMode) 0.9f else 0.55f
                    )
                  },
                  modifier = Modifier.size(20.dp),
                )
              }
            }
          }
        }

        items(
          items = section.items,
          key = { item -> item.id },
          contentType = { item -> item.mediaType },
        ) { media ->
          MediaGridTile(
            media = media,
            isSelected = media.id in selectedIds,
            isSelectionMode = isSelectionMode,
            onMediaClick = currentItemClick,
            onMediaLongPress = currentItemLongPress,
          )
        }
      }
    }

    if (totalGridEntries > 18) {
      TimelineFastScroller(
        gridState = gridState,
        totalItems = totalGridEntries,
        headerIndexMap = headerIndexMap,
        onScrollToIndex = { targetIndex ->
          coroutineScope.launch { gridState.scrollToItem(targetIndex) }
        },
        modifier = Modifier.align(Alignment.CenterEnd),
      )
    }
  }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGridTile(
  media: MediaEntity,
  isSelected: Boolean,
  isSelectionMode: Boolean,
  onMediaClick: (MediaEntity) -> Unit,
  onMediaLongPress: (MediaEntity) -> Unit,
  modifier: Modifier = Modifier,
) {
  val appContext = LocalContext.current.applicationContext
  val latestMedia by rememberUpdatedState(media)
  val latestClick by rememberUpdatedState(onMediaClick)
  val latestLongPress by rememberUpdatedState(onMediaLongPress)

  val modelData = remember(media.id, media.localUri, media.cachedThumbPath, media.cachedLocalVideoPath) {
    media.localUri ?: media.cachedThumbPath ?: media.cachedLocalVideoPath
  }

  val imageRequest = remember(modelData) {
    if (modelData == null) null
    else {
      ImageRequest.Builder(appContext)
        .data(modelData)
        .size(256)
        .allowRgb565(true)
        .crossfade(false)
        .build()
    }
  }

  val tileClick = remember(media.id) { { latestClick(latestMedia) } }
  val tileLongClick = remember(media.id) { { latestLongPress(latestMedia) } }

  Box(
    modifier = modifier
      .aspectRatio(1f)
      .clip(RoundedCornerShape(4.dp))
      .background(MaterialTheme.colorScheme.surfaceVariant)
      .then(
        if (isSelected) {
          Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
        } else {
          Modifier
        }
      )
      .combinedClickable(
        onClick = tileClick,
        onLongClick = tileLongClick,
      )
      .testTag("media_tile_${media.id}"),
  ) {
    if (imageRequest != null) {
      AsyncImage(
        model = imageRequest,
        contentDescription = media.fileName,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (media.mediaType == MediaType.VIDEO) {
            Icons.Filled.Videocam
          } else {
            Icons.Filled.CloudDone
          },
          contentDescription = media.fileName,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(26.dp),
        )
      }
    }

    if (isSelected) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
      )
    }

    if (!isSelectionMode) {
      UploadStatusMiniBadge(
        mediaId = media.id,
        status = media.uploadStatus,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(5.dp),
      )
    } else {
      Box(
        modifier = Modifier
          .align(Alignment.TopStart)
          .padding(5.dp)
          .size(22.dp)
          .clip(CircleShape)
          .background(
            if (isSelected) Color.White else Color.Black.copy(alpha = 0.45f)
          ),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          imageVector = if (isSelected) {
            Icons.Filled.CheckCircle
          } else {
            Icons.Outlined.RadioButtonUnchecked
          },
          contentDescription = if (isSelected) "Đã chọn" else "Chưa chọn",
          tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
          modifier = Modifier.size(20.dp),
        )
      }
    }

    if (media.mediaType == MediaType.VIDEO) {
      Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Black.copy(alpha = 0.68f),
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(5.dp)
          .testTag("video_duration_badge_${media.id}"),
      ) {
        Text(
          text = Formatters.formatVideoDuration(media.durationSec),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = Color.White,
          modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
      }
    }
  }
}

@Composable
private fun UploadStatusMiniBadge(
  mediaId: Long,
  status: UploadStatus,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier
      .size(22.dp)
      .clip(CircleShape)
      .background(
        when (status) {
          UploadStatus.UPLOADED -> Color.Black.copy(alpha = 0.56f)
          else -> Color.Black.copy(alpha = 0.42f)
        }
      )
      .testTag("sync_badge_${mediaId}_${status.name}"),
    contentAlignment = Alignment.Center,
  ) {
    when (status) {
      UploadStatus.UPLOADED -> {
        Icon(
          imageVector = Icons.Filled.CheckCircle,
          contentDescription = "Đã đồng bộ lên Telegram",
          tint = StatusUploadedGreen,
          modifier = Modifier.size(16.dp),
        )
      }
      UploadStatus.UPLOADING -> {
        CircularProgressIndicator(
          color = Color.White,
          strokeWidth = 2.dp,
          modifier = Modifier.size(13.dp),
        )
      }
      UploadStatus.QUEUED -> {
        Icon(
          imageVector = Icons.Outlined.CheckCircle,
          contentDescription = "Đang chờ đồng bộ",
          tint = Color(0xFF93C5FD),
          modifier = Modifier.size(15.dp),
        )
      }
      UploadStatus.PAUSED_FLOOD_WAIT -> {
        Icon(
          imageVector = Icons.Filled.PauseCircleOutline,
          contentDescription = status.labelVi,
          tint = StatusWarningAmber,
          modifier = Modifier.size(15.dp),
        )
      }
      UploadStatus.FAILED -> {
        Icon(
          imageVector = Icons.Filled.ErrorOutline,
          contentDescription = status.labelVi,
          tint = StatusErrorRed,
          modifier = Modifier.size(15.dp),
        )
      }
      UploadStatus.LOCAL_ONLY -> {
        Icon(
          imageVector = Icons.Outlined.CheckCircle,
          contentDescription = "Chưa đồng bộ lên Telegram",
          tint = Color(0xFFD1D5DB).copy(alpha = 0.85f),
          modifier = Modifier.size(15.dp),
        )
      }
    }
  }
}

@Composable
private fun TimelineFastScroller(
  gridState: LazyGridState,
  totalItems: Int,
  headerIndexMap: List<Pair<Int, String>>,
  onScrollToIndex: (Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  var isDragging by remember { mutableStateOf(false) }
  var dragFraction by remember { mutableStateOf(0f) }

  val currentFraction by remember(totalItems) {
    derivedStateOf {
      if (totalItems <= 1) 0f
      else (gridState.firstVisibleItemIndex.toFloat() / (totalItems - 1).toFloat()).coerceIn(0f, 1f)
    }
  }

  val activeFraction = if (isDragging) dragFraction else currentFraction
  val activeMonthLabel = remember(activeFraction, headerIndexMap, totalItems) {
    val targetIdx = (activeFraction * (totalItems - 1)).roundToInt()
    headerIndexMap.lastOrNull { it.first <= targetIdx }?.second ?: ""
  }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxHeight()
      .padding(vertical = 24.dp, horizontal = 4.dp)
      .width(if (isDragging) 180.dp else 32.dp),
  ) {
    val trackHeightPx = with(LocalDensity.current) { (maxHeight - 44.dp).toPx().coerceAtLeast(1f) }
    val thumbOffsetPx = (activeFraction * trackHeightPx).roundToInt()

    if (isDragging && activeMonthLabel.isNotBlank()) {
      Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier
          .align(Alignment.TopEnd)
          .padding(end = 36.dp)
          .offset { IntOffset(0, thumbOffsetPx) },
      ) {
        Text(
          text = activeMonthLabel,
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
      }
    }

    Box(
      modifier = Modifier
        .align(Alignment.TopEnd)
        .offset { IntOffset(0, thumbOffsetPx) }
        .width(24.dp)
        .height(44.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(
          if (isDragging) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.surface.copy(alpha = 0.88f)
        )
        .border(
          1.dp,
          MaterialTheme.colorScheme.outline,
          RoundedCornerShape(12.dp),
        )
        .pointerInput(totalItems, trackHeightPx) {
          detectVerticalDragGestures(
            onDragStart = { isDragging = true },
            onDragEnd = { isDragging = false },
            onDragCancel = { isDragging = false },
            onVerticalDrag = { change, dragAmount ->
              change.consume()
              val nextPx = (activeFraction * trackHeightPx + dragAmount).coerceIn(0f, trackHeightPx)
              val nextFraction = nextPx / trackHeightPx
              dragFraction = nextFraction
              val targetIdx = (nextFraction * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
              onScrollToIndex(targetIdx)
            },
          )
        },
    )
  }
}
