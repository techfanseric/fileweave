# 安装与发布

## 当前预发布

- 仓库：https://github.com/techfanseric/fileweave
- 下载：https://github.com/techfanseric/fileweave/releases/tag/v0.2.1-dev
- Android：`0.2.1-dev`，versionCode 42，`org.fileweave.android`，Android 6.0 / API 23 起。
- APK 同时包含 arm64-v8a、armeabi-v7a、x86、x86_64；实际媒体兼容性仍取决于设备解码能力。
- Mac：从仓库运行 `./mac/run.sh`；本次不发布可独立安装的 Mac 二进制。

在 Releases 下载 `FileWeave-0.2.1-dev.apk` 和 `SHA256SUMS`。可在下载目录运行：

```sh
shasum -a 256 -c SHA256SUMS
```

当前 APK 使用开发机的 debug 签名，包含 debug-only 诊断入口，适合开发体验，尚非正式签名版本。同一来源签名才可覆盖安装；GitHub Actions 的临时 debug 签名可能不同。遇到签名冲突时不要直接卸载：卸载会删除本机保险库，先导出密文备份并确认密码可用。

## 维护者发布步骤

1. 更新 `app/build.gradle` 中的 versionCode / versionName，并同步 README、CHANGELOG、FEATURES、DEVELOPMENT、VALIDATION 和本页。
2. 运行 Android 构建、JVM 测试、lint 和必要的设备回归；Mac 协议有改动时运行 Python 测试。仅记录实际完成的验证。
3. 检查 Git 未跟踪签名材料、个人路径、配对凭据和测试文件；保留 LICENSE、上游署名及第三方许可。
4. 将验证过的 APK 复制到发布目录，按版本命名并生成 SHA-256 校验文件。
5. 提交文档与代码，向 `origin/main` 推送；检查 Android CI。
6. 在对应提交创建 FileWeave 自有标签，例如 `v0.2.1-dev`，推送该标签。创建 GitHub **预发布**，上传相同的 APK 及 `SHA256SUMS`，发布说明标注签名、验证范围及限制。
7. 核对远程标签、发布提交、附件名称和校验值一致。不要向 `upstream` 推送，也不要批量推送上游标签。

正式发行前需配置独立、持续保存的 release 签名，完成相应回归，并决定现有 debug 安装迁移方案。签名密钥与密码不提交到仓库，也不作为 Release 附件。
