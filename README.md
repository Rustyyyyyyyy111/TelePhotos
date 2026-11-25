# Tele Photos

Ứng dụng thư viện ảnh & sao lưu đám mây cho Android xây dựng bằng **Kotlin**, **Jetpack Compose** và **Telegram TDLib**, hoạt động theo mô hình **Chỉ tải lên (Upload-Only) + Xem trực tuyến + Tải về theo nhu cầu**.

---

## Tính năng chính

- **Thư viện hợp nhất Ảnh & Video (`Ảnh`, `Video`):**
  - Tự động đọc ảnh/video trên thiết bị qua `MediaStore` và gộp với dữ liệu đã sao lưu từ Telegram (chống trùng lặp theo `localUri`, `SHA-256` và cặp tên tệp + kích thước).
  - Hiển thị huy hiệu trạng thái đồng bộ trên từng mục (đã đồng bộ tick xanh, chưa tải lên tick xám, đang chờ, lỗi, tạm dừng `FLOOD_WAIT`).
  - Nút **Đồng bộ** trên tab `Ảnh` chỉ tải lên ảnh; trên tab `Video` chỉ tải lên video.
- **Tải lên nguyên gốc (`InputMessageDocument`):**
  - Gửi ảnh và video lên Telegram dưới dạng **File (Document)** để giữ nguyên độ phân giải, codec, tên tệp và siêu dữ liệu, không bị nén.
  - Tự động tạo ảnh thu nhỏ (`<= 320px` JPEG) đính kèm làm `InputThumbnail` để xem trước mượt mà.
  - Đính kèm metadata `#tele_anh_meta` (JSON) vào caption để hỗ trợ quét chỉ đọc và khớp tệp giữa máy với đám mây.
- **Tổ chức theo Album & Forum Topics:**
  - Hỗ trợ khoá một đích lưu trữ riêng tư: Siêu nhóm riêng tư (tự động tách chủ đề **Ảnh**, **Video**, **Đã Xoá Gần Đây** nếu bật Forum), Nhóm thường, Kênh riêng tư hoặc Tin nhắn đã lưu (`Saved Messages`).
  - Tạo Album riêng đồng bộ 1-1 với Forum Topic và ẩn 2 chủ đề hệ thống `Ảnh`/`Video` khỏi danh sách Album tuỳ chỉnh.
- **Xem trực tuyến, Tải về máy & Giải phóng dung lượng:**
  - Ưu tiên mở trực tiếp tệp trên thiết bị nếu còn bản cục bộ; tệp chỉ có trên Telegram sẽ hiển thị thumbnail nhẹ trước và tải/phát bằng **Media3 ExoPlayer** khi người dùng chạm mở.
  - Nút **Tải về máy** lưu tệp gốc vào `Pictures/Tele Photos` (ảnh) hoặc `Movies/Tele Photos` (video).
  - Tính năng **Giải phóng dung lượng máy** (trong chọn nhiều, trình xem và `Setting`) xoá bản cục bộ của các mục đã sao lưu trên Telegram, giữ lại bản đám mây để xem trực tuyến.
  - Bộ nhớ đệm xem trực tuyến giới hạn tự động **500 MB** theo LRU (xoá tệp xem cũ nhất) và có nút dọn thủ công trong `Setting`.
- **An toàn tài khoản & Kết nối ngắn hạn:**
  - **Không tự động kết nối nền:** Chỉ khởi tạo TDLib khi người dùng đăng nhập, chọn đích, tạo album, quét chỉ đọc, đồng bộ hoặc mở tệp đám mây; tự động chạy `TdApi.OptimizeStorage` và `TdApi.Close()` ngay khi xong.
  - **Chế độ Chỉ đọc + Tải lên:** Không bao giờ gọi API xoá tin nhắn hay xoá chủ đề trên Telegram. Xoá mục đã đồng bộ chỉ xoá bản trên máy (qua hộp thoại xác nhận hệ thống) và chuyển tiếp bản sao sang chủ đề **Đã Xoá Gần Đây** (lưu giữ cục bộ 30 ngày).
  - Hàng đợi tuần tự 1 luồng qua `WorkManager`, giãn nhịp `2.0–3.5 giây/tệp`, hỗ trợ giới hạn chỉ tải lên qua Wi-Fi, bộ ngắt mạch `FLOOD_WAIT` và cơ chế thử lại khi rớt mạng (`8 giây x 2 lần`).
- **Giao diện Tiếng Việt & Hỗ trợ máy cấu hình thấp:**
  - Toàn bộ UI và tên chủ đề Telegram bằng Tiếng Việt.
  - 3 chế độ giao diện: **Sáng**, **Tối** và **Liquid Glass** (tắt làm mờ nâng cao mặc định trên máy yếu), hỗ trợ bố cục thích ứng `NavigationBar` / `NavigationRail`.

---

## Công nghệ sử dụng

