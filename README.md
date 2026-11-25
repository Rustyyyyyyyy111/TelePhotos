# Tele Photos — Đặc tả Kiến trúc & Ngữ cảnh Kỹ thuật (AI Context & Architecture Specification)

**Tele Photos** là ứng dụng thư viện ảnh & video kiêm sao lưu đám mây riêng tư cho Android, xây dựng bằng **Kotlin** và **Jetpack Compose** trên nền **Telegram TDLib**. Ứng dụng vận hành theo mô hình **Chỉ tải lên một chiều (Upload-Only) + Xem trực tuyến (Online Viewing) + Tải về từng tệp theo nhu cầu (On-Demand Single Download)**, **không** tải hàng loạt hai chiều về thiết bị và **không** sử dụng Firebase / Google Services.

Toàn bộ giao diện người dùng, hộp thoại thông báo và tên chủ đề (Forum Topics) trên Telegram đều bắt buộc sử dụng **Tiếng Việt**.

---

## 1. Tổng quan Dự án & Tech Stack

### Mục tiêu cốt lõi
- Gộp danh sách ảnh (`Ảnh`) và video (`Video`) từ thiết bị (`MediaStore`) và lịch sử đã sao lưu trên Telegram (chống trùng lặp theo `localUri`, `(mediaType, fileName, sizeBytes)`, `(chatId, messageId)` và prefix SHA-256).
- Tải lên thủ công theo hàng đợi tuần tự 1 luồng dưới dạng **Tệp gốc (`TdApi.InputMessageDocument`)** kèm ảnh thu nhỏ (`InputThumbnail <= 320px`) và caption tối giản 2 dòng (`🖼️/🎞️ <fileName>\n📅 dd/MM/yyyy HH:mm • #tele_anh [#h_<16hex>] [#trash]`), đọc kích thước/loại tệp/thời lượng/chủ đề trực tiếp từ đối tượng `MessageDocument`/`MessagePhoto`/`MessageVideo` của Telegram và vẫn tương thích ngược hoàn toàn với caption JSON `#tele_anh_meta` cũ.
- Quét và hiển thị cả ảnh/video có sẵn từ trước trên nhóm/kênh Telegram (`TdApi.MessagePhoto`, `TdApi.MessageVideo`, `TdApi.MessageDocument` kể cả khi không có caption metadata) vào cả tab `Ảnh` và tab `Album` (bao gồm cả chủ đề Forum **«Ảnh»** và **«Video»** bên cạnh các Album tuỳ chỉnh).
- Quản lý vòng đời kết nối **TDLib tái sử dụng theo Foreground/Background**: giữ một phiên `TdLibManager` duy nhất sống khi người dùng mở ứng dụng (loại bỏ chi phí kết nối lại liên tục), và tự động chạy `TdApi.OptimizeStorage` + `TdApi.Close()` khi ứng dụng chuyển sang chạy nền (sau khi mọi tác vụ/worker hoàn tất).
- Tự phục hồi lỗi `File not found` sau khi bị force-close / kill khỏi Recents: xác thực trạng thái `Ready` của TDLib và dung lượng tệp vật lý trên đĩa (> 0 byte), tự động dọn handle cache lỗi (`TdApi.DeleteFile`), truy xuất lại tin nhắn Telegram (`GetMessage` / lịch sử Topic) hoặc `GetRemoteFile` để làm mới `fileId`, cập nhật Room và tải lại sạch sẽ.
- Tối ưu hiệu năng cho máy cấu hình thấp (Dimensity giá rẻ), hỗ trợ chọn nhiều theo nhóm ngày kiểu Google Photos, zoom lưới tab `Ảnh` (`2–5` cột bằng chụm/mở 2 ngón hoặc nút chuyển mật độ), trình xem toàn màn hình hỗ trợ vuốt ngang theo đúng ngữ cảnh (`HorizontalPager`) kèm pinch-zoom / chạm đúp, reset trạng thái giao diện khi bị đóng hẳn khỏi Recents, và biểu tượng launcher 4 cánh chong chóng tối giản (hỗ trợ đổi 3 preset qua `<activity-alias>`).
- Tuân thủ chính sách **Chỉ đọc + Tải lên (`TelegramPolicyGuard`)**: tuyệt đối không gọi các hàm TDLib có khả năng xoá tin nhắn, lịch sử chat hoặc Forum Topic trên Telegram (`TdApi.DeleteFile` chỉ xoá tệp cache cục bộ trong `tdlib_files` để phục hồi tải xuống).

