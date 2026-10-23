# 更新记录

## 0.4.0-dev — 开发中

合并原独立项目「荣耀录音监视器」，删除其 ADB 通道与无鉴权手机 HTTP 桥接，能力并入 FileWeave 配对体系。

### WebDAV 服务器与 SMB 决策（待真机验收）

- 新增 WebDAV 服务器（`webdavserver/` 包，自研最小协议核零新依赖：OPTIONS/PROPFIND/GET/HEAD/PUT/DELETE/MKCOL/MOVE/COPY、Range、Basic Auth、keep-alive；照 FTP 服务器模式的前台服务与设置页；抽屉入口）。协议核 30 个 JVM 测试含真实 socket 全栈往返与 UTF-8 文件名。
- SMB 服务器经评估暂缓：无开源 JVM SMB2/3 服务端、Win11 24H2 签名强制、445 特权端口与非 root 冲突（详见 docs/research/android-smb-server-assessment.md）；对外共享由 FTP+WebDAV 覆盖。

### 录音目录自动识别（真机已验证）

- 打开共享开关即用：优先用户显式选择的 SAF 目录；未选择时按 Recordings/Call → Recordings → Sounds → Music/Recordings → Audio/Recordings 顺序探测首个含音频的目录（实测荣耀 MagicOS 命中 Sounds，递归含 CallRecord 通话录音）；探测基于「所有文件访问权限」，新增 File API 后端与 SAF 后端同构（.Trash 复制＋校验语义、路径逃逸双保险），21 个 JVM 测试覆盖。
- 设置摘要显示「自动识别：目录名」；手动选择仍可覆盖。

### 互通历史（待真机验收）

- 新增 BridgeHistoryStore（应用私有目录 JSON，上限 100 条，只存文件名与 80 字符预览）；「Mac 剪贴板」「Mac 共享文件」两页改造为：一行连接状态 → 紧凑操作区 → 倒序互通历史 → 清空历史；文件传输按实测字节数记录。

### 文件列表与关于页（按用户方向调整，待真机验收）

- 无可用 root 时抽屉不再显示「根目录」项（应用启动后台检测一次，策略为「从不使用 root」时直接隐藏）。
- 路径面包屑并入标题栏：去掉独立路径行，存储根显示「文件」、进入子目录后标题位置即面包屑导航（可点击逐级返回、长按复制路径），字号统一缩小，移除常驻的文件夹/文件计数副标题，节省一行纵向空间；搜索展开时自动让位，多选模式由不透明工具条自然覆盖。
- 关于页：作者署名 Eric Yim（链接 github.com/techfanseric），新增「基于 Hai Zhang 的 Material Files」致谢行（链接上游仓库），移除原作者个人主页/Twitter 链接；项目链接指向 github.com/techfanseric/fileweave。
- 命名去 Mac 化：「Mac 共享文件」抽屉项与页面更名为「共享文件」（文件共享服务局域网内任意设备）。

### 常驻 Mac 互联（按用户方向调整，真机已验证）

- 新增 `BridgeConnectionService` 常驻前台服务：后台自动发现、零接触配对、保持连接、每 5 秒刷新共享文件列表并同步 Mac 剪贴板到本机；设置「保持与 Mac 的连接」默认开启，通知栏「关闭」与设置开关等效，关闭后保持关闭直至重新开启。
- 剪贴板互通边界：Mac→手机由服务自动写入（后台服务可写剪贴板）；手机→Mac 仍需在前台页面主动发送——系统只允许前台应用读取剪贴板，不承诺后台自动读取。
- 侧边栏改为以「Mac 剪贴板」「Mac 共享文件」两个入口为先（新增 `ClipboardActivity` 剪贴板页），隐藏原标准目录分类（相册、音乐、视频等；目录本身仍可从文件列表与设置访问）。
- 原「连接 Mac」页面瘦身为「Mac 共享文件」页：仅承担发送／下载／播放等用户操作，发现与配对全部移入服务；`BridgeActivity` 不再管理发现生命周期。

### 局域网自动配对（真机已验证）

- 手机端：Mac 侧批准后直接保存凭据并连接，不再弹出「已在 Mac 核对」确认对话框；保留单台 Mac 才自动配对、TLS 证书固定与退避限制。
- Mac 端（bridge.py）：`.state/auto-approve` 标记文件存在时，每个待处理配对请求在 1 秒内自动批准（与弹窗批准共用设备数、频控与 120 秒过期限制），实现零接触配对；删除标记即恢复弹窗人工批准。默认不创建该标记。

### 手机文件夹共享（Android，`folderbridge`）

- 抽屉菜单新增「共享录音文件夹给 Mac」：SAF 授权一个目录，前台服务（specialUse，与 FTP 服务器同类）对外提供 HTTPS 服务，快速设置磁贴可开关。
- TLS 证书由 Mac 经已配对通道签发（PKCS#10 CSR，私钥不出 Android Keystore）；所有端点要求配对 Mac 的 Bearer 令牌；NSD 发布 `_fileweave-folder._tcp.`，广播不含凭据。
- 删除走目录内自建 `.Trash`（复制＋大小校验＋失败回滚），支持还原；下载支持 HTTP Range。

