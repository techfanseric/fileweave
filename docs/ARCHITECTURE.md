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

Mac Swift 菜单栏程序启动 Python 标准库 HTTPS 服务；只暴露明确的 shared 目录和接收用 inbox。默认端口 8787。二维码/链接包含主机、端口、证书 SHA-256 和随机令牌，Android 经明确确认并完成 TLS 请求后保存配对。

Android 以固定证书、主机校验与 bearer token 认证每次请求，禁止重定向，以免凭据发送到其他主机。配对配置通过 Android Keystore 的 AES-GCM 加密并存入 noBackupFilesDir。Mac 的证书私钥及配对令牌仅放在权限受限的 `.state/`，由 Git 忽略。

协议入口：

- GET /v1/status：协议与服务身份。
- GET /v1/files：共享目录中的普通文件。
- GET /v1/file?name=：下载，支持 HTTP Range。
- POST /v1/file?name=：接收文件，支持流式／chunked 请求及原子重名处理。
- GET /v1/clipboard、POST /v1/clipboard：用户主动触发的文本读写。

剪贴板不轮询、不保存历史。Android 读剪贴板发生在前台按钮操作时，符合普通后台应用不能持续读取剪贴板的系统限制。

## 当前边界

尚无指纹解锁、Mac 解锁 gocryptfs、后台队列、断点续传、双向目录同步或互联网中继。Mac 程序当前依赖本机 Python/Swift/OpenSSL 和仓库路径。设备 IP、证书或令牌变化需要重新配对。独立安全审计尚未进行，实际验证范围见 VALIDATION.md。
