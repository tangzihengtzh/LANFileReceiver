# LANFileReceiver · 局域网文件接收

[简体中文](#简体中文) | [English](#english)

把 Android 手机变成局域网文件接收端。手机开启热点（或连接同一个 Wi-Fi），电脑用浏览器打开手机上显示的地址，拖拽文件即可上传，文件直接保存到手机的 `Download/LANTransfer/` 目录。

反向也支持：在手机上挑选照片，电脑在同一个网页里就能直接下载到本地。

无需数据线、无需 Windows 客户端、无需云服务器，全程只在局域网内传输。

Turn your Android phone into a LAN file receiver. Start a hotspot (or join the same Wi-Fi), open the address shown on the phone in any desktop browser, then drag & drop your files. They are saved straight into `Download/LANTransfer/` on the phone.

The other direction works too: pick photos on the phone and download them to your PC from the very same web page.

No cable, no desktop client, no cloud server — everything stays on your local network.

> **版本 / Version**：v0.1 — 双向传输（电脑 → 手机 文件、手机 → 电脑 照片）

---

## 简体中文

### 特性

- **零客户端**：电脑只需要 Chrome / Edge / Firefox 等浏览器
- **完全离线**：网页资源全部打包进 APK，不依赖任何 CDN，不向互联网发送数据
- **拖拽上传**：支持拖拽与多选，可一次提交多个文件，串行队列避免内存与带宽竞争
- **实时进度**：文件名、大小、已上传字节、百分比、进度条、速度与剩余时间
- **大文件友好**：全链路流式传输，1 GB 文件实测内存占用恒定（约 135 MB PSS），不会 OOM
- **文件名安全**：中文名不乱码，重名自动编号为 `name (1).ext`，绝不覆盖已有文件
- **自动探测地址**：运行时枚举网卡与网络能力，自动给出推荐局域网 IPv4，排除 VPN / 回环 / IPv6 / 蜂窝
- **一次性 Token**：每次启动服务器重新生成，页面与 API 均校验，错误 Token 返回 `403`
- **后台不中断**：服务器运行在前台服务中，切到后台或锁屏传输继续
- **双向传输**：手机上用系统照片选择器挑选照片，电脑网页里直接预览并下载到本地
- **异常健壮**：断网、关热点、存储失败、空间不足都会记录失败并清理临时文件，不留下半成品

### 使用流程

```
手机开启热点  →  电脑连接该热点  →  手机上点击「启动文件接收」
        ↓
手机显示 http://192.168.x.x:8080/?token=XXXXXX
        ↓
电脑浏览器打开该地址  →  拖入文件  →  浏览器显示实时进度
        ↓
文件保存到手机 Download/LANTransfer/
```

### HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 上传页面（需要 Token） |
| GET | `/app.js`、`/style.css` | 内置静态资源 |
| GET | `/api/status` | 服务器状态、设备名、地址、已连接设备数 |
| GET | `/api/transfers` | 本次运行期间的传输记录 |
| POST | `/api/upload` | `multipart/form-data` 流式上传，支持单文件与多文件 |
| GET | `/api/photos` | 手机端已选照片列表 |
| GET | `/api/photos/{id}` | 下载原始照片（带 `Content-Disposition`） |
| GET | `/api/photos/{id}/thumb` | 缩略图，供网页画廊预览 |

Token 通过查询参数 `?token=` 或请求头 `X-Auth-Token` 传递。

### 技术栈

Kotlin · Jetpack Compose · MVVM · Coroutines / StateFlow · Foreground Service · MediaStore

HTTP 服务器与 `multipart/form-data` 解析器为自研实现（不引入第三方网络库），以便完全控制流式读取行为：数据从 socket 直接写入目标文件，缓冲区固定 256 KB，内存占用与文件大小无关。

### 文件保存策略

- Android 10（API 29）及以上：使用 `MediaStore.Downloads`，以 `IS_PENDING` 充当临时文件，写入完成才对外可见，无需存储权限
- Android 9（API 28）及以下：写入公共下载目录，先写 `.tmp` 再重命名

### 构建

```powershell
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

环境要求：JDK 17+、Android SDK Platform 36、Build-Tools 37.0.0，minSdk 24 / targetSdk 36。

> 小米 / Redmi 等机型若提示 `INSTALL_FAILED_USER_RESTRICTED`，可改用：
>
> ```powershell
> adb push .\app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/lan.apk
> adb shell pm install -r -t /data/local/tmp/lan.apk
> ```

### 权限说明

遵循最小权限原则，仅申请：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`，以及 Android 9 及以下需要的 `WRITE_EXTERNAL_STORAGE`。

不申请定位、通讯录、短信、电话、相机、麦克风等任何无关权限。

### 已知限制

- 应用不负责开关热点，需要用户手动开启
- 传输记录与照片列表仅保留在内存中，进程结束即清空（后续可迁移到 Room）
- 手机 → 电脑仅支持照片；暂未实现断点续传与二维码
- 照片通过 Content URI 共享，不复制文件本体；若照片在系统相册中被删除，电脑端会提示重新选择

---
<img width="1280" height="2772" alt="Screenshot_2026-09-16-11-02-19-023_com lanfile tr" src="https://github.com/user-attachments/assets/bb8c3a69-7942-405f-913a-497f5ef9ec84" />

## English

### Features

- **No desktop client** — any browser (Chrome / Edge / Firefox) works
- **Fully offline** — all web assets are bundled in the APK; no CDN, no data ever leaves your LAN
- **Drag & drop** — drop or multi-select files; uploads run in a serial queue to avoid memory, IO and bandwidth contention
- **Live progress** — file name, size, uploaded bytes, percentage, progress bar, speed and ETA
- **Built for large files** — the whole pipeline is streaming; a 1 GB upload keeps memory flat (~135 MB PSS), no OOM
- **Filename safety** — UTF-8 names are preserved; duplicates are auto-renamed to `name (1).ext`, never overwritten
- **Automatic address detection** — enumerates network interfaces and capabilities at runtime, skipping VPN / loopback / IPv6 / cellular
- **One-time token** — regenerated on every server start, enforced for both the page and the API; invalid token returns `403`
- **Survives backgrounding** — the server runs in a foreground service, so transfers continue when you leave the app
- **Two-way transfer** — pick photos on the phone with the system photo picker, then preview and download them on the PC from the same page
- **Robust failure handling** — dropped Wi-Fi, hotspot shutdown, storage errors and low disk space all fail cleanly and remove partial files

### Workflow

```
Enable hotspot  →  Connect your PC  →  Tap "启动文件接收" on the phone
        ↓
Phone shows http://192.168.x.x:8080/?token=XXXXXX
        ↓
Open it in a browser  →  Drop files  →  Watch live progress
        ↓
Files land in Download/LANTransfer/ on the phone
```

### HTTP API

| Method | Path | Description |
|---|---|---|
| GET | `/` | Upload page (token required) |
| GET | `/app.js`, `/style.css` | Bundled static assets |
| GET | `/api/status` | Server status, device name, addresses, connected clients |
| GET | `/api/transfers` | Transfer history of the current session |
| POST | `/api/upload` | Streaming `multipart/form-data` upload (single or multiple files) |
| GET | `/api/photos` | Photos currently shared from the phone |
| GET | `/api/photos/{id}` | Download the original photo (with `Content-Disposition`) |
| GET | `/api/photos/{id}/thumb` | Thumbnail used by the web gallery |

Pass the token via the `?token=` query parameter or the `X-Auth-Token` header.

### Tech Stack

Kotlin · Jetpack Compose · MVVM · Coroutines / StateFlow · Foreground Service · MediaStore

The HTTP server and the `multipart/form-data` parser are implemented from scratch (no third-party network library) to keep full control over streaming: bytes go straight from the socket to the destination file through a fixed 256 KB buffer, so memory usage is independent of file size.

### Storage Strategy

- Android 10 (API 29) and above: `MediaStore.Downloads` with `IS_PENDING` acting as a temporary file — nothing is visible until the write completes, and no storage permission is needed
- Android 9 (API 28) and below: write `.tmp` into the public Downloads folder, then rename

### Build

```powershell
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

Requires JDK 17+, Android SDK Platform 36, Build-Tools 37.0.0. minSdk 24 / targetSdk 36.

> On Xiaomi / Redmi devices, if you hit `INSTALL_FAILED_USER_RESTRICTED`:
>
> ```powershell
> adb push .\app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/lan.apk
> adb shell pm install -r -t /data/local/tmp/lan.apk
> ```

### Permissions

Minimal by design: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, plus `WRITE_EXTERNAL_STORAGE` on Android 9 and below only.

No location, contacts, SMS, phone, camera or microphone permissions.

### Known Limitations

- The app does not toggle the hotspot for you
- Transfer history and the shared photo list live in memory only and are cleared when the process dies (Room migration planned)
- Phone → PC supports photos only; no resume-after-interruption and no QR code yet
- Photos are shared by Content URI without copying; if a photo is deleted from the gallery, the PC will be asked to re-select it

---

## 版本历史 / Changelog

| 版本 / Version | 内容 / Highlights |
|---|---|
| **v0.1** | 新增手机 → 电脑照片传输（系统照片选择器、缩略图画廊、单张/全部下载、已发送回显）；访问 Token 改为 4 位数字<br>Phone → PC photo transfer (system photo picker, thumbnail gallery, single / batch download, sent-state feedback); access token changed to 4 digits |
| **v0** | 首个版本：手机作为局域网文件接收端，浏览器拖拽上传，流式落盘、一次性 Token、前台服务<br>First release: phone as a LAN file receiver, browser drag & drop upload, streaming to disk, one-time token, foreground service |

用 Git tag 区分版本 / Versions are distinguished by Git tags：

```bash
git checkout v0     # 第一个版本 / first release
git checkout v0.1   # 双向传输版本 / two-way transfer release
```