### 真机回归发现并修复的问题

- `folder_bridge` 通知渠道从未注册，启动前台服务即抛 `CannotPostForegroundServiceNotificationException` 崩溃；已加入 `AppInitializers` 渠道创建。
- AndroidKeyStore 密钥原仅允许 PKCS1 与显式 digest 集合，Conscrypt TLS 服务端握手需要 RSA-PSS 与 NONEwithRSA 上行调用，触发 `INCOMPATIBLE_DIGEST`；密钥规格已改为允许 `DIGEST_NONE`、SHA-256/384/512 与 PKCS1+PSS 填充。

### Mac 菜单栏程序（`mac/` 重组为 SwiftPM 包）

- 左键面板新增「录音」页：浏览／搜索／水位线新增标／回收站删除与还原／下载／本地转录（sherpa-onnx）／显式点击的 AI 摘要（百炼，唯一出网点）。
- 发现改走 `_fileweave-folder._tcp.` + 配对记录 `folderFp` 指纹匹配；删除 UDP 广播与双通道切换。
- bridge.py 新增专用 CA 与 `POST /v1/folder-key` 签发端点（仅已配对设备可用）；登录项、`install.sh` 安装与数据自动迁移自旧目录。
- 原 `mac/MenuBar.swift` 单文件结构移入包内；`run.sh` 体验不变。

### 验证与边界

脱机：Android 37 个 JVM 测试 + lint 0 error，Swift 30 个测试，Python 18 个协议测试，双路代码评审并修复全部 P1。真机回归（TLS 握手、NSD、SAF 回收站、前台服务行为、数据迁移）待设备可用后进行，见 [验证记录](docs/VALIDATION.md)。版本 versionCode 45。

## 0.3.1-dev — 开发中

- Mac 与 Android 均移除手动配对、搜索、启停、刷新与重置入口；保留首次信任核对和正常文件／剪贴板操作。
- Android 进入连接页面后自动发现、重连和更新共享列表，连接失败退避重试，授权失效后自动重新请求批准。
- Mac 自动维持连接服务，子进程异常退出后自动拉起并恢复 Bonjour 广播。
- 修复断线重连或页面退出时，主线程关闭 TLS 连接可能导致的崩溃。
- 多台 Mac 时只需选择要使用的设备；不增加后台剪贴板采集，也不自动重放失败的文件传输。

## 0.3.0-dev — 开发中

- Bonjour 局域网发现；已配对 Mac 自动连接、地址变化后按原证书重新定位。
- 首次在 Mac 核对配对码并批准，手机确认完成后保存信任；为新配对手机单独签发凭据。
- 匿名配对请求限流、2 分钟过期、拒绝／取消处理；发现广播不含访问凭据。
- 保留原二维码／链接入口；发现仅在 Android 连接页面前台运行，不增加后台剪贴板读取。

## 0.2.1-dev — 2026-10-20

首次公开预发布，Android versionCode 42，安装标识 `org.fileweave.android`。

### 播放器体验

- 全屏播放，标题和控制条点按显示、无操作后收起；字幕选择与外部播放器收进菜单。
- 任意位置横向拖动调节进度，显示目标时间、总时长和变化量，松手跳转。
- 左侧上下调窗口亮度，右侧上下调媒体音量。
- 左侧双击后退 10 秒，右侧双击前进 10 秒；边界限制在视频范围内。
- 按当前屏幕坐标识别横竖屏方向；锁定本次手势类型，调节时只显示对应反馈。
- 保留原播放／暂停状态，取消手势不提交进度，旋转或退出前取消未完成的调节。

### 本次发布包含的已有能力

- Material Files 的基础文件操作及 SMB / SFTP / WebDAV 等存储支持。
- Media3 内置播放、随机读取、进度记忆、字幕、倍速及音轨选择。
- 标准 gocryptfs 文件内容加密、密码解锁、自动锁定、库内按块解密播放、密文备份恢复。
- Mac Swift/Python 菜单栏配套程序，证书固定 HTTPS 文件互传、共享视频播放与前台主动剪贴板文本传输。

### 验证与边界

Android 构建、4 个 JVM 测试和 lint 通过（0 error / 937 warning）；API 36 真机及 API 34 模拟器横竖屏手势回归通过。加密和随机读取已有功能测试，尚无独立安全审计；完整范围见 [验证记录](docs/VALIDATION.md)。

APK 使用开发签名，Mac 配套程序需从源码运行。没有指纹解锁、后台传输队列、断点续传、后台自动剪贴板同步或 Mac 端保险库解锁。卸载会删除本机保险库，请自行保管密码和密文备份。

## 0.2.0-dev — 2026-10-20

本地开发里程碑：接入内置播放器、gocryptfs 内容加密保险库、Mac 文件及文本桥接，完成核心真机／模拟器验证。未单独公开发布。

## 0.1.0-dev — 2026-10-20

本地立项：以 Material Files 为基础创建 FileWeave，独立应用标识、移除 Firebase，保留上游历史与许可，建立项目文档。未单独公开发布。