### Ngôn ngữ, Build & Thư viện (xác thực từ `gradle/libs.versions.toml` & `app/build.gradle.kts`)
- **Ngôn ngữ & SDK:** Kotlin `2.4.21`, Java `11` bytecode, Android Gradle Plugin `9.1.1`, `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36`.
- **Giao diện (UI):** Jetpack Compose BOM `2024.09.00`, Material 3, Navigation Compose `2.8.9`, Material Icons Extended.
- **Hiển thị Ảnh/Video:** Coil `2.7.0` (`coil-compose`, `coil-video`), Media3 ExoPlayer `1.11.1` (`androidx-media3-exoplayer`, `androidx-media3-ui`).
- **Lưu trữ & Tác vụ nền:**
  - Room `2.7.0` (`room-runtime`, `room-ktx`, KSP `2.3.5`) — CSDL cục bộ `tele_photos_vault.db` (Version `2`).
  - DataStore Preferences `1.1.7` (`tele_photos_settings`) — lưu cấu hình giao diện, Wi-Fi guard, API ID/Hash, thông tin phiên Telegram và dấu thời gian mở khoá `FLOOD_WAIT`.
  - WorkManager `2.12.0` (`work-runtime-ktx`) — thực thi hàng đợi upload tuần tự (`TelegramUploadWorker`) và đặt lịch chạy lại qua `initialDelay` cho `FLOOD_WAIT` dài.
  - Kotlin Coroutines `1.10.2` & `kotlinx-serialization-json` `1.13.0`.
- **Telegram Client:** TDLib Android `com.github.tdlibx:td:1.8.56` (tải qua JitPack trong `settings.gradle.kts`).
- **Secrets & Build Config:** `secrets-gradle-plugin` `2.0.1` đọc `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` từ `.env` / `.env.example` vào `BuildConfig` (người dùng cũng có thể nhập trực tiếp trên app nếu chưa cấu hình lúc build).
- **Kiểm thử:** JUnit `4.13.2`, Robolectric `4.16.1`, `androidx.test:core` `1.6.1`, `kotlinx-coroutines-test` `1.10.2`.

---

## 2. Bản đồ Thư mục & Tệp Cốt lõi

