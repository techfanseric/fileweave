# 验证记录

日期：2026-10-20。此记录说明实际完成的验证，不等同于独立安全审计或全设备兼容性认证。

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
