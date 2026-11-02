# 「Mac 直接取手机文件」三路径评估（拉取式互传）

- 调研日期：2026-10-25（第二轮）。方法：rea 6.8.0（已全局安装）对 HonorConnect.app 的 CrossDevFileService 相关模块做定向静态分析 + 原生库 strings + 本机局域网 Bonjour 实测 + 荣耀官方文档检索 + FileWeave 仓库现状盘点（main @ 69cd26b6，与 origin/main 一致）。仅调研，不含任何已实施改动。
- 需求语境：用户目标从「手机推送、Mac 接收」升级为「**日常不操作手机，直接在 Mac 上浏览/取用手机文件**」，并希望体验不弱于 HonorConnect（同等甚至更好）。
- 前置报告：[honorconnect-protocol-assessment.md](honorconnect-protocol-assessment.md)（协议栈逆向与推送路径）、[honorconnect-receive-visual-design.md](honorconnect-receive-visual-design.md)（接收中心视觉方案）。

## 结论先行（TL;DR）

1. **推荐主路径：泛化 FileWeave 自有通道**。仓库现状远比预期接近目标——Android 端 `FolderBridgeServer` 已是「SAF 任选目录 + Bearer 鉴权 + TLS 证书固定 + Range/206 随机读」的通用 HTTP 文件服务（并非录音专用），Mac 端传输层同样通用。缺的是：Mac 面板的通用「手机文件」浏览 UI（现在被 `Recording` 模型绑死）、Android 端懒加载列表端点（现在是全量递归 walk，扛不住整盘共享）、按文件类型的 MIME。补齐后即可做到「一次性设置 → 之后全程在 Mac 取文件/直接播放视频」，体验可超 HonorConnect（同面板、可流播、带回收站）。
2. **推荐补充：荣耀分享「共享至电脑」（官方 SMB）**。手机一次性开启后，Mac 经 smb:// 用手机上显示的账号密码访问，零协议实现（可 `mount_smbfs` 挂载后浏览）。价值：不依赖手机装 FileWeave、系统级稳定；风险：官方排查文档提示可能需 SMB 1.0/CIFS 兼容（macOS 客户端默认已禁 SMB1，需实测用户的手机），共享范围与息屏存活策略需验证。
3. **不推荐：逆向 HonorConnect 的拉取协议**。本轮用 rea 定位到其拉取实现为 HnOfficeCenter 的 `CrossDevFileService` 插件（`DccDFS` 分布式文件系统业务），走 pcSync SDK 的分块断点续传引擎（`/cfs/files`、`/cfs/blocks?fid=`、`resume_offset`）——仍架设在「SoftBus 发现 + HiChain 账号配对 + ECDH/GCM」的整套加密栈上，结论与第一轮一致：账号绑定不可绕过，逆向是持续对抗。

### 路径评级总表

| # | 路径 | 一句话 | 评级 |
|---|---|---|---|
| C | FileWeave 自有通道泛化 | SAF 任选目录的鉴权 HTTP 共享已就绪，补 Mac 浏览 UI 与懒加载 | **推荐（主路径）** |
| B | 荣耀分享「共享至电脑」(SMB) | 官方能力，Mac 挂载即用，零逆向 | **推荐（补充）** |
| A | 逆向 CrossDevFileService/DccDFS/cfs | 账号绑定的加密协议栈，无法绕过 | **不推荐** |

## 一、HonorConnect 拉取链路逆向（Path A 证据）

