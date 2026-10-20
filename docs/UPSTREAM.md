# 上游与维护

- 项目：Material Files
- 仓库：https://github.com/zhanghai/MaterialFiles
- 导入分支：master
- 导入提交：93c333960aa897836ba3f215a19d0cb0de494db1
- 提交日期：2026-10-15
- 导入日期：2026-10-20
- 本地开发分支：main
- 上游远端：upstream
- FileWeave 托管远端：尚未创建

完整保留原 Git 历史与 LICENSE。原 README 和隐私说明分别保留在根目录的 `README.upstream.md`、`README.upstream.zh-CN.md` 和 `PRIVACY.upstream.md`。上游隐私说明仅用于来源记录，不代表 FileWeave 的承诺。

使用 `git fetch upstream` 获取更新，在单独的更新分支评估差异、合并并验证后再进入 main。不要向 upstream 推送 FileWeave 变更。

暂时保留原源码包名和资源结构，减少大规模重命名带来的合并冲突；安装标识和 Provider authority 已独立。后续复用第三方组件前，记录精确版本、许可和集成方式。

本次修改的重点为项目身份、移除 Firebase 接入、立项文档和 CI 命名。上游版权和作者信息保留。关于页面暂时链接到原项目和原作者，发布前增加清晰的分支归属。

内部 versionCode 保留上游的 40，避免现有 AppUpgrader 将后续启动误判为旧版升级；对外 versionName 独立为 0.1.0-dev。
