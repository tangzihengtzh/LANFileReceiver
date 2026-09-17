# LANFileReceiver · 局域网文件接收

把 Android 手机变成局域网文件接收端：手机开热点，电脑用浏览器打开手机显示的地址，拖入文件即保存到手机 `Download/LANTransfer/`；反向也能把手机上的照片或任意文件下载到电脑。全程局域网直传，无需数据线、客户端或云服务。

Turn your Android phone into a LAN file receiver: start a hotspot, open the address shown on the phone in any browser, drag & drop files — they land in `Download/LANTransfer/`. The reverse direction works too: pick photos or any other file on the phone and download it to your PC. Everything stays on your LAN — no cable, no client, no cloud.

> **版本 / Version**：v0.3 — 双向传输：电脑 → 手机 任意文件，手机 → 电脑 照片与任意文件

## 界面 / Screenshots

<img width="1280" height="2772" alt="Screenshot_2026-09-16-11-02-19-023_com lanfile tr" src="https://github.com/user-attachments/assets/bb8c3a69-7942-405f-913a-497f5ef9ec84" />
<img width="1230" height="640" alt="Screenshot 2026-09-16 131835" src="https://github.com/user-attachments/assets/9dfc77ef-e8f3-4a2d-948e-e4753b681f61" />

## 使用 / Usage

1. 手机开启热点，电脑连接该热点
2. 手机上点击「启动文件接收」，记下显示的地址和 4 位 Token
3. 电脑浏览器打开 `http://192.168.x.x:8080`，在输入框中填入 Token
4. 拖拽或选择文件上传；手机端的照片与文件可在网页「手机上的文件」中下载

## 特性 / Features

- **零客户端**：电脑只需 Chrome / Edge / Firefox
- **完全离线**：网页资源全部打包进 APK，不依赖 CDN，不向互联网发送数据
- **拖拽上传**：支持拖拽与多选，串行队列，实时显示进度、速度与剩余时间
- **大文件友好**：全链路流式传输，1 GB 文件内存占用恒定（约 135 MB PSS）
- **文件名安全**：中文名不乱码，重名自动编号 `name (1).ext`，绝不覆盖
- **地址自动探测**：排除 VPN / 回环 / IPv6 / 蜂窝，给出可用局域网 IPv4
- **Token 不进网址**：打开页面弹出输入框，校验通过后下发会话 Cookie
- **后台不中断**：服务器运行在前台服务中，切后台或锁屏传输继续
- **双向传输**：手机选照片或任意文件，电脑网页直接预览并下载
- **异常健壮**：断网、存储失败、空间不足均记录失败并清理临时文件

- **No client** — any browser works
- **Fully offline** — web assets are bundled in the APK; nothing is sent to the internet
- **Drag & drop** with a serial queue, live progress, speed and ETA
- **Large files** — fully streaming; 1 GB keeps memory flat (~135 MB PSS)
- **Safe filenames** — UTF-8 preserved, duplicates become `name (1).ext`, never overwritten
- **Auto address detection** — skips VPN / loopback / IPv6 / cellular
- **No token in the URL** — a prompt appears on load, then a session cookie is issued
- **Runs in the background** via a foreground service
- **Two-way** — pick photos or any file on the phone, preview and download it on the PC
- **Fails cleanly** on disconnects, storage errors and low disk space

## HTTP 接口 / API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 上传页面（公开，不含 Token） |
| GET | `/app.js`、`/style.css` | 内置静态资源 |
| POST | `/api/auth` | 提交 4 位 Token 换取会话 Cookie |
| GET | `/api/status` | 服务器状态、设备名、地址 |
| GET | `/api/transfers` | 本次运行期间的传输记录 |
| POST | `/api/upload` | `multipart/form-data` 流式上传（单/多文件） |
| GET | `/api/files` | 手机端已选文件列表 |
| GET | `/api/files/{id}` | 下载原始文件 |
| GET | `/api/files/{id}/thumb` | 图片缩略图（非图片返回 415） |

除 `/` 与静态资源外均需鉴权：浏览器用会话 Cookie，脚本可用 `X-Auth-Token` 请求头。
Token 每次启动服务器重新生成，重启后会话失效，网页会自动重新弹出输入框；同一 IP 连续 10 次失败锁定 30 秒。

## 技术栈 / Tech Stack

Kotlin · Jetpack Compose · MVVM · Coroutines / StateFlow · Foreground Service · MediaStore

HTTP 服务器与 `multipart/form-data` 解析器为自研实现（无第三方网络库），数据从 socket 直接写入目标文件，缓冲区固定 256 KB。

## 文件保存 / Storage

- Android 10+：`MediaStore.Downloads`，以 `IS_PENDING` 充当临时文件，写完才对外可见，无需存储权限
- Android 9 及以下：写入公共下载目录，先写 `.tmp` 再重命名

## 构建 / Build

```powershell
.\gradlew.bat assembleDebug
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

JDK 17+、Android SDK Platform 36、Build-Tools 37.0.0；minSdk 24 / targetSdk 36。

> 小米 / Redmi 若报 `INSTALL_FAILED_USER_RESTRICTED`：
>
> ```powershell
> adb push .\app\build\outputs\apk\debug\app-debug.apk /data/local/tmp/lan.apk
> adb shell pm install -r -t /data/local/tmp/lan.apk
> ```

## 权限 / Permissions

仅 `INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`POST_NOTIFICATIONS`，以及 Android 9 及以下需要的 `WRITE_EXTERNAL_STORAGE`。不申请定位、通讯录、短信、相机、麦克风。

## 已知限制 / Limitations

- 不负责开关热点，需手动开启
- 传输记录与共享文件列表仅存内存，进程结束即清空
- 暂无断点续传与二维码

## 版本 / Versions

| 版本 | 内容 |
|---|---|
| **v0.3** | 手机端除照片外还可选择任意文件发送到电脑；接口 `/api/photos` 更名为 `/api/files` |
| **v0.2** | Token 改为网页输入框 + 会话 Cookie，不再出现在网址中 |
| **v0.1** | 手机 → 电脑照片传输；Token 改为 4 位数字 |
| **v0** | 首个版本：手机作为局域网文件接收端，浏览器拖拽上传 |

```bash
git checkout v0     # 第一个版本
git checkout v0.1   # 双向传输
git checkout v0.2   # Token 输入框
git checkout v0.3   # 文件传输
```