```text
├── .github/workflows/Main.yml            # CI GitHub Actions build app-debug.apk
├── gradle/libs.versions.toml             # Version Catalog toàn bộ thư viện & plugin
├── app/build.gradle.kts                  # Cấu hình Android app, signingConfigs, KSP, secrets
└── app/src/
    ├── main/java/com/laughing/telephotos/
    │   ├── TelePhotosApplication.kt      # DI singleton cấp Application + ActivityLifecycleCallbacks + cờ cold process launch + cấu hình Coil RGB565
    │   ├── MainActivity.kt               # Entry point edge-to-edge, reset savedInstanceState & selection khi bị kill từ Recents
    │   ├── core/
    │   │   ├── AppIconManager.kt         # Đổi preset launcher icon (DEFAULT / DARK / MONO) qua <activity-alias>
    │   │   ├── CacheCleanupManager.kt    # Giới hạn cache 500 MB LRU & dọn bộ nhớ đệm
    │   │   ├── Formatters.kt             # Định dạng ngày/dung lượng/thời lượng, chuẩn hoá +84 & tên Topic
    │   │   ├── MediaHashUtil.kt          # SHA-256 (lấy mẫu 16MB với tệp lớn), staging upload, thumbnail <=320px
    │   │   ├── MediaStoreScanner.kt      # Quét MediaStore, xin quyền, xoá hệ thống, lưu Pictures/Movies
    │   │   ├── NetworkRetryHelper.kt     # Retry mạng tạm thời (8s x 2 + 25s stall) & NetworkAlertCoordinator
    │   │   ├── SettingsPreferences.kt    # DataStore lưu AppSettings, AppIconPreset & unlockAtEpochMs cho FLOOD_WAIT
    │   │   ├── TelegramCaptionMetadata.kt# Tạo caption sạch 2 dòng & giải mã tương thích ngược JSON #tele_anh_meta cũ
    │   │   └── ThemeMode.kt              # Enum 3 chế độ giao diện: LIGHT, DARK, LIQUID_GLASS
    │   ├── data/
    │   │   ├── local/
    │   │   │   ├── TelePhotosDatabase.kt # Room v2 (entities + MIGRATION_1_2)
    │   │   │   ├── MediaEntity.kt        # Bảng media_items, MediaType, UploadStatus
    │   │   │   ├── MediaDao.kt           # Truy vấn Timeline, Queue, FreeUpSpace, PAUSED_FLOOD_WAIT
    │   │   │   ├── AlbumEntity.kt        # Bảng albums ánh xạ 1-1 Forum Topic
    │   │   │   ├── AlbumDao.kt           # Truy vấn danh sách Album kèm số lượng mục
    │   │   │   ├── SyncTargetEntity.kt   # Bảng sync_targets lưu đích đã khoá + threadId 3 chủ đề lõi
    │   │   │   └── SyncTargetDao.kt      # Lưu/cập nhật đích đồng bộ
    │   │   ├── telegram/
    │   │   │   ├── TdLibModels.kt        # Enum TelegramConnectionStatus, TdLibLifecycleState, FloodWaitState, FloodWaitException
    │   │   │   ├── TelegramPolicyGuard.kt# Chặn hàm Delete* trên Telegram, phân loại đích, tính buffer FLOOD_WAIT
    │   │   │   └── TdLibManager.kt       # Quản lý client TDLib đơn lệ, single-flight recovery, in-flight map
    │   │   └── repository/
    │   │       └── VaultRepository.kt    # Điều phối trung tâm Room <-> MediaStore <-> TdLibManager <-> WorkManager
    │   ├── worker/
    │   │   └── TelegramUploadWorker.kt   # Worker upload tuần tự 1 luồng + xử lý FLOOD_WAIT động
    │   └── ui/
    │       ├── components/               # MediaGridComponents.kt, TelePhotosSurfaces.kt
    │       ├── navigation/TelePhotosApp.kt # Bottom Bar / NavigationRail + điều hướng 4 tab & sub-screens
    │       ├── screens/                  # TimelineScreen, VideosScreen, AlbumsScreen, TrashScreen, SyncStorageScreen, TargetSelectorScreen, TelegramAuthScreen, MediaViewerScreen
    │       ├── theme/                    # Color.kt, Theme.kt, Type.kt
    │       └── viewmodel/TelePhotosViewModel.kt # StateFlow cho UI, đếm ngược FLOOD_WAIT, xử lý hành động người dùng
    └── test/java/com/laughing/telephotos/
        ├── ExampleUnitTest.kt            # Unit test cho metadata, policy guard, FLOOD_WAIT động, network retry
        └── ExampleRobolectricTest.kt     # Robolectric test cho Room v2 & luồng dữ liệu cục bộ
```

---

## 3. Luồng Logic Cốt lõi & Dữ liệu Đầu-Cuối

