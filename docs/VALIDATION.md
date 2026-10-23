# 验证记录

日期：2026-10-22 起，10-23 更新。此记录说明实际完成的验证，不等同于独立安全审计或全设备兼容性认证。

## 搜索筛选、生物识别与空状态接手验证（2026-10-23）

接手另一聊天遗留的未完成增量，补齐资源与界面，并恢复构建。前文记录的保险库生物识别缺失资源构建错误已解决。

- 搜索新增筛选对话框：图片／视频／音频／Markdown／指定扩展名；包含子文件夹开关；修改／创建日期；今天、昨天、近 7 天、自选起止日。日期按设备时区自然日处理，首尾日均包含。取消与重载通过请求代次抑制旧结果回写。
- 真实创建时间仅由明确提供该数据的 SMB、WebDAV、压缩包源标识；本地 Linux 属性当前没有 birth time，筛选创建日期时不会用其修改时间替代，界面说明缺失时间的文件被排除。
- 保险库新增显式启用／停用的系统强生物识别入口。AndroidX BiometricPrompt CryptoObject 授权 Android Keystore AES-GCM 加解密本机凭据；gocryptfs 格式与备份不变，绑定在 noBackupFilesDir，主密码始终保留。系统只会提供满足强认证要求的指纹／人脸，不承诺所有设备的人脸传感器均可用于密码学操作。
- 凭据与 vault 配置摘要绑定；配置替换、密钥失效或解密失败要求重新启用。验证取消／后台切换清理待处理凭据；会话代次防止锁定前的迟到认证结果重新打开保险库。临时传感器不可用保留绑定。真实人脸／指纹、重新录入后密钥失效、旋转／切后台的系统提示行为仍需人工真机验收；JVM 不能验证这些硬件行为。
- 文件夹为空、搜索无结果、预览未选择文件共享 `file_empty_state.xml`，在所属内容区域居中展示图标、标题与次级提示。约定见 `docs/UI_GUIDELINES.md`。
- JVM 测试 120 项通过；新增测试覆盖类型／扩展名、日期首尾与 DST、缺失创建时间、加密封装往返／篡改／错误绑定／畸形输入，以及锁定后的迟到认证拒绝并清空凭据。
- 模拟器（2160×2344、480dpi）端到端通过：当前文件夹与递归搜索、Markdown／扩展名／日期过滤、未知创建时间排除、筛选对话框、搜索和预览空状态居中。修正仪器测试对文件列表与侧栏同名 RecyclerView 的定位及溢出按钮落点后，大屏 LIST 与 GRID 的长按扩选、回拖、普通滚动、边缘自动滚动、取消均通过。只使用生成的应用缓存文件，沿用临时隔离 Root/桥接自启的模拟器条件。

