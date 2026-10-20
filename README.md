# FileWeave

文件有序，设备无间。

以 [Material Files](https://github.com/zhanghai/MaterialFiles) 为基础的 Android 文件管理器，保留原有浏览、文件操作和网络存储体验，新增内置视频播放、文件内容加密保险库及 Android ↔ Mac 文件与文本互通。

当前版本：`0.2.0-dev`，安装标识：`org.fileweave.android`。

## 已接入功能

- **内置播放器**：本地和网络文件按需读取，播放、拖动、倍速、字幕及音轨选择，普通视频记忆进度，保留外部播放器入口。
- **文件内容加密**：标准 gocryptfs 格式，AES-256-GCM 内容加密并保护文件名；独立密码、自动锁定、锁定后读取会话失效。
- **库内查看**：视频按块解密播放；小型图片和 UTF-8 文本在内存预览。预览不先完整导出为明文，敏感窗口禁止系统截图。
- **备份与恢复**：导出／恢复密文 ZIP；用户主动选择后可导出明文副本。导入不自动删除来源明文。
- **Mac 配套程序**：菜单栏配对二维码、HTTPS 文件传输、共享文件直接播放、主动发送／读取剪贴板文本。
- **保留基础**：Material Files 原有 SMB / SFTP / WebDAV 等存储支持，完整上游历史及原作者署名；已移除 Firebase 接入。

Android 文件列表右上角菜单进入「加密保险库」或「连接 Mac」，文件行菜单可直接加密或发送文件。

## 使用与开发

[完整使用说明与当前边界](docs/FEATURES.md)。Mac 开发版运行 `./mac/run.sh`；需要本机 Python 3、OpenSSL 和 Swift 工具链，当前依赖仓库路径。

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
python3 -m unittest discover -s mac -v
```

Android 构建还需要 Go，Gradle 会调用 `scripts/build-vault.sh` 编译 vendored libgocryptfs。工具链及测试记录见 [开发文档](docs/DEVELOPMENT.md)。

## 文档

- [产品范围](docs/PROJECT.md)
- [技术路线](docs/ARCHITECTURE.md)
- [阶段任务](docs/ROADMAP.md)
- [验证记录](docs/VALIDATION.md)
- [上游维护](docs/UPSTREAM.md)
- [第三方代码与许可](third_party/README.md)

当前为开发版，详细功能及验收限制以使用说明和验证记录为准。Android 不进行后台全自动剪贴板采集；当前没有指纹解锁、后台传输队列、断点续传或 Mac 端保险库解锁。

## 来源与许可

基于 Hai Zhang 及 Material Files 贡献者的工作，沿用 GNU GPL v3 或更新版本，见 [LICENSE](LICENSE)。gocryptfs 实现的 MIT 许可与原文件头保留在第三方源码中。FileWeave 是独立分支，不代表上游官方版本；原说明见 [英文](README.upstream.md)／[中文](README.upstream.zh-CN.md)。