### A. Vòng đời `TdLibManager` (Foreground-Alive, Background-Close) & Single-Flight Recovery
1. **Khởi tạo & Theo dõi Foreground/Background:**
   - `TelePhotosApplication` đăng ký `ActivityLifecycleCallbacks` đếm `startedActivityCount` và bỏ qua biến động khi xoay màn hình (`activity.isChangingConfigurations`).
   - Khi app vào foreground (`0 -> 1`): gọi `tdLibManager.onAppForegrounded()`. Hàm này khôi phục trạng thái `FLOOD_WAIT` từ `SettingsPreferences` (nếu còn hạn) và nếu `isTelegramLoggedIn == true` + có mạng thì tự làm nóng phiên TDLib (`ensureConnected()`).
   - Khi app xuống background (`1 -> 0`): gọi `tdLibManager.onAppBackgrounded()`. Nếu `activeTransfers == 0 && activeWorkers == 0`, lập tức gọi `closeConnectionIdle()` (`TdApi.OptimizeStorage` + `TdApi.Close()` + dọn LRU cache). Nếu đang có worker hoặc download chạy, đặt cờ `closeWhenIdlePending = true` để tự đóng ngay khi tác vụ cuối cùng trả lease (`endTransfer()` / `endWorkLease()`).
2. **An toàn đồng thời & Theo dõi yêu cầu (`ensureConnected` & `sendFunction`):**
   - `lifecycleMutex` + `activeConnectDeferred` đảm bảo **single-flight**: nhiều coroutine gọi đồng thời sẽ chờ cùng một `CompletableDeferred`, không bao giờ tạo hai `Client` TDLib song song.
   - Mọi request đi qua `sendFunction` -> `sendSuspending` đều được cấp ID từ `AtomicLong` và lưu vào `ConcurrentHashMap<Long, CompletableDeferred<TdApi.Object>>` với `withTimeoutOrNull(timeoutMs)` (mặc định `30s`).
   - Nếu client đóng bất thường (`AuthorizationStateClosed` khi `isIntentionalShutdown == false`) hoặc lệnh gặp lỗi `"Client is closed"` / `"TDLib chưa sẵn sàng"`, `TdLibManager` tự phục hồi tối đa 1 lần qua `ensureConnected(forceReconnect = true)` (trừ `FLOOD_WAIT` — không bao giờ đóng/kết nối lại khi dính `FLOOD_WAIT`).

### B. Luồng Đồng bộ Thủ công, Quét Ảnh/Album Có Sẵn & Chống trùng lặp
1. **Hợp nhất nguồn & Chống trùng lặp:**
   - `VaultRepository.syncDeviceMediaStore()` quét ảnh/video qua `MediaStoreScanner` và gộp với bảng `media_items` theo `localUri` và `(mediaType, fileName.lowercase(), sizeBytes)`.
   - Khi quét lịch sử Telegram (`TdLibManager.scanHistoryReadOnly` phân trang `GetForumTopicHistory` / `GetChatHistory`), ứng dụng đọc cả `MessageDocument`, `MessagePhoto` và `MessageVideo` (kể cả ảnh có sẵn trên Telegram trước khi cài app), đối chiếu chống trùng lặp theo `(chatId, messageId)` và prefix SHA-256 (`#h_<16hex>`).
   - Khi `TelegramUploadWorker` chuẩn bị upload mục có hash tạm `ms_*`, `MediaHashUtil` sao chép URI ra staging file trong `cacheDir` và tính `SHA-256` thật. Nếu `mediaDao.findUploadedByHash(trueHash)` đã tồn tại, mục này được đánh dấu `UPLOADED` ngay lập tức (không upload trùng).
