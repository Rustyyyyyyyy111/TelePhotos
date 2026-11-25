package com.laughing.telephotos.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.laughing.telephotos.data.local.AlbumEntity
import com.laughing.telephotos.data.local.AlbumWithCount
import com.laughing.telephotos.data.local.MediaEntity
import com.laughing.telephotos.data.local.SyncTargetEntity
import com.laughing.telephotos.ui.components.MediaGridTile
import com.laughing.telephotos.ui.components.TelePhotosGlassCard

private val TopicColors = listOf(0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumsScreen(
  albums: List<AlbumWithCount>,
  trashedCount: Int,
  lockedTarget: SyncTargetEntity?,
  onAlbumClick: (Long) -> Unit,
  onOpenTrash: () -> Unit,
  onCreateAlbum: (String, Int) -> Unit,
  onOpenTargetSelector: () -> Unit,
  onPurgeExpiredTrash: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var showCreateDialog by remember { mutableStateOf(false) }

  LaunchedEffect(Unit) {
    onPurgeExpiredTrash()
  }

  Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      TopAppBar(
        title = {
          Column {
            Text("Album & Chủ đề Forum", style = MaterialTheme.typography.headlineMedium)
            Text(
              text = if (lockedTarget?.isForumEnabled == true) {
                "Đồng bộ 1-1 với Forum Topics trong ${lockedTarget.chatTitle}"
              } else {
                "Mỗi album ánh xạ một chủ đề lưu trữ riêng"
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
      )

      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 96.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
          .fillMaxSize()
          .testTag("albums_grid"),
      ) {
        if (lockedTarget == null) {
          item(span = { GridItemSpan(maxLineSpan) }, key = "albums_no_target") {
            TelePhotosGlassCard(
              modifier = Modifier.fillMaxWidth(),
              onClick = onOpenTargetSelector,
            ) {
              Column(modifier = Modifier.padding(18.dp)) {
                Text("Chưa khoá đích lưu trữ", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = "Hãy chọn và khoá một Nhóm Telegram, Kênh riêng tư hoặc Tin nhắn đã lưu để tạo Album.",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                  onClick = onOpenTargetSelector,
                  modifier = Modifier.testTag("albums_open_target_button"),
                ) {
                  Text("Chọn đích lưu trữ")
                }
              }
            }
          }
        } else if (albums.isEmpty()) {
          item(span = { GridItemSpan(maxLineSpan) }, key = "albums_empty_state") {
            TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
              Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
              ) {
                Icon(
                  imageVector = Icons.Filled.CollectionsBookmark,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(44.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text("Chưa có Album nào", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                  text = "Tạo album mới để phân loại ảnh và video theo chủ đề riêng (chỉ đồng bộ khi bạn bấm tải lên).",
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(14.dp))
                Button(
                  onClick = { showCreateDialog = true },
                  modifier = Modifier.testTag("empty_create_album_button"),
                ) {
                  Icon(Icons.Filled.Add, contentDescription = null)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Tạo album mới")
                }
              }
            }
          }
        } else {
          item(span = { GridItemSpan(maxLineSpan) }, key = "albums_section_title") {
            Text(
              text = "Album của bạn (${albums.size})",
              style = MaterialTheme.typography.titleSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              modifier = Modifier.padding(top = 4.dp),
            )
          }
          items(albums, key = { it.id }) { album ->
            TelePhotosGlassCard(
              onClick = { onAlbumClick(album.id) },
              modifier = Modifier.testTag("album_card_${album.id}"),
            ) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .aspectRatio(1.15f)
                  .background(Color(album.iconColor or 0x6FB9F0).copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
              ) {
                if (album.coverUri != null) {
                  AsyncImage(
                    model = album.coverUri,
                    contentDescription = album.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                  )
                } else {
                  Icon(
                    imageVector = Icons.Filled.Forum,
                    contentDescription = album.title,
                    tint = Color(album.iconColor or 0x6FB9F0).copy(alpha = 0.9f),
                    modifier = Modifier.size(38.dp),
                  )
                }
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = Color.Black.copy(alpha = 0.58f),
                  modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp),
                ) {
                  Text(
                    text = "Topic #${album.messageThreadId}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                  )
                }
              }
              Column(modifier = Modifier.padding(12.dp)) {
                Text(
                  text = album.title,
                  style = MaterialTheme.typography.titleSmall,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                  text = "${album.itemCount} ảnh & video",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }
        }

        // Dedicated "Đã xóa gần đây" card positioned cleanly at the bottom of the Album list
        item(span = { GridItemSpan(maxLineSpan) }, key = "albums_recently_deleted_footer") {
          RecentlyDeletedAlbumFooterCard(
            trashedCount = trashedCount,
            onClick = onOpenTrash,
          )
        }
      }
    }

    if (lockedTarget != null) {
      ExtendedFloatingActionButton(
        onClick = { showCreateDialog = true },
        icon = { Icon(Icons.Filled.Add, contentDescription = "Tạo album mới") },
        text = { Text("Tạo album") },
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(16.dp)
          .testTag("fab_create_album"),
      )
    }
  }

  if (showCreateDialog) {
    CreateAlbumDialog(
      onDismiss = { showCreateDialog = false },
      onConfirm = { title, color ->
        onCreateAlbum(title, color)
        showCreateDialog = false
      },
    )
  }
}

@Composable
private fun RecentlyDeletedAlbumFooterCard(
  trashedCount: Int,
  onClick: () -> Unit,
) {
  TelePhotosGlassCard(
    onClick = onClick,
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = 6.dp)
      .testTag("albums_trash_card"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween,
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.weight(1f),
      ) {
        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.72f),
          modifier = Modifier.size(40.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Filled.DeleteSweep,
              contentDescription = "Đã xóa gần đây",
              tint = MaterialTheme.colorScheme.onErrorContainer,
            )
          }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
          Text("Đã xóa gần đây", style = MaterialTheme.typography.titleMedium)
          Text(
            text = "Giữ 30 ngày trên máy • Không xoá khỏi Telegram",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
          shape = RoundedCornerShape(10.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
          Text(
            text = "$trashedCount mục",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
          )
        }
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
          imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
          contentDescription = "Mở Đã xóa gần đây",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun CreateAlbumDialog(
  onDismiss: () -> Unit,
  onConfirm: (String, Int) -> Unit,
) {
  var title by remember { mutableStateOf("") }
  var selectedColor by remember { mutableIntStateOf(TopicColors.first()) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Tạo Album / Forum Topic mới") },
    text = {
      Column {
        OutlinedTextField(
          value = title,
          onValueChange = { title = it },
          label = { Text("Tên album (ví dụ: Du lịch Đà Lạt)") },
          singleLine = true,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("album_name_input"),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("Màu biểu tượng chủ đề:", style = MaterialTheme.typography.labelMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TopicColors.forEach { hex ->
            val selected = hex == selectedColor
            Surface(
              onClick = { selectedColor = hex },
              shape = RoundedCornerShape(10.dp),
              color = Color(0xFF000000 or hex.toLong()),
              border = if (selected) {
                androidx.compose.foundation.BorderStroke(2.5.dp, MaterialTheme.colorScheme.onSurface)
              } else null,
              modifier = Modifier.size(32.dp),
              content = {},
            )
          }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
          text = "Lưu ý: Tạo chủ đề trên Telegram dùng kết nối ngắn hạn và tự ngắt ngay sau khi tạo xong.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    },
    confirmButton = {
      Button(
        onClick = { if (title.isNotBlank()) onConfirm(title, selectedColor) },
        enabled = title.isNotBlank(),
        modifier = Modifier.testTag("confirm_create_album_button"),
      ) {
        Text("Tạo Album")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Huỷ") }
    },
  )
}

/**
 * Album detail with strict segmented tab separation between Ảnh (tab 0) and Video (tab 1),
 * plus multi-select support to move selected items to "Đã xóa gần đây".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
  album: AlbumEntity?,
  photos: List<MediaEntity>,
  videos: List<MediaEntity>,
  onBack: () -> Unit,
  onMediaClick: (MediaEntity) -> Unit,
  onImportIntoAlbum: (List<android.net.Uri>) -> Unit,
  onMoveSelectedToTrash: (List<Long>) -> Unit = {},
  modifier: Modifier = Modifier,
) {
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
  val isSelectionMode = selectedIds.isNotEmpty()
  val activeList = if (selectedTabIndex == 0) photos else videos

  if (isSelectionMode) {
    BackHandler { selectedIds = emptySet() }
  }

  val picker = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.PickMultipleVisualMedia(50),
    onResult = { uris -> if (uris.isNotEmpty()) onImportIntoAlbum(uris) },
  )

  fun launchTabSpecificPicker() {
    val type = if (selectedTabIndex == 0) {
      ActivityResultContracts.PickVisualMedia.ImageOnly
    } else {
      ActivityResultContracts.PickVisualMedia.VideoOnly
    }
    picker.launch(PickVisualMediaRequest(type))
  }

  fun toggleItemSelection(id: Long) {
    selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
  }

  Box(modifier = modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      if (isSelectionMode) {
        TopAppBar(
          title = {
            Text(
              text = "Đã chọn ${selectedIds.size} mục",
              style = MaterialTheme.typography.titleMedium,
            )
          },
          navigationIcon = {
            IconButton(
              onClick = { selectedIds = emptySet() },
              modifier = Modifier.testTag("album_clear_selection_button"),
            ) {
              Icon(Icons.Filled.Close, contentDescription = "Bỏ chọn")
            }
          },
          actions = {
            IconButton(
              onClick = {
                onMoveSelectedToTrash(selectedIds.toList())
                selectedIds = emptySet()
              },
              modifier = Modifier.testTag("album_trash_selected_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.DeleteOutline,
                contentDescription = "Chuyển vào Đã xóa gần đây",
              )
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f),
          ),
        )
      } else {
        TopAppBar(
          title = {
            Column {
              Text(
                text = album?.title ?: "Chi tiết Album",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              if (album != null) {
                Text(
                  text = "Topic #${album.messageThreadId} • ${photos.size} ảnh • ${videos.size} video",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          },
          navigationIcon = {
            IconButton(onClick = onBack, modifier = Modifier.testTag("album_detail_back")) {
              Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
            }
          },
          colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        )
      }

      PrimaryTabRow(
        selectedTabIndex = selectedTabIndex,
        containerColor = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Tab(
          selected = selectedTabIndex == 0,
          onClick = { selectedTabIndex = 0 },
          text = { Text("Ảnh (${photos.size})") },
          modifier = Modifier.testTag("album_tab_photos"),
        )
        Tab(
          selected = selectedTabIndex == 1,
          onClick = { selectedTabIndex = 1 },
          text = { Text("Video (${videos.size})") },
          modifier = Modifier.testTag("album_tab_videos"),
        )
      }

      if (activeList.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
          contentAlignment = Alignment.Center,
        ) {
          TelePhotosGlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
              modifier = Modifier.padding(22.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Text(
                text = if (selectedTabIndex == 0) {
                  "Album này chưa có ảnh nào"
                } else {
                  "Album này chưa có video nào"
                },
                style = MaterialTheme.typography.titleMedium,
              )
              Spacer(modifier = Modifier.height(8.dp))
              Text(
                text = if (selectedTabIndex == 0) {
                  "Thêm ảnh vào album này để sẵn sàng tải lên đúng chủ đề Telegram khi bạn bấm đồng bộ."
                } else {
                  "Thêm video vào album này. Video chỉ hiển thị thời lượng và phát bằng Media3 khi bạn chạm mở."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.height(14.dp))
              Button(
                onClick = { launchTabSpecificPicker() },
                modifier = Modifier.testTag("album_add_media_button"),
              ) {
                Icon(
                  imageVector = if (selectedTabIndex == 0) Icons.Filled.AddPhotoAlternate else Icons.Filled.VideoCall,
                  contentDescription = null,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(if (selectedTabIndex == 0) "Thêm ảnh vào Album" else "Thêm video vào Album")
              }
            }
          }
        }
      } else {
        LazyVerticalGrid(
          columns = GridCells.Adaptive(minSize = 112.dp),
          contentPadding = PaddingValues(top = 6.dp, bottom = 96.dp),
          horizontalArrangement = Arrangement.spacedBy(3.dp),
          verticalArrangement = Arrangement.spacedBy(3.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          items(
            items = activeList,
            key = { it.id },
            contentType = { it.mediaType },
          ) { item ->
            MediaGridTile(
              media = item,
              isSelected = item.id in selectedIds,
              isSelectionMode = isSelectionMode,
              onClick = {
                if (isSelectionMode) {
                  toggleItemSelection(item.id)
                } else {
                  onMediaClick(item)
                }
              },
              onLongPress = { toggleItemSelection(item.id) },
            )
          }
        }
      }
    }

    if (!isSelectionMode) {
      ExtendedFloatingActionButton(
        onClick = { launchTabSpecificPicker() },
        icon = {
          Icon(
            imageVector = if (selectedTabIndex == 0) Icons.Filled.AddPhotoAlternate else Icons.Filled.VideoCall,
            contentDescription = null,
          )
        },
        text = { Text(if (selectedTabIndex == 0) "Thêm ảnh" else "Thêm video") },
        modifier = Modifier
          .align(Alignment.BottomEnd)
          .padding(16.dp)
          .testTag("fab_album_add_media"),
      )
    }
  }
}