最终检查：`:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 全部通过（lint 0 error、1102 warnings、3 hints）；荣耀 VER-AN10 通过 `adb install -r` 安装最新调试包。未执行真实生物识别认证，未将该项人工验收记为通过。

## 折叠屏列表与预览（2026-10-23）

- 增加小列表（最小行高 56dp）、中等列表、缩略图网格、列表与预览四种视图；保留原有视图设置序号与按目录记忆机制。列表依据实际内容区域宽度与字体缩放分列，导航侧栏不计入文件区域。
- 「显示文件属性」控制两种列表的第二行；文件显示修改时间和大小，文件夹显示修改时间和类型，不将目录 inode 大小冒充目录总大小。
- 预览模式在至少 640dp（随大字体增加）可用宽度时展示右栏；左栏最少 320dp，较宽窗口采用约 40% / 60% 比例。图片取消固定 240dp 高度，以右栏宽度及可用高度的 68% 为上限保持比例；详情区域可滚动，操作按钮并排。
- 预览复用列表 Coil 管线，提供图片／视频缩略图、类型与 MIME、精确字节数和人类可读大小、完整修改时间、路径，以及提供者支持的链接目标、所有者、组和权限。没有内嵌音视频播放、文本正文或目录递归统计。更换条目、隐藏右栏及离开页面时清理请求与画面；未改动播放器、保险库或底层随机读取实现。
- 长按后滑动选择／取消连续范围，回拖恢复手势前的选择，边缘自动滚动，抬手或取消后停止；普通滑动保留滚动行为，单选文件选择器不启用批量滑选。
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 通过；112 个 JVM 测试通过（含新增 7 个布局／选择范围测试），lint 0 error、1065 warnings、3 hints。
- 荣耀 VER-AN10 展开态安装成功，UI 层级确认双列和文件夹属性行；预览实际显示图片及文件属性。小列表、实体折叠切换、大字体、锁屏后恢复及真实网络文件缩略图的完整人工回归仍待完成。
- 模拟器原生触摸回归：长按、范围扩选、回拖、普通滚动、边缘滚动与取消均通过。测试只使用生成的应用缓存文件；首次运行遇到 Root 配置下 `The main shell was already created` 和后台启动 `ForegroundServiceStartNotAllowedException`，模拟器限定 Root NEVER、关闭桥接自启动后通过；这两个既有启动问题未在本次修复。

补充验证：GRID（双列）与 COMPACT 的上述触摸回归同样通过。展开尺寸追加测试最初因测试通过 Activity 查找同名 RecyclerView 而命中导航侧栏，已将测试定位改为 contentLayout 内的文件列表。修正测试后的追加构建遇到共享工作区同期改动：`VaultActivity.kt:87 Unresolved reference fw_vault_biometric_enabled`、`:243 Unresolved reference fw_vault_biometric_prompt` 等资源缺失，因此此轮展开态仪器测试尚未重跑；不将它记为通过。此前视图与手势主体的完整构建和 JVM/lint 结果仍为上文所记。

## 0.4.0 真机回归（10-23，主体通过）

环境与网络：用户重组网（FlClash→Tailscale）后手机↔Mac 局域网互通恢复；bridge.py + `dns-sd -R` 替代菜单栏程序（Xcode 仍缺失）。

VER-AN10 / API 36 / MagicOS 上全部通过：

- **零接触配对闭环**（含 pm clear 后从零恢复共 4 轮）：NSD 发现→pair/start→auto-approve（标记文件）→poll→自动保存→自动连接，全程无人工点击。
- **常驻连接服务**：应用启动即自启（设置默认开），通知渠道 bridge_connection 注册并显示状态；手机↔Mac 8787 持久连接（netstat ESTABLISHED 验证）。
- **Mac→手机剪贴板自动同步端到端**：Mac 侧 POST /v1/clipboard 写入标记文本，≤5 秒后手机系统剪贴板持有该文本（搜索框长按粘贴菜单直接显示）。
- **抽屉重构**：Mac 剪贴板／Mac 共享文件两个入口就位，标准目录分类已移除；ClipboardActivity/BridgeActivity 的 FLAG_SECURE 均按设计拒绝截图。
- **文件夹共享 TLS 链路**：软件密钥（见下）+ Mac CA 签发 + TLS 1.3 握手成功；服务端证书 SHA-256 与 paired-devices.json folderFp 一致（等价产品端指纹固定）。
- **协议回归（curl 全量）**：/v1/status、/v1/tree（录音过滤、.Trash 元数据 movedAt/trashPath）、完整下载 MD5 与原件一致、Range 206 且字节一致、无鉴权/错 token 401、路径穿越 400、删除（复制＋移原件）与还原（内容逐字节一致）闭环。
- **FGS**：specialUse（types=0x40000000）isForeground=true 稳定运行，通知含文件计数实时更新。
- 快速设置磁贴代码在位但未加入磁贴面板（需用户在 QS 编辑中拖入），待后续人工确认。

真机抓到并修复的缺陷（均回归验证）：

1. `folder_bridge` 通知渠道未注册 → `CannotPostForegroundServiceNotificationException` 崩溃；已加入 AppInitializers 渠道创建。
2. AndroidKeyStore 硬件 RSA 密钥无法完成 TLS 服务端握手：本机 Conscrypt 经 `RSA/ECB/NoPadding` 原始 Cipher 上行调用签名，keystore 不支持原始 NoPadding（INCOMPATIBLE_PADDING/DIGEST），任何密钥规格都无法满足；已改为应用私有目录（文件级加密保护）中的软件 RSA 密钥对，CSR 签发流程不变，代码注释记录取舍。
3. 软件密钥首版从私钥推导公钥在部分 KeyFactory 上失败（"Local key missing"）；改为公钥 SPKI 独立文件保存。

未完成/待验证（2026-10-23 深夜交接，下一台机器继续）：

- 全部新功能已实现并集成：录音目录自动识别（真机已验）、互通历史页、截屏自动互通、录音实时推送（bridge.py /v1/notify + folder-events.jsonl）、WebDAV 服务器、SMB 评估暂缓、UI 改版（标题面包屑/根目录隐藏/关于页署名/「共享文件」改名）。**104 个 JVM 测试全绿、lint 0 error、Python 22 个测试通过。**
- 最终真机安装与验收被网络阻塞：手机已切热点网，与开发机仅剩 Tailscale 中继且断连。**最新 APK（36,677,261 字节，MD5 5d8db9a7b275c954b918d5147ad2328b）已完整送达手机 `/data/local/tmp/app-debug.apk`**；手机回到与开发机同网后执行 `adb shell pm install -r /data/local/tmp/app-debug.apk` 完成安装（此前已确认同签名可无人值守安装）。
- 待验收清单：WebDAV（开服务→PROPFIND/GET/PUT→Finder Cmd+K 挂载）；截屏互通（`adb shell input keyevent 120` 触发截屏→查 mac/.state/inbox/）；录音实时事件（共享目录出现新音频→查 mac/.state/folder-events.jsonl；勿对真实 Sounds 写入测试文件）；UI 四项（抽屉无「根目录」、标题面包屑、关于页署名、共享文件改名）截图确认。
- 手机设置仍为调试态（亮度 25、锁屏超时 1 小时）；验收完成后恢复原值：亮度 52+自动（`settings put system screen_brightness_mode 1; settings put system screen_brightness 52`）、锁屏 30 秒（`settings put system screen_off_timeout 30000`）。
- Mac 端菜单栏程序、转录、旧数据迁移仍等 Xcode；本机桥为 bridge.py + `dns-sd -R` 替代运行，新机器需自行拉起（见 docs/DEVELOPMENT.md）。

## 0.4.0 真机回归（第一晚，部分完成）

开发机环境在 10-22 晚间被整体重置（JDK 21、Android SDK/NDK、Go、Xcode、`~/.android` 旧调试密钥与 `mac/.state/` 配对数据全部丢失）。已重装 JDK 21（Homebrew openjdk@21）、SDK Platform 37.2 / Build-Tools 37.0.0 / NDK 30.0.16248370 / CMake 3.22.1 与 Go 1.27，`:app:assembleDebug` 通过并产出 0.4.0-dev（45）；Python 协议测试 18 个通过；Maven Central 对本机 IP 返回 403，经 `~/.gradle/init.gradle` 配置阿里云镜像解决（机器级配置，未改仓库）。

已完成的真机验证（VER-AN10 / API 36）：

- 0.4.0-dev（45）安装成功。旧 0.3.1-dev（44）因旧调试密钥随环境丢失而无法覆盖安装；经 run-as 核实应用数据仅有已失效的旧配对配置、无保险库数据后卸载重装，未触碰用户其他数据。
- 手机侧 NSD 发现与配对协议：`BridgeActivity` 打开后经 `_fileweave._tcp.` 发现 Mac（bridge.py 独立运行 + `dns-sd -R` 代替 Swift 菜单栏程序发布），TLS 固定证书连接、`pair/start`/`pair/poll` 与 Mac 侧决定文件批准全部按预期工作，当晚共 9 轮请求全部完成批准（含双方核对码生成）。
- `FLAG_SECURE` 安全行为在真机确认：连接页面截图与 uiautomator 转储均被拒绝，符合设计。

阻塞项与原始原因（均未绕过、未声称通过）：

- 首次信任需要手机端点击「已在 Mac 核对，完成配对」。荣耀 MagicOS 的多层防护使远程点击不可行：安全安装确认对话框忽略注入触控并阻止独立测试 APK（`INSTALL_FAILED_ABORTED`，`pm install -t` 同样被拒）；配对确认对话框属 FLAG_SECURE 窗口；crash 日志经 Honor 密钥加密不可读；手机上残留的投屏虚拟显示器反复触发 Activity 重建（`Performing restart`，锁定刷新率后仍复现），重建会取消进行中的配对。需要用户在场手动点击一次完成；未为绕过该人工确认修改任何产品代码。
- Xcode 已被卸载，Command Line Tools 不含 SwiftUI 宏插件（`SwiftUIMacros` plugin not found），`swift build`/`swift test` 无法编译菜单栏程序；Mac 面板发现、录音浏览、转录、AI 摘要与旧数据迁移启动项全部保留待 Xcode 恢复。
- 迁移测试准备就绪：`~/Library/Application Support/HonorRecorderBar` 已按旧项目（techfanseric/HonorRecorderBar）格式合成 library.json（含 pendingNew 旧账本）、transcripts 侧车、asr 与 bailian.env 占位（假密钥）；`FileWeave` 新目录确认不存在，首次启动即触发迁移。

辅助调试设施（机器级，未提交）：`mac/.state` 由 bridge.py 重建；自动批准观察器与 `dns-sd` 发布进程按 0.3.1 联测方式运行；Vision OCR（`/tmp/ocr.swift`）用于定位系统界面元素。仓库内新增 debug-only `FolderBridgePairingInstrumentation`（app/src/debug，模式同既有三个 instrumentation，随主 APK 分发），预期在安装确认可完成后替代人工点击，**该 instrumentation 尚未在真机运行过，未验证**。

## 0.4.0 文件夹共享与录音监视开发分支（脱机部分）

本阶段代码完成时全部为脱机验证（真机进展见上方「0.4.0 真机回归（第一晚）」），下列真机项仍保留：

- 手机端 TLS 服务器与 Android Keystore KeyManager 的真实握手、向 Mac 请求证书签发的闭环。
- NSD 双端发现（手机注册 `_fileweave-folder._tcp.`、Mac 浏览并按指纹匹配）。
- SAF 真实 provider 上的递归列表、`.Trash` 删除／还原闭环（复制＋校验＋删原件语义）。
- Android 15+ 的 specialUse 前台服务、快速设置磁贴、通知行为。
- 真实录音目录的扫描性能、转录与 AI 摘要、旧「荣耀录音监视器」数据迁移。

脱机已验证：

- Android `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug`：通过；37 个 JVM 测试（folderbridge 29 个：HTTP 内核鉴权／路由／Range 全边界／头部与正文上限／路径安全／CSR 结构与真实签名、`+` 字面解码、movedAt 缺省），lint 0 error / 973 项结果。
- Mac `swift build`、`swift test`：30 个测试通过（协议解析含 movedAt=0、配对记录、Bonjour TXT、Content-Range、重连退避、水位线、登录项）；`build.sh` 产出 .app（Info.plist 含两类 Bonjour 服务、LSUIElement；ad-hoc 签名），未启动安装。
- Python `test_folder_key` / `test_pairing` / `test_bridge`：18 个通过，含 CA 签发链验证、指纹落账与替换、master token 拒绝、并发签发唯一性、超限请求。
- 两轮独立代码评审（Android 侧、Mac/Python 侧）无 P0 发现；P1（specialUse 服务类型、install.sh 孤儿进程、movedAt=0、迁移清单）与接受的 P2 已全部修复并回归测试。

## 0.3.1 双端操作简化开发分支

- Android `assembleDebug`、`testDebugUnitTest`、`lintDebug`：通过；8 个 JVM 测试，lint 0 error / 942 warning。新增测试覆盖连接退避、上限与恢复后重置。
- Mac Swift 菜单栏程序编译、启动通过，当前本机运行新版；Python 协议测试 12 个通过。
- VER-AN10 / API 36 真机：0.3.1-dev（44）经无线 ADB 覆盖安装成功，沿用原批准凭据。
- `automatic-bridge-smoke.py` 双端联测通过：Bonjour 发现、已有凭据连接、保存地址失效后找回真实地址、错误证书拒绝、共享列表自动增加／移除，以及 Mac 服务被终止后自动拉起、手机恢复并继续更新列表。测试只创建并清理随机生成的文件，不读取真实剪贴板或修改已有共享文件。
- 连接页面已验证不出现旧的手动配对、搜索、刷新和重置控件；Mac 菜单源代码已移除配对信息、服务启停与重置入口，保留首次批准弹窗。
- 联测曾发现主线程关闭 TLS 连接引发 `NetworkOnMainThreadException`，已将连接清理移到专用线程，修复后断线恢复联测通过。修复后另有一次列表更新等待超时，随后同版本连续两轮完整重跑通过；保留为后续长时间网络稳定性观察项。
- 首次人工信任、多个 Mac、Wi-Fi 切换和长时间前后台切换仍需更完整的人工回归。自动发现和列表更新当前仅在 Android 连接页面前台执行，Mac 应用退出后不维持服务。

## 0.3 自动发现开发分支

- Android `assembleDebug`、`testDebugUnitTest`、`lintDebug`：通过；6 个 JVM 测试，lint 0 error / 948 warning。
- Mac Swift 菜单栏程序：编译、启动、系统局域网授权后 Bonjour 发布通过；本机发现工具可见 `_fileweave._tcp.` 服务。
- Python 协议测试：12 个通过，包含原文件／文本协议，以及批准前拒绝访问、随机 nonce 绑定、独立持久化凭据、拒绝、过期、迟到决定、取消失效、限流与私有状态文件权限。
- 两端配对码共享固定向量测试通过；修改证书指纹或 nonce 会改变核对码。
- VER-AN10 / API 36 真机：0.3.0-dev（43）经无线 ADB 覆盖安装通过。
- 真机 `BridgeDiscoveryInstrumentation`：Bonjour 发现、使用已有批准凭据连接、模拟保存地址失效后自动找回真实地址、错误证书拒绝，全部通过。测试最后恢复原配对配置，未读取真实剪贴板或修改共享文件。
- 首次新设备的 Mac 弹窗与手机“完成配对”整套人工操作、多台 Mac、路由器隔离和不同 VPN 组合仍待人工回归；协议状态与权限边界已有上述自动化覆盖。

## 0.2.1 历史验证

### 构建与静态检查

- JDK 21、Gradle 9.6.0、Go 1.26.0。
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`：通过。
- JVM 单元测试：4 个通过，覆盖密文备份恢复、不覆盖现有库、解压路径和大小限制，以及长短视频手势进度映射、边界钳制和无效输入。
- 最后一次 lint：0 个 error，937 条 warning，包含继承的翻译、未使用资源、旧 API 及网络相关检查项。未用 baseline 隐藏本次 lint 错误。
- 本次发现并修复的 lint 错误：API 23–25 的 autofill 兼容性和 Media3 opt-in 标记。
- macOS Swift 菜单栏程序：编译及启动通过。
- Python HTTPS 服务：6 个测试通过，覆盖未配对拒绝、Range、路径／符号链接限制、chunked 上传、重名保护、中文文本读写与超限拒绝。