2. **Phân luồng Album / Forum Topics & Caption Sạch 2 Dòng:**
   - Nếu đích là Forum Group, `VaultRepository.resolveUploadDestination()` tự định tuyến: mục đã gán Album (`media.albumId`) -> ưu tiên `album.messageThreadId`; nếu chưa gán -> dùng **Đích tải lên mặc định** (`defaultUploadAlbumId` trong tab `Setting` nếu đã chọn); nếu để mặc định `0L` -> tự định tuyến ảnh vào topic **"Ảnh"** và video vào topic **"Video"** (tạo an toàn qua `topicMutexFor(chatId)` chuẩn hoá dấu tiếng Việt và tự tạo thẻ Album tương ứng để đếm `COUNT(DISTINCT m.id)` ngay lập tức trong tab `Album`).
   - `AlbumDao.observeAlbumsWithCount()` và `MediaDao.observeAlbumPhotos` / `observeAlbumVideos` hiển thị cả hai thẻ chủ đề **"Ảnh"** và **"Video"** bên cạnh các Album tuỳ chỉnh (chỉ ẩn **"Đã Xoá Gần Đây"** xuống thẻ footer thùng rác), khớp cả `albumId` lẫn `topicId`.
   - Nếu đích là Kênh/Nhóm thường (`!isForumEnabled`), `lockSyncTarget` / `syncAlbumsFromTelegram` tự tạo Album đại diện (`messageThreadId = 0L`) để hiển thị toàn bộ ảnh/video trên kênh/nhóm trong tab `Album`.
   - `TdLibManager.sendDocumentOriginal()` gửi `TdApi.InputMessageDocument` (`disableContentTypeDetection = true`) kèm `InputThumbnail` (`<= 320px`) và caption sạch tối đa 2 dòng từ `TelegramCaptionMetadata.buildCaption()` (không nhúng chuỗi JSON thô). Nếu upload bị rớt mạng giữa chừng, giữ lại `pendingMessageId` và kiểm tra qua `GetMessage` / `ResendMessages` khi thử lại để **không gửi trùng tin nhắn**.
   - Giữa các tệp tuần tự có độ trễ chống khoá tài khoản `2.0–3.5 giây` (`uploadPacingMs + random(0..1500ms)`).

### C. Xử lý `FLOOD_WAIT` Động vs. Lỗi mạng tạm thời (`8s x 2`)
Hệ thống tách bạch hoàn toàn hai cơ chế này:
1. **Lỗi mạng tạm thời (chỉ cho rớt mạng / stall `25s`):**
   - `NetworkRetryPolicy.isTransientNetworkError()` **loại trừ** `FloodWaitException`, chuỗi `"FLOOD_WAIT"` / `"Too Many Requests"` / `"retry after"` và `SecurityException`.
   - Chỉ khi gặp mất kết nối / stall `25s` khi upload hoặc download, `NetworkRetryTracker` mới chờ `8 giây x 2 lần`, và báo hộp thoại `NetworkInterruptionAlert` ở lần lỗi liên tiếp thứ 3.
2. **Xử lý `FLOOD_WAIT` động theo đúng thời gian Telegram trả về:**
   - `TelegramPolicyGuard.parseFloodWaitSeconds()` trích xuất chính xác `retryAfterSec` (từ `FLOOD_WAIT_<N>`, `retry after <N>` hoặc mã lỗi `429`).
   - Áp dụng **buffer an toàn phân tầng** (`computeFloodWaitBufferSeconds`):
     - `retryAfterSec <= 30s` -> `+3s`
     - `31s..300s` -> `+5s`
     - `> 300s` -> `+10s`
   - Tính `unlockAtEpochMs = now + (retryAfterSec + bufferSec) * 1000L`, cập nhật đồng thời vào `_floodWaitState` (`StateFlow`), trạng thái `TelegramConnectionStatus.FLOOD_WAIT` / `TdLibLifecycleState.FLOOD_WAIT`, và lưu bền vững vào `SettingsPreferences` (`DataStore`) để sống sót qua khởi động lại app hoặc Worker.
   - **Giữ nguyên phiên TDLib ở trạng thái nhàn rỗi (idle/alive)** — tuyệt đối không `Close()` hay ép kết nối lại; lập tức khoá hàng đợi (`PAUSED_FLOOD_WAIT` trong Room) để không gửi thêm bất kỳ request upload nào khi `System.currentTimeMillis() < unlockAtEpochMs`.
   - Trong `TelegramUploadWorker`:
     - Nếu `retryAfterSec <= 60s` (hoặc thời gian còn lại `<= 65s`): Worker tạm hoãn (`delay`) ngay trong worker, cập nhật tiến độ đếm ngược; hết giờ sẽ xoá cờ `FLOOD_WAIT`, chuyển `PAUSED_FLOOD_WAIT` -> `QUEUED` và **tải tiếp đúng tệp đang dở đầu tiên** với nhịp `2.0–3.5s`.
     - Nếu `retryAfterSec > 60s`: gọi `VaultRepository.scheduleUploadAfterFloodWait(remainingMs, filterMediaType)` để lên lịch `WorkManager` với `setInitialDelay(remainingMs, TimeUnit.MILLISECONDS)` và thoát Worker sạch sẽ để không giữ wake lock.

