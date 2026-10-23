# Android 端 SMB 服务器评估（2026-10-23，结论：暂缓实施）

结论：**不做 SMB 服务端**。对外共享由 FTP（上游已有）+ WebDAV（2026-10-23 新增）覆盖；SMB 保留客户端能力（smbj，浏览/播放 SMB 媒体源）。

## 核心论据

1. **无可用的开源 JVM/Android SMB2/3 服务端库**
   - smbj、jCIFS/jCIFS-NG 均为纯客户端。
   - 唯一活跃的纯 Java 服务端谱系 JFileServer（jLAN 后继）：开源部分仅 SMB1/CIFS；SMB2/3 在需 license key 的商业 Enterprise 附加包中。
   - SMBLibrary（TalAloni）是唯一成熟的开源 SMB1+2+3 双端实现，但是 C#/.NET（约 2.2 MB 源码），无法用于本 Kotlin 应用。

2. **SMB1 路线已死**：Windows 10/11 默认禁用 SMB1（24H2 起新装不含）；macOS 自 Catalina 起移除 SMB1 客户端。

3. **自研 SMB2/3 服务端门槛远超预算**：Windows 11 24H2 默认强制 SMB 签名、默认禁用 guest → 必须实现 NTLMv2（NTLMSSP、AV pairs）与 SMB2/3 消息签名；Explorer/Finder 实际依赖的命令面（CHANGE_NOTIFY、QUERY/SET_INFO 多个 info class、resume 语义、CREATE contexts、FSCTL_VALIDATE_NEGOTIATE_INFO、DFS 探测）在万行级；参照 SMBLibrary 体量与三类客户端真机验证成本，与本项目规模不成比例。

4. **445 特权端口死结**：Android 非 root 不能绑定 <1024 端口；Windows 资源管理器 GUI 无法连接非 445 端口（24H2+ 仅命令行 net use \\server@port\share），macOS 需手输含端口的 smb:// URI——SMB「像 NAS 一样双击挂载」的唯一卖点在非 root 设备上默认不可达，与 AGENTS.md「不以 Root/ADB 特权为默认条件」冲突。

5. **同行印证**：Material Files、Solid Explorer、CX、X-plore 均不提供 SMB 服务器（共享走 FTP/HTTP/WebDAV）；唯一开源实现 SimbaDroid 基于 fork 的 JFileServer，仅 SMB1、无认证、需 root。

## 若未来重启的条件（按可行性排序）

1. JFileServer SMB2/3 开源或出现新的 JVM 服务端库；
2. SMBLibrary（C#）移植专项（万行级，需单独立项）；
3. 商业 license JFileServer Enterprise（与开源发布模式冲突，需评估）；
4. NDK 移植 Samba + root（违反项目约定，不建议）。

## 来源

- smbj（纯客户端）: https://github.com/hierynomus/smbj
- JFileServer: https://github.com/FileSysOrg/jfileserver-alfresco-embed 、https://github.com/FileSysOrg/fileServersNG
- SimbaDroid（SMB1-only、需 root）: https://github.com/buttercookie42/SimbaDroid
- SMBLibrary（C#）: https://github.com/TalAloni/SMBLibrary
- Windows 11 24H2 SMB 加固与签名强制: https://techcommunity.microsoft.com/blog/filecab/smb-security-hardening-in-windows-server-2025--windows-11/4226591 、https://learn.microsoft.com/en-us/windows-server/storage/file-server/smb-signing
- SMB 替代端口支持现状: https://techcommunity.microsoft.com/blog/filecab/smb-alternative-ports-now-supported-in-windows-insider/3974509
- macOS Catalina 移除 SMB1: https://www.bleepingcomputer.com/news/apple/apple-issues-solutions-for-macs-unable-to-access-windows-shares