### Android 真机（VER-AN10，API 36）

使用 debug-only Instrumentation 和自行生成的测试文件，未读取或修改用户文件。

- 文件内容加密写入并重新解锁读取，中文及 emoji 文件名往返：通过。
- 跨加密块的随机偏移读取：通过。
- 锁定后旧读取会话失效，重复关闭不导致 native 崩溃：通过。
- 错误密码拒绝、重新解锁、重名导入不覆盖：通过。
- 修改密文后认证失败：通过。
- 超过 4 GiB 的偏移读取：通过，使用临时稀疏文件，并非完整的超大视频编码回归。
- 本地 MP4 准备、播放及跳转：通过。
- 库内 MP4 直接解密读取与跳转：通过。
- 导入中锁定、下次解锁清理未完成临时密文：通过。
- 密文备份恢复后，导出明文与原样本逐字节一致：通过。
- Mac 局域网证书固定 HTTPS、错误指纹拒绝：通过。
- 手机上传文本样本、下载 Mac 文件、Mac 视频流式播放：通过。
- 配对配置 Keystore 加密保存与读取：通过。
- 播放器手势：真机横竖屏自动化通过。覆盖左／右／顶部／底部区域起手横拖、只显示调节反馈、暂停状态保留、取消、左侧亮度、右侧媒体音量、左右双击 ±10 秒、控件自动隐藏及拖动中退出。亮度仅影响当前窗口；测试后恢复原媒体音量。
- 新版 0.2.1-dev（42）已通过 ADB 覆盖安装。