### D. Xem trực tuyến Tự phục hồi, Zoom Lưới Ảnh `2–5` Cột & Tối ưu Thiết bị Yếu
- **Xem trực tuyến & Tự phục hồi `File not found` (`MediaViewerScreen` + `TdLibManager.downloadMediaOnDemand`):**
  - Mọi đường dẫn `localUri` và `cachedLocalVideoPath` đều được kiểm tra thực tế trên đĩa (`MediaStoreScanner.isLocalUriAccessible`, `MediaStoreScanner.isUsableLocalFile` > 0 byte); cache hỏng/bị dọn sau khi kill app sẽ tự được xoá khỏi Room (`clearCachedVideoPath`).
  - Khi tải từ Telegram, `TdLibManager.downloadMediaOnDemand(media)` luôn chờ `ensureConnected()` (`AuthorizationStateReady`), kiểm tra `GetFile` và dọn bản ghi cache TDLib sai lệch (`TdApi.DeleteFile`). Nếu gặp `File not found` do `telegramFileId` cũ hết hiệu lực giữa các lần khởi động lại TDLib, ứng dụng tự động làm mới liên kết qua `GetMessage` / `GetForumTopicHistory` / `GetChatHistory` (`refreshMediaSourceFromMessage`) hoặc `GetRemoteFile`, cập nhật `telegramFileId` mới vào Room (`updateTelegramFileRefs`) và tải lại hoàn chỉnh (giới hạn cache **500 MB** LRU qua `CacheCleanupManager`).