- **插件架构**（`OfficeCenter/config/plugincfg.xml`）：HnOfficeCenter 以插件承载各业务，与本需求直接相关的是 **`CrossDevFileService`**（跨设备文件服务）；同列表还有 `PCSyncPlugin`、`HonorFileShareServicePlugin`（互传）、`DeviceManager`（clsid 为 `hwa.plugin.devicemanager`，华为血统可见）、`dmsdps`、`HnHotSpotService` 等。
- **业务关联**（`libDataSyncCommon.dylib` strings）：`DccDFS`、`HnCrossDevFileMgr`、`connectedDeviceList` 同段出现——拉取式文件管理挂在 DccDFS（分布式文件系统）业务下，与 `defaultConnectProcedure.json` 中 PC↔Phone 的 `HnCrossDevFileMgr` 业务一致。
- **传输引擎**（`Frameworks/libpcSyncSDKLibrary.dylib` strings）：
  - `/cfs/files?path=`（列目录/元数据）、`/cfs/blocks?fid=`、`/cfs/blocks?path=`（分块取内容）、`/cfs/operations?opCode=1&action=unlock&path=`（文件操作）；
  - 断点续传引擎日志：`resume download task check passed, resume_offset…`、`slice_size`、`down data … offset/end`；SQLite `block` 表（id/orderIndex/offset/size/locId/objId/state）管理分块。
  - 会话与密钥：`/auth?deviceId=` → `/basic/security/encryption/publicKey`/`workingKey`（ECDH+AES-GCM-256，见第一轮报告）。
- **JS 编排**（rea `analyze-javascript-application`，证据 `rea-filemgr.json` / bundle `rea-evidence-3`：4 文件、50,679 AST 节点、178 findings）：`file-manager-main` 枚举含 `MSG_DISTRIBUTED_DEVICE_ONLINE/OFFLINE`、`FEATURE_FILE_SHARE`、`FILEMANAGER_ROUTER`；`HnOfficeSdk.node`（Mac 主桥）暴露 `RegisterRecvHonorShare*` 系列回调（DeviceFound/Lost、SendPercent、UpdateTaskSpeed…），文件浏览 UI 在渲染进程。另注意 `FileManagerUiSdk.node` 实为 **Windows 构建误打包**（含 `CreateFileW`、`IsHuaweiDeviceFromReg` 导入），Mac 端逻辑全在 HnOfficeCenter。
- **结论**：拉取协议 = 「SoftBus 会话 + 账号配对」之上的 cfs HTTP 分块引擎。第一轮已论证账号链路不可复刻，本轮证据使「不推荐」更具体，不再展开。

## 二、荣耀分享「共享至电脑」（Path B）

