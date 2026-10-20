# FileWeave

文件有序，设备无间。

FileWeave 是以 [Material Files](https://github.com/zhanghai/MaterialFiles) 为基础的 Android 文件管理器分支，目标是在保留原有浏览、文件操作和网络存储体验的基础上，整合视频播放、加密保险库，以及 Android 与 Mac 的文件和文本互通。

## 当前状态

项目已立项，处于基础分支初始化阶段；新增播放器、加密保险库和 Mac 配套客户端尚未实现。

本次初始化：

- 保留 Material Files 完整 Git 历史，使用 `upstream` 远端跟踪上游。
- 独立应用标识 `org.fileweave.android`，版本 `0.1.0-dev`，各语言显示名称统一为 FileWeave。
- 保留上游源码命名空间，减少后续合并冲突；Provider authority 由独立应用标识生成。
- 移除上游 Firebase 插件、依赖、配置和初始化代码。
- 保留 GPL 许可、原作者署名和上游说明。

## 产品范围

| 场景 | 当前基础 | 开发目标 |
| --- | --- | --- |
| 日常文件管理 | 继承上游文件浏览、搜索、复制移动和压缩能力 | 保持原有操作体验 |
| Mac 文件访问 | 继承上游 SMB / SFTP / WebDAV 实现 | 连接指引、可靠的大文件传输与播放 |
| 视频 | 继承上游打开文件方式 | 内置播放、字幕、进度记忆和网络随机读取 |
| 敏感文件 | 尚无新增加密保险库 | 成熟格式、解锁、自动锁定、加密视频按需读取 |
| 剪贴板 | 尚无互通实现 | 先提供用户主动发送文本，后评估 Mac 配套程序 |

这些继承能力尚未在 FileWeave 分支进行设备回归测试。应用锁和隐藏文件不等同于内容加密；普通 Android 应用不能承诺任意后台全自动读取剪贴板。

## 文档

- [项目定位与范围](docs/PROJECT.md)
- [技术路线与待验证决策](docs/ARCHITECTURE.md)
- [阶段任务和验收标准](docs/ROADMAP.md)
- [开发、构建与验证记录](docs/DEVELOPMENT.md)
- [上游来源和维护约定](docs/UPSTREAM.md)

## 开发

使用仓库自带的 Gradle Wrapper。JDK、SDK、NDK 要求见开发文档和 Gradle 配置。

```sh
./gradlew :app:assembleDebug
./gradlew :app:lintDebug
```

本仓库目前仅在本地创建，尚未创建或发布 FileWeave 的 GitHub 远端仓库、安装包或正式版。关于页面暂时保留上游项目和作者链接，待有自己的发布地址后再增加分支入口。

## 来源与许可

基于 Hai Zhang 及 Material Files 贡献者的工作，沿用 GNU GPL v3 或更新版本；见 [LICENSE](LICENSE)。原版说明保留于 [README.upstream.md](README.upstream.md) 和 [README.upstream.zh-CN.md](README.upstream.zh-CN.md)。FileWeave 是独立分支，不代表上游官方版本。