- **Vuốt ngang theo ngữ cảnh & Pinch-Zoom trong Viewer:** `MediaViewerScreen` dùng `HorizontalPager` theo đúng `MediaCollectionScope` (`PHOTOS`, `VIDEOS`, `ALBUM_PHOTOS`, `ALBUM_VIDEOS`, `TRASH`). Ảnh hỗ trợ chụm/mở 2 ngón (`1x..4x`), chạm đúp (`1x <-> 2.5x`), kéo xem vùng phóng to; tự khoá vuốt pager khi đang zoom (`scale > 1.02f`) và tạm dừng `ExoPlayer` ở các trang video không active.
- **Zoom lưới tab `Ảnh` (`2–5` cột):** Người dùng có thể chụm/mở 2 ngón trực tiếp trên lưới `ChronologicalMediaGrid` (bộ bắt cử chỉ 2 ngón tại `PointerEventPass.Initial` không cản trở cuộn dọc/chọn ảnh) hoặc bấm nút chuyển mật độ trên `TopAppBar`; cấu hình `photoGridColumns` được lưu bền vững trong `SettingsPreferences`.
- **Tab `Setting` tinh gọn:** Đã loại bỏ danh sách hàng đợi chi tiết dài dòng (vẫn giữ đầy đủ các bộ đếm tổng quan như *Tổng mục*, *Đã tải lên*, *Đang chờ*, *Thùng rác*) và bổ sung mục chọn gọn **"Đích tải lên mặc định"** (`defaultUploadAlbumId`).
- **Chọn nhiều & Reset khi bị kill từ Recents:** Hỗ trợ nhấn giữ từng ảnh hoặc chạm nút tròn ở tiêu đề ngày để chọn/bỏ chọn cả nhóm ngày (`toggleDateGroupSelection`), kèm thanh hành động `X` (`clearSelection`) ở trên cùng. Khi app bị tắt hẳn khỏi Recents (`!app.hasInitializedUiInCurrentProcess`), `MainActivity` loại bỏ `savedInstanceState` cũ để mở sạch lại tab `Ảnh` và xoá trạng thái chọn tạm thời (vẫn giữ trạng thái khi chỉ xoay màn hình).
- **Tối ưu Dimensity cấu hình thấp:** Dùng `android:windowSoftInputMode="adjustResize"` + `imePadding()`, tắt animation chuyển tab `NavHost` (`EnterTransition.None`/`ExitTransition.None`), loại bỏ `Modifier.blur` tốn GPU, gom nhóm timeline trên `Dispatchers.Default`, tái sử dụng `SimpleDateFormat`/`Calendar`, dùng `rememberUpdatedState` trong `MediaGridTile` và decode thumbnail Coil `256px` `RGB_565`.
- **Biểu tượng Launcher & Chuyển Preset:** Icon thích ứng hình chong chóng 4 cánh tối giản kèm 3 `<activity-alias>` (`.LauncherIconDefault`, `.LauncherIconDark`, `.LauncherIconMono`) có thể chuyển đổi trong tab `Setting`.
- **Tải về máy & Giải phóng dung lượng:** Nút **"Tải về máy"** lưu bản gốc vào `Pictures/Tele Photos` hoặc `Movies/Tele Photos` (cũng sử dụng chung luồng tự phục hồi `downloadMediaOnDemand(media)`). Chỉ giải phóng mục đã sao lưu (`UploadStatus.UPLOADED`); xoá mục chỉ xoá cục bộ và gắn cờ `#trash` / chuyển tiếp sang **"Đã Xoá Gần Đây"** (30 ngày).

---

## 4. Các Bất biến Nghiêm ngặt & Ràng buộc Kỹ thuật (DO NOT BREAK)

1. **Chính sách Chỉ đọc + Tải lên (`TelegramPolicyGuard.assertAllowedFunction`):**
   - Mọi `TdApi.Function<*>` gửi qua `TdLibManager` (`sendFunction`, `sendSuspending`, `applyTdlibParameters`, `GetMe`, `DownloadFile`, `Close`) **BẮT BUỘC** đi qua `TelegramPolicyGuard.assertAllowedFunction(function)`. Nghiêm cấm xoá hoặc vô hiệu hoá bộ lọc chặn `DeleteMessages`, `DeleteChatHistory`, `DeleteForumTopic`, `DeleteChat`.
2. **Không tải hàng loạt tự động & Không gắn Firebase:**
   - Không thêm `google-services` / Firebase dependencies vào `app/build.gradle.kts` (CI `.github/workflows/Main.yml` cũng chủ động loại bỏ `google-services`).
   - Không tự động tải tệp gốc từ Telegram khi quét lịch sử (`scanHistoryReadOnly` chỉ tải metadata & thumbnail nhỏ `downloadThumbnailQuick`).
3. **Bất biến Room Schema v2:**
   - Giữ nguyên `TelePhotosDatabase` `version = 2` và `MIGRATION_1_2` (`photosThreadId`, `videosThreadId` trong `sync_targets`). Mọi thay đổi schema bắt buộc phải tăng version và viết `Migration` mới.
4. **Ràng buộc Test & Trạng thái UI:**
   - `TelegramConnectionStatus.DISCONNECTED_IDLE.labelVi` phải giữ chuỗi con `"Đã ngắt kết nối"` (được kiểm tra bởi `ExampleUnitTest.kt`).
   - Không dùng `NetworkRetryTracker` (`8s x 2`) cho `FloodWaitException`.
