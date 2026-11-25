package com.laughing.telephotos.ui.navigation

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.laughing.telephotos.core.MediaStoreScanner
import com.laughing.telephotos.data.local.MediaType
import com.laughing.telephotos.ui.screens.AlbumDetailScreen
import com.laughing.telephotos.ui.screens.AlbumsScreen
import com.laughing.telephotos.ui.screens.MediaViewerScreen
import com.laughing.telephotos.ui.screens.SyncStorageScreen
import com.laughing.telephotos.ui.screens.TargetSelectorScreen
import com.laughing.telephotos.ui.screens.TelegramAuthScreen
import com.laughing.telephotos.ui.screens.TimelineScreen
import com.laughing.telephotos.ui.screens.TrashScreen
import com.laughing.telephotos.ui.screens.VideosScreen
import com.laughing.telephotos.ui.theme.LocalTelePhotosTokens
import com.laughing.telephotos.ui.viewmodel.TelePhotosViewModel
import kotlinx.serialization.Serializable

@Serializable data object TimelineRoute
@Serializable data object AlbumsRoute
@Serializable data object VideosRoute
@Serializable data object SyncStorageRoute
@Serializable data object TrashRoute
@Serializable data class AlbumDetailRoute(val albumId: Long)
@Serializable data class MediaViewerRoute(val mediaId: Long)
@Serializable data object TargetSelectorRoute
@Serializable data object TelegramAuthRoute

