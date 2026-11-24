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
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
  modifier: Modifier = Modifier,
  contentPadding: PaddingValues = PaddingValues(bottom = 96.dp),
) {
  val gridState = rememberLazyGridState()
  val coroutineScope = rememberCoroutineScope()

  val headerIndexMap = remember(sections) {
    val map = mutableListOf<Pair<Int, String>>()
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
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Text(
              text = section.headerTitle,
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
              text = "${section.items.size} mục",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            onClick = { onItemClick(media) },
            onLongPress = { onItemLongPress(media) },
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
  onClick: () -> Unit,
  onLongPress: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val modelData = remember(media.id, media.localUri, media.cachedThumbPath, media.cachedLocalVideoPath) {
    media.localUri ?: media.cachedThumbPath ?: media.cachedLocalVideoPath
  }

  val imageRequest = remember(modelData) {
    ImageRequest.Builder(context)
      .data(modelData)
      .size(280)
      .crossfade(false)
      .build()
  }

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
        onClick = onClick,
        onLongClick = onLongPress,
      )
      .testTag("media_tile_${media.id}"),
  ) {
    if (modelData != null) {
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

    if (!isSelectionMode) {
      UploadStatusMiniBadge(
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
          .background(Color.Black.copy(alpha = 0.45f)),
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
  status: UploadStatus,
  modifier: Modifier = Modifier,
) {
  val (icon, tint) = when (status) {
    UploadStatus.UPLOADED -> Icons.Filled.CloudDone to StatusUploadedGreen
    UploadStatus.QUEUED, UploadStatus.UPLOADING -> Icons.Filled.CloudQueue to Color.White
    UploadStatus.PAUSED_FLOOD_WAIT -> Icons.Filled.PauseCircleOutline to StatusWarningAmber
    UploadStatus.FAILED -> Icons.Filled.ErrorOutline to StatusErrorRed
    UploadStatus.LOCAL_ONLY -> return
  }

  Box(
    modifier = modifier
      .size(20.dp)
      .clip(CircleShape)
      .background(Color.Black.copy(alpha = 0.48f)),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = status.labelVi,
      tint = tint,
      modifier = Modifier.size(13.dp),
    )
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