- **Ngôn ngữ & SDK:** Kotlin `2.4.21`, Java `11` bytecode, Android Gradle Plugin `9.1.1`, `minSdk 24`, `targetSdk 36`, `compileSdk 36`
- **Giao diện:** Jetpack Compose (BOM `2024.09.00`), Material 3, Navigation Compose `2.8.9`, Coil `2.7.0` (`coil-compose`, `coil-video`), Media3 ExoPlayer `1.11.1`
- **Lưu trữ & Nền:** Room `2.7.0` (KSP `2.3.5`), DataStore Preferences `1.1.7`, WorkManager `2.12.0`, Kotlin Coroutines `1.10.2`, `kotlinx-serialization-json` `1.13.0`
- **Telegram:** TDLib Android (`com.github.tdlibx:td:1.8.56` qua JitPack)
- **Kiểm thử:** JUnit `4.13.2`, Robolectric `4.16.1`, `kotlinx-coroutines-test`

---

## Cấu trúc dự án

```text
app/src/main/java/com/laughing/telephotos/
├── TelePhotosApplication.kt         # Khởi tạo repository/database và Coil ImageLoader
├── MainActivity.kt                  # Activity chính bật edge-to-edge và áp dụng TelePhotosTheme
├── core/
│   ├── CacheCleanupManager.kt       # Quản lý giới hạn cache 500 MB LRU và dọn cache tạm
│   ├── Formatters.kt                # Định dạng ngày/dung lượng/thời lượng, chuẩn hoá +84 & tên chủ đề
│   ├── MediaHashUtil.kt             # Tính SHA-256 (lấy mẫu >16MB), staging upload, tạo thumbnail <=320px
│   ├── MediaStoreScanner.kt         # Quét MediaStore, xin quyền, xoá hệ thống, lưu vào Pictures/Movies
│   ├── NetworkRetryHelper.kt        # Retry 8s x 2 lần, phát hiện stall 25s, điều phối cảnh báo mạng
│   ├── SettingsPreferences.kt       # DataStore lưu cấu hình theme, Wi-Fi guard, API ID/Hash, phiên TG
│   ├── TelegramCaptionMetadata.kt   # Đóng gói/giải mã JSON #tele_anh_meta trong caption Document
│   └── ThemeMode.kt                 # Enum giao diện Sáng / Tối / Liquid Glass
├── data/
│   ├── local/                       # Room Database v2 (MediaEntity/Dao, AlbumEntity/Dao, SyncTargetEntity/Dao)
│   ├── telegram/                    # TdLibManager (phiên ngắn hạn), TdLibModels, TelegramPolicyGuard
│   └── repository/VaultRepository.kt# Tầng điều phối trung tâm giữa Room, MediaStore, TDLib và WorkManager
├── worker/TelegramUploadWorker.kt   # CoroutineWorker tải lên tuần tự từng tệp qua TDLib
└── ui/                              # Navigation, ViewModel, Theme và các màn hình Compose
```

---

## Thiết lập & Build

### 1. Cấu hình Telegram API (Tuỳ chọn khi build, hoặc nhập trực tiếp trong app)
Ứng dụng dùng `secrets-gradle-plugin` để đọc `TELEGRAM_API_ID` và `TELEGRAM_API_HASH` từ tệp `.env` ở thư mục gốc (xem mẫu trong `.env.example`):

```properties
TELEGRAM_API_ID=12345678
TELEGRAM_API_HASH=your_api_hash_here
```

> Bạn có thể lấy `API ID` và `API Hash` tại [my.telegram.org](https://my.telegram.org). Nếu để giá trị mặc định trong `.env.example`, ứng dụng sẽ hiển thị màn hình nhập `API ID` / `API Hash` ngay khi bạn mở phần đăng nhập Telegram trong `Setting`.

### 2. Chạy unit test & build cục bộ
```bash
# Chạy unit tests
gradle :app:testDebugUnitTest

# Build APK debug
gradle :app:assembleDebug
```

### 3. Build tự động bằng GitHub Actions
Repository đã cấu hình sẵn workflow tại `.github/workflows/Main.yml` (kích hoạt khi `push` lên nhánh `main` hoặc `master`):
- Cài đặt JDK 17 (`temurin`).
- Gỡ bỏ `google-services` nếu có để build độc lập không cần Firebase.
- Tự tạo `debug.keystore` khi thiếu và chạy `./gradlew assembleDebug`.
- Xuất artifact **`app-debug`** (`app/build/outputs/apk/debug/app-debug.apk`).

---

## Lưu ý bảo mật & Chính sách sử dụng

- Không commit tệp `.env` chứa `TELEGRAM_API_ID` / `TELEGRAM_API_HASH` cá nhân hoặc `my-upload-key.jks` lên repository công khai.
- Thư mục dữ liệu phiên đăng nhập TDLib được lưu riêng tại `filesDir/tdlib_db` trên thiết bị và tách biệt hoàn toàn với bộ nhớ đệm xem trực tuyến (`cacheDir`).