Mac 配对地址曾误选 VPN 虚拟地址，导致真机 TLS 连接失败。已改为 macOS 优先枚举物理网络接口，Android 局域网请求显式不使用 HTTP 代理；修复后完成上述局域网测试。

### Android 模拟器（API 34）

独立只读实例中，本地与加密 MP4 播放／跳转、内容加密、锁定、密文篡改，以及 Mac HTTPS 协议集成测试通过。0.2.1 新增的整套手势横竖屏测试也通过。

模拟器一次普通界面冷启动出现 ANR，后续重试可启动；真机启动与播放未复现。该冷启动性能现象保留为后续排查项，不据此声称 UI 在所有设备上均已稳定。

### 尚未覆盖

- 长时间后台行为、所有设备和系统版本、指纹解锁（尚未实现）。
- 不同 SMB/SFTP 服务的真实网络断线、复杂字幕及特殊编解码组合。
- 完整超大视频的码率、HDR、功耗与性能回归。
- Mac 端直接解锁保险库（尚未实现）。
- 真机剪贴板界面的完整人工回归；协议层文本传输已测试，系统剪贴板仅由用户前台按钮操作。
- 密码实现、native 内存处理、服务器协议和第三方依赖的独立安全审计。

### 复现

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 -m unittest discover -s mac -v
./mac/run.sh
python3 scripts/device-smoke.py DEVICE_SERIAL --video /absolute/path/to/generated-test.mp4 --pairing mac/.state/pairing.json
```

手势回归额外需要将至少 2 分钟的生成 MP4 放入应用缓存 `cache/player-gesture-qa.mp4`，然后运行：

```sh
adb -s DEVICE_SERIAL shell am instrument -w org.fileweave.android/me.zhanghai.android.files.diagnostics.PlayerGestureInstrumentation
```

模拟器可用 `--host 10.0.2.2` 作为测试传输。配对信息及私钥不提交；测试只使用隔离缓存目录。Instrumentation 位于 `src/debug`，不进入 release 构建。