private data class TopDestination(
  val route: Any,
  val labelVi: String,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

private val TopDestinations = listOf(
  TopDestination(
    route = TimelineRoute,
    labelVi = "Ảnh",
    selectedIcon = Icons.Filled.PhotoLibrary,
    unselectedIcon = Icons.Outlined.PhotoLibrary,
    testTag = "nav_tab_photos",
  ),
  TopDestination(
    route = AlbumsRoute,
    labelVi = "Album",
    selectedIcon = Icons.Filled.CollectionsBookmark,
    unselectedIcon = Icons.Outlined.CollectionsBookmark,
    testTag = "nav_tab_albums",
  ),
  TopDestination(
    route = VideosRoute,
    labelVi = "Video",
    selectedIcon = Icons.Filled.VideoLibrary,
    unselectedIcon = Icons.Outlined.VideoLibrary,
    testTag = "nav_tab_videos",
  ),
  TopDestination(
    route = SyncStorageRoute,
    labelVi = "Setting",
    selectedIcon = Icons.Filled.Settings,
    unselectedIcon = Icons.Outlined.Settings,
    testTag = "nav_tab_settings",
  ),
)

@Composable
fun TelePhotosApp(viewModel: TelePhotosViewModel) {
  val context = LocalContext.current
  val navController = rememberNavController()
  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentDestination = navBackStackEntry?.destination
  val snackbarHostState = remember { SnackbarHostState() }

  val settings by viewModel.settings.collectAsStateWithLifecycle()
  val authState by viewModel.authState.collectAsStateWithLifecycle()
  val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
  val lockedTarget by viewModel.lockedTarget.collectAsStateWithLifecycle()
  val photoSections by viewModel.photosTimelineSections.collectAsStateWithLifecycle()
  val videoSections by viewModel.videosTimelineSections.collectAsStateWithLifecycle()
  val albums by viewModel.albums.collectAsStateWithLifecycle()
  val trashedMedia by viewModel.trashedMedia.collectAsStateWithLifecycle()
  val uploadQueue by viewModel.uploadQueue.collectAsStateWithLifecycle()
  val storageStats by viewModel.storageStats.collectAsStateWithLifecycle()
  val selectedIds by viewModel.selectedIds.collectAsStateWithLifecycle()
  val candidateTargets by viewModel.candidateTargets.collectAsStateWithLifecycle()
  val isLoadingTargets by viewModel.isLoadingTargets.collectAsStateWithLifecycle()
  val isBusy by viewModel.isBusy.collectAsStateWithLifecycle()
  val statusBanner by viewModel.statusBanner.collectAsStateWithLifecycle()
  val floodWaitRemainingSec by viewModel.floodWaitRemainingSec.collectAsStateWithLifecycle()
  val manualSyncProgress by viewModel.manualSyncProgress.collectAsStateWithLifecycle()
  val networkInterruptionAlert by viewModel.networkInterruptionAlert.collectAsStateWithLifecycle()
  val hasPromptedMediaPermission by viewModel.hasPromptedMediaPermission.collectAsStateWithLifecycle()
  val cacheSizeBytes by viewModel.cacheSizeBytes.collectAsStateWithLifecycle()
  val pendingSystemUriRequest by viewModel.pendingSystemUriRequest.collectAsStateWithLifecycle()

  val systemDeleteLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartIntentSenderForResult(),
    onResult = { activityResult ->
      val pending = pendingSystemUriRequest
      if (pending != null) {
        if (activityResult.resultCode == Activity.RESULT_OK) {
          viewModel.completeSystemUriRequest(pending)
        } else {
          viewModel.cancelSystemUriRequest()
        }
      }
    },
  )

  LaunchedEffect(pendingSystemUriRequest) {
    val req = pendingSystemUriRequest ?: return@LaunchedEffect
    runCatching {
      systemDeleteLauncher.launch(IntentSenderRequest.Builder(req.intentSender).build())
    }.onFailure {
      viewModel.cancelSystemUriRequest()
    }
  }

  LaunchedEffect(statusBanner) {
    val msg = statusBanner
    if (!msg.isNullOrBlank()) {
      snackbarHostState.showSnackbar(msg)
      viewModel.dismissBanner()
    }
  }

  val isTopLevelRoute = currentDestination?.let { dest ->
    dest.hasRoute<TimelineRoute>() ||
      dest.hasRoute<AlbumsRoute>() ||
      dest.hasRoute<VideosRoute>() ||
      dest.hasRoute<SyncStorageRoute>()
  } ?: true

  val tokens = LocalTelePhotosTokens.current

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useNavigationRail = maxWidth >= 600.dp && isTopLevelRoute

    Scaffold(
      containerColor = Color.Transparent,
      contentWindowInsets = WindowInsets(0, 0, 0, 0),
      snackbarHost = {
        SnackbarHost(
          hostState = snackbarHostState,
          modifier = Modifier.navigationBarsPadding(),
        )
      },
      bottomBar = {
        if (isTopLevelRoute && !useNavigationRail) {
          NavigationBar(
            containerColor = if (tokens.isGlass) tokens.cardSurface else MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
          ) {
            TopDestinations.forEach { dest ->
              val selected = currentDestination?.hasRoute(dest.route::class) == true
              NavigationBarItem(
                selected = selected,
                onClick = {
                  viewModel.clearSelection()
                  navController.navigate(dest.route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                      saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                  }
                },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = dest.labelVi,
                  )
                },
                label = { Text(dest.labelVi) },
                modifier = Modifier.testTag(dest.testTag),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        if (useNavigationRail) {
          NavigationRail(
            containerColor = if (tokens.isGlass) tokens.cardSurface else MaterialTheme.colorScheme.surface,
          ) {
            TopDestinations.forEach { dest ->
              val selected = currentDestination?.hasRoute(dest.route::class) == true
              NavigationRailItem(
                selected = selected,
                onClick = {
                  viewModel.clearSelection()
                  navController.navigate(dest.route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                      saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                  }
                },
                icon = {
                  Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.unselectedIcon,
                    contentDescription = dest.labelVi,
                  )
                },
                label = { Text(dest.labelVi) },
                modifier = Modifier.testTag(dest.testTag),
              )
            }
          }
        }

        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
          NavHost(
            navController = navController,
            startDestination = TimelineRoute,
          ) {
            composable<TimelineRoute> {
              val hasPhotoPerm = MediaStoreScanner.hasMediaReadPermission(context, MediaType.PHOTO)
              TimelineScreen(
                sections = photoSections,
                selectedIds = selectedIds,
                albums = albums,
                lockedTarget = lockedTarget,
                settings = settings,
                authState = authState,
                connectionStatus = connectionStatus,
                floodWaitRemainingSec = floodWaitRemainingSec,
                manualSyncProgress = manualSyncProgress,
                hasMediaPermission = hasPhotoPerm,
                hasPromptedPermission = hasPromptedMediaPermission,
                onPermissionPrompted = viewModel::markMediaPermissionPrompted,
                onDevicePermissionGranted = viewModel::syncDevicePhotos,
                onMediaClick = { media -> navController.navigate(MediaViewerRoute(media.id)) },
                onToggleSelection = viewModel::toggleSelection,
                onClearSelection = viewModel::clearSelection,
                onImportPickedPhotos = { uris -> viewModel.importSelectedMedia(uris, null) },
                onUploadSelectedItems = viewModel::uploadSelectedItemsNow,
                onFreeUpSelectedSpace = viewModel::requestFreeUpSpaceSelected,
                onMoveSelectedToTrash = viewModel::moveSelectedToTrash,
                onAssignSelectedToAlbum = viewModel::assignSelectedToAlbum,
                onStopManualSync = viewModel::stopManualSync,
                onOpenTargetSelector = { navController.navigate(TargetSelectorRoute) },
                onOpenTelegramAuth = { navController.navigate(TelegramAuthRoute) },
                onSyncTab = { viewModel.startTabSync(MediaType.PHOTO) },
              )
            }

            composable<AlbumsRoute> {
              AlbumsScreen(
                albums = albums,
                trashedCount = trashedMedia.size,
                lockedTarget = lockedTarget,
                onAlbumClick = { albumId -> navController.navigate(AlbumDetailRoute(albumId)) },
                onOpenTrash = { navController.navigate(TrashRoute) },
                onCreateAlbum = viewModel::createAlbum,
                onOpenTargetSelector = { navController.navigate(TargetSelectorRoute) },
                onPurgeExpiredTrash = viewModel::purgeExpiredTrash,
              )
            }

            composable<VideosRoute> {
              val hasVideoPerm = MediaStoreScanner.hasMediaReadPermission(context, MediaType.VIDEO)
              VideosScreen(
                sections = videoSections,
                selectedIds = selectedIds,
                albums = albums,
                lockedTarget = lockedTarget,
                settings = settings,
                authState = authState,
                floodWaitRemainingSec = floodWaitRemainingSec,
                manualSyncProgress = manualSyncProgress,
                hasMediaPermission = hasVideoPerm,
                hasPromptedPermission = hasPromptedMediaPermission,
                onPermissionPrompted = viewModel::markMediaPermissionPrompted,
                onDevicePermissionGranted = viewModel::syncDeviceVideos,
                onMediaClick = { media -> navController.navigate(MediaViewerRoute(media.id)) },
                onToggleSelection = viewModel::toggleSelection,
                onClearSelection = viewModel::clearSelection,
                onImportPickedVideos = { uris -> viewModel.importSelectedMedia(uris, null) },
                onUploadSelectedItems = viewModel::uploadSelectedItemsNow,
                onFreeUpSelectedSpace = viewModel::requestFreeUpSpaceSelected,
                onMoveSelectedToTrash = viewModel::moveSelectedToTrash,
                onAssignSelectedToAlbum = viewModel::assignSelectedToAlbum,
                onStopManualSync = viewModel::stopManualSync,
                onOpenTargetSelector = { navController.navigate(TargetSelectorRoute) },
                onOpenTelegramAuth = { navController.navigate(TelegramAuthRoute) },
                onSyncTab = { viewModel.startTabSync(MediaType.VIDEO) },
              )
            }

            composable<AlbumDetailRoute> { backStackEntry ->
              val route = backStackEntry.toRoute<AlbumDetailRoute>()
              val album by viewModel.observeAlbum(route.albumId)
                .collectAsStateWithLifecycle(initialValue = null)
              val albumPhotos by viewModel.observeAlbumPhotos(route.albumId)
                .collectAsStateWithLifecycle(initialValue = emptyList())
              val albumVideos by viewModel.observeAlbumVideos(route.albumId)
                .collectAsStateWithLifecycle(initialValue = emptyList())
              AlbumDetailScreen(
                album = album,
                photos = albumPhotos,
                videos = albumVideos,
                onBack = { navController.popBackStack() },
                onMediaClick = { media -> navController.navigate(MediaViewerRoute(media.id)) },
                onImportIntoAlbum = { uris -> viewModel.importSelectedMedia(uris, route.albumId) },
                onMoveSelectedToTrash = viewModel::moveMediaIdsToTrash,
              )
            }

            composable<TrashRoute> {
              TrashScreen(
                trashedMedia = trashedMedia,
                onBack = { navController.popBackStack() },
                onRestoreItem = viewModel::restoreSingleFromTrash,
                onPermanentDeleteItem = viewModel::permanentlyDeleteFromTrash,
                onPermanentDeleteAll = viewModel::permanentlyDeleteAllTrash,
                onOpenMedia = { media -> navController.navigate(MediaViewerRoute(media.id)) },
                onPurgeExpired = viewModel::purgeExpiredTrash,
              )
            }

            composable<SyncStorageRoute> {
              SyncStorageScreen(
                settings = settings,
                authState = authState,
                connectionStatus = connectionStatus,
                lockedTarget = lockedTarget,
                storageStats = storageStats,
                uploadQueue = uploadQueue,
                floodWaitRemainingSec = floodWaitRemainingSec,
                manualSyncProgress = manualSyncProgress,
                cacheSizeBytes = cacheSizeBytes,
                isBusy = isBusy,
                onSelectThemeMode = viewModel::setThemeMode,
                onToggleAdvancedBlur = viewModel::setAdvancedBlurEnabled,
                onToggleWifiOnly = viewModel::setWifiOnlyUpload,
                onOpenTelegramAuth = { navController.navigate(TelegramAuthRoute) },
                onSwitchApiCredentials = {
                  viewModel.switchApiCredentials()
                  navController.navigate(TelegramAuthRoute)
                },
                onOpenTargetSelector = { navController.navigate(TargetSelectorRoute) },
                onSyncHistoryReadOnly = viewModel::syncHistoryReadOnly,
                onStartManualUploadBatch = viewModel::startManualUploadBatch,
                onStopManualSync = viewModel::stopManualSync,
                onFreeUpAllSynced = viewModel::requestFreeUpAllSynced,
                onClearCache = viewModel::clearTemporaryCache,
                onRefreshCacheSize = viewModel::refreshCacheSize,
              )
            }

            composable<TargetSelectorRoute> {
              TargetSelectorScreen(
                settings = settings,
                authState = authState,
                lockedTarget = lockedTarget,
                candidates = candidateTargets,
                isLoading = isLoadingTargets,
                onBack = { navController.popBackStack() },
                onRefreshTargets = viewModel::refreshAllowedTargets,
                onConfirmLockTarget = { candidate ->
                  viewModel.lockSyncTarget(candidate) {
                    navController.popBackStack()
                  }
                },
                onOpenTelegramAuth = { navController.navigate(TelegramAuthRoute) },
              )
            }

            composable<TelegramAuthRoute> {
              TelegramAuthScreen(
                settings = settings,
                authState = authState,
                lockedTarget = lockedTarget,
                isBusy = isBusy,
                onEnsureTelegramInitialized = viewModel::ensureTelegramInitialized,
                onCloseTelegramIdle = viewModel::closeTelegramIdle,
                onSaveApiCredentials = viewModel::saveApiCredentials,
                onSwitchApiCredentials = viewModel::switchApiCredentials,
                onReturnToPhoneNumber = viewModel::returnToPhoneNumberStep,
                onBack = { navController.popBackStack() },
                onSubmitPhone = viewModel::submitPhoneNumber,
                onSubmitCode = viewModel::submitAuthCode,
                onSubmitPassword = viewModel::submitPassword,
                onLogout = viewModel::logoutTelegram,
                onAuthReadyNavigate = {
                  if (lockedTarget == null) {
                    navController.navigate(TargetSelectorRoute) {
                      popUpTo<TelegramAuthRoute> { inclusive = true }
                      launchSingleTop = true
                    }
                  } else {
                    navController.navigate(TimelineRoute) {
                      popUpTo<TimelineRoute> { inclusive = false }
                      launchSingleTop = true
                    }
                  }
                },
              )
            }

            composable<MediaViewerRoute> { backStackEntry ->
              val route = backStackEntry.toRoute<MediaViewerRoute>()
              val media by viewModel.observeMediaById(route.mediaId)
                .collectAsStateWithLifecycle(initialValue = null)
              MediaViewerScreen(
                media = media,
                onBack = { navController.popBackStack() },
                onMoveToTrash = viewModel::moveSingleToTrash,
                onRestoreFromTrash = viewModel::restoreSingleFromTrash,
                onDownloadVideoOnDemand = viewModel::downloadVideoOnDemand,
                onSaveDownloadedVideoPath = viewModel::saveDownloadedVideoPath,
                onSaveToDevice = viewModel::saveMediaToDeviceGallery,
                onFreeUpSpace = viewModel::requestFreeUpSpaceSingle,
                onNotifyMessage = viewModel::showBanner,
              )
            }
          }
        }
      }
    }

    networkInterruptionAlert?.let { alert ->
      AlertDialog(
        onDismissRequest = viewModel::dismissNetworkInterruptionAlert,
        title = { Text(alert.titleVi) },
        text = { Text(alert.messageVi) },
        confirmButton = {
          Button(
            onClick = viewModel::dismissNetworkInterruptionAlert,
            modifier = Modifier.testTag("network_alert_confirm_button"),
          ) {
            Text(alert.confirmButtonVi)
          }
        },
      )
    }
  }
}