- 官方流程（[荣耀分享快速分享文件](https://www.honor.com/cn/support/content/zh-cn15819241)）：手机「荣耀分享」开启 **共享至电脑** → 手机显示「电脑端显示名 + 访问用户名/密码」→ 电脑资源管理器输入 `\\显示名`（Mac 为 `smb://显示名`）访问。
- [官方 FAQ](https://www.honor.com/cn/support/content/zh-cn15870906)：PC 网络位置不显示时可直输 `\\名字`；[荣耀俱乐部案例](https://club.honor.com/cn/thread-28507511-1-1.html)提到需在 Windows 启用「SMB 1.0/CIFS」——**服务端 SMB 版本兼容性存疑**，macOS 11+ 默认禁用 SMB1 客户端（需 `nsmb.conf` 显式开），必须在用户手机上实测。
- 本机实测（dns-sd 浏览 10 秒）：LAN 内无手机 SMB 播报（仅 MacBook 在播 `_smb._tcp`）——荣耀该功能不依赖 Bonjour 广播（Windows 走 NetBIOS/WS-Discovery），且用户手机当前未开启该开关。顺带发现网内有一台 `_adb-tls-connect` 设备（开无线调试的 Android，疑似目标手机）。
- **FileWeave 集成方式（若实测可行）**：`mount_smbfs //user@显示名/共享名 /Volumes/…` 系统挂载 → 面板内浏览挂载点或「在 Finder 打开」。不需要自研 SMB 客户端（macOS 无公开 SMB 浏览 API，自研库不成熟，与「复用成熟实现」的项目立场相悖）。
- 待用户实测项：手机开启后 ① Mac Finder `smb://` 能否直连（SMB2 是否可用）；② 共享范围（仅内部存储？哪些目录）；③ 息屏/锁屏后是否存活。

## 三、FileWeave 自有通道现状（Path C 盘点，main @ 69cd26b6）

### 已具备（无需新协议）

- **Android 服务端**（`app/.../folderbridge/FolderBridgeServer.kt`，680 行）：HTTP/1.1 直写 Socket；`Bearer` 常量时间比较鉴权；路径全部相对共享根、`isSafeRelativePath` 防穿越；**Range/206 单区间随机读**（与 Mac bridge.py 同语义）；`/status` `/tree` `/file` 回收站 `trash/restore`。
- **共享目录是用户 SAF 任选的**（`FolderBridgeSettings.kt`）：`ACTION_OPEN_DOCUMENT_TREE` + 持久化授权；来源分级 USER_CHOSEN > AUTO_DETECTED(录音目录) > NONE——**「共享任意目录」在设置层已实现**，非录音专用。
- **变更感知**：`FolderBridgeObserver` 监听并去抖 rescan。
- **Mac 传输层**（`FolderSource.swift`）：Bonjour（`NetServiceBrowser`）发现 + `PinningDelegate` 证书固定 + Bearer + 禁重定向；`PairedDeviceStore` 管理配对设备。

### 泛化缺口（按优先级）

1. **Mac UI 去录音化**：`FileWeaveFolderSource: RecordingSource`、`Recording` 模型、`RecorderMenuView` 全按录音语义组织。需要通用「手机文件」页（目录树浏览、任意类型、下载/在 Finder 显示、流式播放），挂载点可直接用视觉报告预留的分段 Picker 槽位（`MenuView.swift:53-55`）。
2. **Android 懒加载列表**：`FolderBridgeFileBackend.listFiles()` 是全量递归 `walk()`，整盘共享不可行；需新增按目录的 `list?dir=` 端点（observer 机制可保留做增量提示）。
3. **MIME 修正**：`serveFile` 写死 `Content-Type: audio/mp4`，按扩展名映射后流播/预览才正确。
4. **多根/整盘**：当前单共享根；可选支持多目录或存储根（配合 2 的懒加载）。
5. **体验加分项（超越 HonorConnect 的点）**：AVPlayer 直连手机端点流式播视频（Range 已就绪）；面板内搜索；回收站删除/还原（官方无）；与「发送到 Mac」推送通道同面板闭环。

### 「不用操作手机」的达成方式

一次性设置：手机上选共享目录（SAF）并保持共享前台服务运行 → 之后全部操作在 Mac 面板完成。与 HonorConnect 的差别仅是初始配置入口（官方为账号登录 + NFC/发现，本项目为选目录 + 配对），日常使用完全等同或更优。

## 四、建议路线

1. **主线走 Path C**：按上节缺口 1→5 排期；先做「单目录通用浏览 + 下载 + 流播」，再做懒加载与多根。
2. **并行实测 Path B**（需要手机在手，约 10 分钟）：开启共享至电脑 → Mac Finder `smb://显示名` 连接 → 记录 SMB 版本/共享范围/息屏存活。若 SMB2 可用，可做「快捷挂载」小功能作为非 FileWeave 设备的兜底。
3. Path A 维持否决，本轮逆向证据已归档于 `/tmp/honorconnect-research/`（rea 证据包 rea-evidence-3 与 JSON 摘要）。

## 五、来源清单

本机/仓库证据：

- `/Applications/HonorConnect.app/Contents/OfficeCenter/config/plugincfg.xml`（CrossDevFileService 等插件清单）
- `Frameworks/libpcSyncSDKLibrary.dylib`、`OfficeCenter/libDataSyncCommon.dylib` strings（cfs 分块引擎、DccDFS 关联）
- `Resources/app.asar.unpacked/dist/Release/HnOfficeSdk.node` strings（JS↔原生回调 API 面）
- rea 6.8.0 定向分析产物：`/tmp/honorconnect-research/rea-filemgr.json`、`rea-evidence-3`（50,679 AST 节点/178 findings）
- FileWeave：`app/src/main/java/me/zhanghai/android/files/folderbridge/*`（Server/Settings/Backend/Observer）、`mac/Sources/FileWeaveMacKit/recordings/FolderSource.swift`
- dns-sd 实测记录：`/tmp/honorconnect-research/{smb,svc}-browse.txt`

网络来源：

- [荣耀分享快速分享文件（官方）](https://www.honor.com/cn/support/content/zh-cn15819241)
- [PC 网络位置不显示手机（官方 FAQ）](https://www.honor.com/cn/support/content/zh-cn15870906)
- [SMB 1.0/CIFS 排查案例（荣耀俱乐部）](https://club.honor.com/cn/thread-28507511-1-1.html)
