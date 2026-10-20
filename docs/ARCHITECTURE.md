# 技术路线

以下为设计建议，不代表已实现。

## 保留基础

沿用 `app` 模块中的 Kotlin、Views / ViewBinding、ViewModel / LiveData 和现有 NIO 文件系统 Provider，不做全量 UI 或 Compose 重写。源码 namespace 保留 `me.zhanghai.android.files`，安装标识独立为 `org.fileweave.android`。

优先关注源码位置：

- `filelist/`：列表与打开文件流程。
- `file/`：文件类型、URI 和跨应用访问。
- `provider/`：本地、文档树、SMB、SFTP、WebDAV 等文件系统。
- `viewer/`：已有查看器，可作为视频功能的组织参照。
- `filejob/`：后台文件任务、冲突和错误处理。
- `storage/`：存储源配置与连接入口。

## 视频

候选实现为 Media3 / ExoPlayer 内置播放；先验证本地 content URI，再验证现有网络 Provider 是否能提供带偏移的随机读取。必要时实现适配 `open/read/close` 的 DataSource，将播放请求的 offset 映射到文件读取位置。

第一轮优先播放、暂停、拖动、外挂字幕、音轨和播放进度。HDR、特殊编码、字幕复杂度和大码率表现需真机验证；若 Media3 覆盖不足，再评估 libVLC，检查二进制体积与许可。

参考：https://developer.android.com/reference/androidx/media3/datasource/DataSource

## 加密保险库

复用成熟、支持随机访问的加密格式，不自创密码算法或容器格式。候选为 DroidFS 使用的 gocryptfs 路线，以及 Cryptomator 格式；前者优先研究 Android 视频路径，后者优先研究 Mac 共用加密库。

库选型前完成：Android 可用性、随机读取、文件名保护、Mac 支持、依赖许可和恢复策略验证。不能把整个第三方应用直接当作可嵌入 SDK。

保险库应有明确的锁定会话：

- 密码解锁为基础；生物认证仅用于保护本机解锁材料，保留密码恢复路径。
- 锁定时关闭文件句柄、撤销可撤销的授权、停止播放并释放解密状态。
- 兼顾后台播放与自动锁定：默认安全锁定；确需后台播放时显示明确状态并由用户启用。
- 播放、缩略图和字幕处理避免明文磁盘缓存；缩略图要么不缓存，要么随库加密。
- 导出和分享明文必须由用户明确触发；应用无法阻止外部接收程序另存。
- 不默认永久暴露保险库到系统 DocumentsProvider。
- 不承诺删除闪存文件等于物理擦除，不把导入后仍存在的源文件误认为已受保护。

参考：https://github.com/hardcore-sushi/DroidFS
参考：https://github.com/cryptomator/android/issues/62

## Mac 文件与剪贴板

阶段一直接使用 Mac SMB 服务与 Android 系统分享入口调用 KDE Connect / LocalSend，不依赖这些应用不存在的嵌入式 SDK，也不宣称已自动配对或接收回调。

阶段二若有明确体验缺口，再做 Mac 菜单栏配套程序。候选为 Swift / SwiftUI；文件传输优先评估复用公开协议。自有协议需要配对、设备认证、传输加密、取消和重试；发现设备不等于信任设备。

文本传输和文件共享分别设计。Android 默认采用前台操作或快捷磁贴；Mac 可在用户开启后监测剪贴板变化。默认不保留剪贴板历史，避免自动发送密码和一次性验证码，需处理循环回传、重复文本、离线状态及消息过期。

普通 Android 后台应用读取剪贴板受系统限制，自研也无法保证绕过。Root、ADB 特权或替换输入法不作为默认使用条件。

参考：https://developer.android.com/about/versions/10/privacy/changes#clipboard-data
参考：https://userbase.kde.org/KDEConnect#Clipboard
参考：https://github.com/localsend/localsend

## 验证重点

重点验证本地和网络视频拖动、锁定后旧 URI / 文件句柄访问、进程被杀后的明文残留、损坏密文处理、断网取消和重试。互通加密库先做复制与单端修改流程，不默认同时在两端写同一个库。
