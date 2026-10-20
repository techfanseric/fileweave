# 当前技术结构

## 文件管理与播放

保留 Kotlin、Views / ViewBinding、ViewModel / LiveData 和现有 NIO 文件系统 Provider。`filelist/` 默认把普通视频路由到 `viewer/video/VideoViewerActivity`，保留原有外部打开方式。

播放器使用 AndroidX Media3 1.11.1。`PathDataSource` 把 Media3 的位置读取映射到已有 NIO ByteChannel，包括 SMB/SFTP；对可直接访问的 Linux 文件先使用本地只读通道，避免上游将应用私有缓存误判为需要 Root。

`VideoGestureLayout` 在播放根布局统一处理触摸方向，使用当前窗口宽高归一化位移；一次手势锁定为进度、亮度或音量。横拖松手才 seek，取消／旋转／退出先恢复状态再释放播放器。双击由 GestureDetector 区分于单击，左右分别后退／前进 10 秒。手势反馈独立于控制条，调节时不展示标题和控制条。亮度仅修改窗口属性，媒体音量通过 AudioManager 调整且不请求系统音量 UI。

`BridgeVideoActivity` 是不导出的独立入口，使用证书固定的 OkHttp DataSource。普通外部 VIEW Intent 不能指定保险库读取上下文或接入 Mac 配对凭据。

## 文件内容加密

选型为 MIT 许可的 libgocryptfs，源码版本和改动见 third_party/README.md。复用标准 gocryptfs 配置、AES-256-GCM 分块认证加密、加密文件名和 scrypt（logN 16）密码派生。JNI 仅负责字符串、缓冲区及函数调用转换，不实现密码算法。

`VaultSession` 串行化所有 native 调用，并以 generation 校验读取会话，锁定先关闭已登记文件句柄再释放加密上下文。不得让 native close 与 read/write 并发或重复关闭句柄。密码只用于解锁，不写入偏好或磁盘。

密文存于 noBackupFilesDir/vault。导入先写临时密文，再原子重命名；不覆盖重名文件，不删除来源明文。中途锁定或进程终止留下的临时密文在下次解锁清理。

`VaultDataSource` 支持长整型偏移和按块读取，供库内视频使用。预览内部入口不导出，FLAG_SECURE 禁止系统截图；离开预览停止并锁定，ProcessLifecycleOwner 也在应用退后台时锁定。密码字段禁用状态保存与自动填充。

小型图片／文本在内存预览；显式明文导出通过系统文档选择器，返回时需要重新解锁。明文一旦导出就不受保险库控制。备份是仅包含密文与配置的标准 ZIP；恢复限制解压大小、条目数和路径，使用 staging 目录，不覆盖已有保险库。

## Mac 文件与文本互通

Mac Swift 菜单栏程序启动 Python 标准库 HTTPS 服务；只暴露明确的 shared 目录和接收用 inbox。默认端口 8787。Swift 随应用启动连接服务并监视子进程退出，失败退避后自动拉起；Bonjour 发布失败也会自动重试。菜单只保留状态、共享／收件文件夹、日志与退出应用。

Android 以固定证书、主机校验与 bearer token 认证每次请求，禁止重定向，以免凭据发送到其他主机。配对配置通过 Android Keystore 的 AES-GCM 加密并存入 noBackupFilesDir。Mac 的证书私钥及配对令牌仅放在权限受限的 `.state/`，由 Git 忽略。

Bonjour 服务类型为 `_fileweave._tcp.`，TXT 仅发布协议版本与证书 SHA-256；地址来自系统解析。Android 串行解析附近服务，已配对设备仅在完整指纹匹配时使用已有凭据自动重连，成功后更新地址。发现随页面进入／离开启动和停止。前台自动化循环处理重连和每 5 秒的共享列表更新，仅数据变化时重建列表，后台停止轮询；连接失败按单调时钟从 1 秒退避至 30 秒，避免重试风暴。经过原证书认证的服务返回 401 时才清理已失效凭据并重新请求人工信任；不会自动重放文件上传、下载或剪贴板操作。

首次自动发现配对使用 120 秒内存请求，随机 nonce 绑定 TLS 指纹生成两端核对码。Mac 私有状态目录中的原子请求／决定文件连接 Python 服务与 Swift 弹窗，批准前没有访问令牌；批准后给本次请求发放独立临时 token。Android 还需用户确认已亲自在 Mac 核对并允许，防止陌生广播端自行声称已批准而被静默信任。手机确认后第一次认证请求才把设备 token 持久化在 Mac，手机通过现有 Keystore 保存。取消／超时清理未完成请求，并限制匿名请求数量、速率和正文大小。已保存的旧凭据仍可使用，但已移除二维码／配对链接 UI、Android 外部配对链接入口、手动启停、刷新和重置操作。

协议入口：

- POST /v1/pair/start、/v1/pair/poll、/v1/pair/cancel：无需原访问令牌的限量配对流程；poll/cancel 必须提供本次随机 nonce。
- GET /v1/status：协议与服务身份。
- GET /v1/files：共享目录中的普通文件。
- GET /v1/file?name=：下载，支持 HTTP Range。
- POST /v1/file?name=：接收文件，支持流式／chunked 请求及原子重名处理。
- GET /v1/clipboard、POST /v1/clipboard：用户主动触发的文本读写。

剪贴板不轮询、不保存历史。Android 读剪贴板发生在前台按钮操作时，符合普通后台应用不能持续读取剪贴板的系统限制。

## 当前边界

尚无指纹解锁、Mac 解锁 gocryptfs、后台队列、断点续传、双向目录同步或互联网中继。Mac 程序当前依赖本机 Python/Swift/OpenSSL 和仓库路径。设备 IP、证书或令牌变化需要重新配对。独立安全审计尚未进行，实际验证范围见 VALIDATION.md。
