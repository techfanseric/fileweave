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