5. **Tên Topic chuẩn hoá & Tiếng Việt tuyệt đối:**
   - Không tạo album tuỳ chỉnh trùng với 3 topic hệ thống (`Ảnh`, `Video`, `Đã Xoá Gần Đây` sau chuẩn hoá `Formatters.normalizeTopicKey`).
6. **Bảo toàn Signing & Build Config:**
   - Không xoá hoặc sửa `signingConfigs` (`release`, `debugConfig`), `applicationId = "com.laughing.telephotos"` trong `app/build.gradle.kts` và không tạo `local.properties` trong repo.

---

## 5. Hướng dẫn Chỉnh sửa & Mở rộng An toàn

- **Thêm hàm TDLib mới:** Thêm phương thức trong `TdLibManager.kt` và gọi thông qua `sendFunction(...)` bên trong `withActiveSession { ... }`. Điều này tự động hưởng single-flight reconnect, timeout, tracking trong `inFlightRequests`, `TelegramPolicyGuard` và cập nhật trạng thái `FLOOD_WAIT` động.
- **Thay đổi hành vi hàng đợi Upload:** Cập nhật `MediaDao.kt` (giữ thứ tự ưu tiên `QUEUED`), `VaultRepository.startManualUploadBatch()` và `TelegramUploadWorker.kt` đồng thời; luôn bảo đảm giữ `PendingUploadInterruptedException` (`pendingMessageId`) để không gửi trùng tin nhắn Telegram.
- **Cạm bẫy cần tránh:**
  - Không gọi `tdLibManager.closeConnectionIdle()` ngay sau mỗi tác vụ khi app đang ở foreground (`appInForeground == true`), vì sẽ phá vỡ kiến trúc phiên tái sử dụng.
  - Không bỏ kiểm tra `activity.isChangingConfigurations` trong `TelePhotosApplication` nếu không muốn xoay màn hình đóng nhầm TDLib.
  - Không thay `InputMessageDocument` bằng `InputMessagePhoto`/`InputMessageVideo` vì Telegram sẽ nén giảm chất lượng ảnh/video gốc.

---

## 6. Build, Kiểm thử & Triển khai GitHub

### Chạy Test & Build cục bộ
- **Trong môi trường container AI Studio (không dùng `./gradlew`):**
  ```bash
  gradle :app:testDebugUnitTest
  gradle :app:assembleDebug
  ```
- **Trên máy cá nhân / Linux / macOS có Gradle Wrapper:**
  ```bash
  ./gradlew :app:testDebugUnitTest
  ./gradlew :app:assembleDebug
  ```
- **Cấu hình API mặc định (tuỳ chọn):** Tạo tệp `.env` ở thư mục gốc (không commit lên Git) với `TELEGRAM_API_ID` và `TELEGRAM_API_HASH` từ [my.telegram.org](https://my.telegram.org), hoặc nhập trực tiếp tại màn hình đăng nhập Telegram trong app.

### Cập nhật lên GitHub & Kiểm tra GitHub Actions
1. Kiểm tra trạng thái và đẩy mã nguồn lên nhánh `main` (hoặc `master`):
   ```bash
   git status
   git add .
   git commit -m "Upgrade persistent TDLib session and dynamic FLOOD_WAIT handling"
   git push origin main
   ```
2. Kiểm tra build tự động trên GitHub Actions (`.github/workflows/Main.yml`):
   - Mở repository trên GitHub -> chọn tab **Actions** -> chọn workflow **Build Android APK**.
   - Theo dõi job `build` (chạy `./gradlew assembleDebug` với JDK 17).
   - Khi job báo thành công (dấu tích xanh), cuộn xuống mục **Artifacts** ở cuối trang run để tải tệp APK **`app-debug`**.
