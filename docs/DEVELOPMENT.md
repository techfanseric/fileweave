# 开发与构建

## 工程入口

Android 单模块 Gradle 项目，模块 `app`。使用仓库自带 `./gradlew`，不使用系统 Gradle 替代。

当前继承的工具链配置：

| 项目 | 版本 / 配置 |
| --- | --- |
| JDK | 本地与 CI 使用 21 |
| Gradle Wrapper | 9.6.0 |
| Android Gradle Plugin | 9.4.1 |
| Kotlin 插件依赖 | 2.4.20 |
| compileSdk | 37，minor 2 |
| targetSdk / minSdk | 36 / 23 |
| Build Tools | 37.0.0 |
| NDK | 30.0.16248370 |

这些版本来自导入的上游配置；本地已补齐对应工具链并完成首次构建。不要只为适应本机环境而随意降低 SDK 或升级整套依赖。

## 本地配置

安装所需 Android SDK、Build Tools、NDK 及 CMake 后，通过 `local.properties` 设置 `sdk.dir`，或使用有效的 Android SDK 环境变量。`local.properties` 已被忽略，不提交个人 SDK 路径。

```sh
./gradlew --version
./gradlew :app:assembleDebug :app:lintDebug --console=plain
```

debug APK 预期输出位置：`app/build/outputs/apk/debug/app-debug.apk`。release 使用自己的签名，按 `signing.properties.example` 配置；签名材料不得提交。

## 2026-10-20 初始化验证

- `./gradlew --version`：通过，Gradle 9.6.0，Temurin JDK 21.0.11。
- AndroidManifest 与 34 个语言资源 strings.xml：XML 解析通过，各语言名称为 FileWeave。
- 主源码和 app Gradle 配置：Firebase 引用移除检查通过。
- LICENSE：与导入的上游提交逐字节一致。
- `git diff --check`：通过。
- 首次 `:app:assembleDebug :app:lintDebug`：通过，耗时 9 分 53 秒（含首次依赖及工具链安装）。
- lint：0 个错误、859 条警告；包括翻译、未使用资源、旧 API 和 TrustAllX509TrustManager 等检查项。通过 lint 不等于完成安全审计，网络信任处理需后续单独审查。
- 本地自动安装了所需 NDK、Build Tools、SDK Platform 37.2 及 CMake 3.22.1。
- 最终版本号与快捷方式调整后的增量构建：`:app:assembleDebug :app:lintDebug` 再次通过。已核对 APK applicationId、versionName 和 versionCode 为 `org.fileweave.android`、`0.1.0-dev`、`40`。
- 真机安装、视频、Mac SMB、独立 Provider 和与原版共存：尚未验证。

## 0.2 开发版更新

新增 Media3 1.11.1、Go 编译的 libgocryptfs JNI、Mac Swift/Python 配套程序。构建需要 Go（本机 1.26.0）；Gradle 的 `buildVaultNative` 会在 `preBuild` 前生成四种 ABI 的库，不提交二进制。

0.2 初始版本为 `0.2.0-dev`，内部 versionCode 41。运行入口与实际验证见 FEATURES.md、VALIDATION.md。Mac 配对令牌和密钥在 `mac/.state/`，禁止提交；开发版 Mac 程序由 `mac/run.sh` 构建到 `mac/build/`，依赖仓库位置。

0.2 最后一次完整检查通过：assembleDebug、testDebugUnitTest、lintDebug；2 个 JVM 测试通过，lint 0 error / 933 warning。另有 6 个 Python 服务测试与真机 Instrumentation 集成检查。

## 0.2.1 播放器与发布

当前版本 `0.2.1-dev`，versionCode 42。手势层 `VideoGestureLayout` 在当前布局坐标中锁定方向，普通视频、库内视频和 Mac 视频复用同一个播放界面；未替换 Media3 和底层 DataSource。

最后一次 Android 检查：assembleDebug、testDebugUnitTest、lintDebug 均通过，4 个 JVM 测试，lint 0 error / 937 warning。真机 API 36 和模拟器 API 34 均通过横竖屏手势 Instrumentation，详见 [验证记录](VALIDATION.md)。

CI 会先安装 SDK Platform 37.2、Build Tools 37.0.0、NDK 30.0.16248370 与 CMake 3.22.1，再构建四种 ABI 的 native 库；不依赖开发机已有的 NDK。CI 产物使用 runner 的临时 debug 签名，与本机预发布 APK 不保证可覆盖更新。

代码托管及发布流程见 [发布说明](RELEASING.md)。公开预发布 APK 采用已在真机验证的本机 debug 签名；正式签名配置仍为后续工作。
