# HonorConnect（荣耀互联）文件互传协议调研与集成可行性评估

- 调研日期：2026-10-25。方法：对本机已安装的 HonorConnect.app（v1.0.1521，Mac App Store 版）做静态逆向——解包 `app.asar`、提取原生库字符串、观察运行时进程与端口；配合 rea 静态分析、荣耀官方支持文档与 OpenHarmony 公开资料交叉验证。仅调研，不含任何已实施改动。
- 对象：评估 FileWeave（Android 端 + Mac 菜单栏程序）接管「手机通过荣耀互传向 Mac 推送文件」这一场景的技术可行性，目标是用户不再常驻运行 HonorConnect.app。
- 结论中的"推荐等级"沿用既有调研约定：**必须** / **推荐** / **可选** / **不推荐**。

## 结论先行（TL;DR）

1. **HonorConnect 的互传不是单一协议，而是华为系全家桶**：OpenHarmony **SoftBus**（CoAP 组播发现，库内留有 `SoftBus_tmp/SoftBus/Common/coap/src/CoapLog.cpp` 编译路径）+ **HiChain/HiLink 风格设备认证**（PAKE/STS 密钥协商）+ **ECDH+AES-GCM-256 会话加密** + **私有 HTTP 文件 API**（`/auth` → `/basic/security/encryption/*` → `/cfs/files`、`/cfs/blocks`）。每一层都有加密和云端辅助，逆向重实现是**以月计的工程**且必然随版本漂移。
2. **账号绑定是硬约束**：官方文档明确 Mac 与手机须**登录同一荣耀账号**（无论 WLAN 互传还是碰一碰）。配对密钥协商（HiChain）与端点发现（GRS 云端路由缓存 `/cache/grs_hncloud_cache`）都有账号/云端参与，纯局域网的第三方实现无法完整复刻官方配对体验。
3. **因此"逆向并集成 HonorConnect 协议"评级：不推荐**（成本、稳定性、合规三重风险）。**推荐路径是把"互传体验"而非"互传协议"集成进来**：手机侧用系统分享（`ACTION_SEND`）把文件交给 FileWeave Android（既有「发送到 Mac」通道），Mac 侧在菜单栏面板做一等公民的"接收中心"（入口/请求确认/进度/完成打开）。这条路 0 逆向、0 新协议，架构已就绪，且与既有调研结论（android-interop-capabilities.md 第 1 条"系统分享目标=推荐"）衔接。
4. **可选补充**：调研「荣耀分享→共享至电脑」模式（手机侧暴露 SMB 供电脑访问），若属实则 FileWeave Mac 端可"拉取"手机文件，作为接收中心的补充通道；但它是主动浏览，不是手机推送，定位不同。
5. 若用户坚持要接收**荣耀官方分享面板**（互传 UI）里发出的文件，唯一现实做法是**继续保留 HonorConnect.app 作为接收端**，FileWeave 通过监视其落盘目录（默认下载目录中荣耀互联子目录）联动提示——这是"共存"而非"替代"，收益有限，评级可选。

## 一、HonorConnect.app 解剖（静态 + 运行时证据）

### 1.1 进程与组件结构

| 组件 | 角色 | 证据 |
|---|---|---|
| `Hihonornote`（Electron 主进程） | UI 编排，bundle id `com.hihonor.hihonornote`，内部名 "HONOR WorkStation" | `Contents/Info.plist`、`package.json` |
| `HnOfficeCenter`（原生后端） | 全部连接/认证/传输逻辑承载者；对 Electron 监听 TCP `*:9979`、`*:8878`（localhost 通信） | `lsof -p 79602`（运行时观察） |
| `OfficeCenter/*.dylib` ×30+ | SoftBus CoAP、设备认证、pcSync、FileSync、GRS、账号 OAuth 等 SDK | 目录清单 |
| `PlugIns/shareextension.appex` | macOS 系统分享接收入口 | 目录清单 |
| `PlugIns/notifextension.appex` | 通知扩展 | 目录清单 |
| `OfficeCenter/HnMirror.app` | 投屏（HnMirror）相关 | 目录清单 |

Electron 主进程通过 FFI（`@lwahonen/ffi-napi`）与 `@honor/*` npm 私有包驱动原生层，核心包：`app-honor-share-main`（互传 UI 逻辑）、`app-trust-circle-main`（可信圈）、`file-hub-napi`、`control-center-napi`。

### 1.2 运行时端口观察（HnOfficeCenter，PID 实测）

| 端口 | 协议 | 推断用途 |
|---|---|---|
| TCP `*:9979`、`*:8878` | HTTP/私有 RPC | Electron ↔ 原生后端本机通信（仅 localhost 使用） |
| UDP `*:5684` | **CoAP over DTLS** 标准端口 | SoftBus 局域网设备发现（加密变体；开源版用 5683 明文） |
| UDP `*:5700`、`*:23888` | 私有 | 发现/保活/数据通道（未进一步逆向） |
| 动态 UDP（绑定本机 LAN IP） | 私有 | 传输数据面 |

实测时手机不在网（无 LAN ESTABLISHED 连接），但发现监听常驻。

## 二、协议栈分层证据

### 2.1 发现层：SoftBus CoAP 组播

- `libCoapResourceManager.dylib` 内留有编译机路径 `/Users/masterlovsky/work/SoftBus_tmp/SoftBus/Common/coap/src/CoapLog.cpp` 与 `CoapDiscover`、`CoapAddServer` 等符号——**直接编译自 OpenHarmony SoftBus 的 CoAP 模块**。
- 机制与 OpenHarmony 公开文档一致：发现端 CoAP 组播/广播（开源版 UDP 5683），被发现端单播响应携带设备信息；HonorConnect 监听的是 **5684（CoAP over DTLS）**，即加密变体。
- 引用：[communication_softbus_lite（Gitee）](https://gitee.com/openharmony/communication_softbus_lite)、[OpenHarmony 软总线源码分析（CSDN）](https://blog.csdn.net/weixin_44821644/article/details/123726279)、[设备发现详解（cnblogs）](https://www.cnblogs.com/zjra/p/16123446.html)。

### 2.2 认证/配对层：HiChain 风格 PAKE/STS + 可信圈

- `libHnDeviceAuthSDK.dylib` 字符串：`Build pake client sub object`、`Build sts client sub object`、`Begin start key agreement`、`Begin hilink import auth info`——华为 HiChain/HiLink 一脉的设备认证与密钥协商。
- `libDeviceManagerClient.dylib`：`hntrustcircleser`、`The current process is a trusted ring process`；`app-trust-circle-main` npm 包对应"可信圈"（已配对设备集合）。
- 官方要求：**两端登录同一荣耀账号**（[跨品牌互传文件支持文档](https://www.honor.com/cn/support/content/zh-cn15886296)）→ 配对链路含云端辅助（GRS：`libGRSSdk.dylib`、`/cache/grs_hncloud_cache`）。
- 发起侧错误码（JS 层枚举）：`ERROR_SENDER_BLE_CCMP_TIMEOUT`、`ERROR_SENDER_CONFIRM_CONNECT_HOTSPOT_*`、`ERROR_SENDER_STA_NOT_IN_SAME_SUBNET` —— 揭示连接路径含 **BLE 确认（CCMP 加密）** 与**手机热点直连**两种，不止同网段 LAN。

### 2.3 会话加密层

- `libpcSyncSDKLibrary.dylib`：`AesEcdh.cpp`（Jenkins 路径 `feature/CloudSync/pcSyncSDKLibrary/source/service/AesEcdh.cpp`）、`AesGcm256Encrypt/Decrypt`、`EC_KEY`——**ECDH 密钥协商 + AES-256-GCM**。
- TLS：`Resources/config/cacert.pem`、`CURLE_SSL_CONNECT_ERROR` 等字符串。

### 2.4 传输层：私有 HTTP API（pcSync SDK）

`libpcSyncSDKLibrary.dylib` 提取到的端点面（顺序即推断的握手顺序）：

```
/auth?deviceId=                                  设备认证
/basic/cfg/all?clientCap=                        能力协商
/basic/cfg/apk?clientCap= / /basic/cfg/syncApp/  应用同步配置
/basic/security/encryption/publicKey             公钥交换
/basic/security/encryption/workingKey?keyType=   会话密钥协商
/cfs/files?path=                                 文件元数据/列表
/cfs/blocks?fid= /cfs/blocks?path=               分块文件传输
/cfs/operations?opCode=1&action=unlock&path=     文件操作
/sync/upload/ /sync/download/                    上/下行
```

- `Authorization: Bearer` 头 + JSON Content-Type，说明是 HTTP(S)+Token 风格而非裸 socket。
- `libFileSyncSDK.dylib` 另有 protobuf 内部总线（`FileOcean`、`DiskDriveProxy`、`IpcBus`、`sendBytes`）。

### 2.5 服务目录与业务矩阵（OfficeCenter/config/ 明文配置）

- `ControlCenterServiceIdsConfig.xml`（gb2312 编码）列出约 20 项跨设备服务，与互传直接相关的：`DccHonorShare`/`DccHonorShare2other`（荣耀分享）、`DccFileCirculation`（文件流转=推送式互传）、`HnCrossDevFileMgr`（跨设备文件管理）、`DccDFS`（分布式文件系统）、`HnNetworkShare`（网络共享）。
- `defaultConnectProcedure.json` / `VirtualBusinessMap.json`：PC↔Phone 默认走 `DccPhonePcCollaboration` + `HnCrossDevFileMgr`；PC↔Pad 走 `DccPcPadAccompanying`（随行）；TV 走投屏类业务。
- `business_config.json`：`HnCrossDevFileMgr` 为 `FILE_SYNC` 类业务（packageName 68944072）。
- `local_config/grsConfig.json` 等四份 GRS 配置：荣耀账号/OAuth 云端端点（`hnoauth-login.cloud.honor.com`、`hnid.cloud.hihonorcloud.com` 等），按国家/GeoIP 路由——确认配对链路的云端参与。
- `magiclink.xml`：MagicLink（一碰连）ProductKey/ProductSecret/AES Key 以 IV+密文形式内置，运行时解密——第三方复刻需先提取这些运行时秘密。
- `product_model_*.xml`（六份，各 127–318KB）：按手机型号的能力矩阵，判断对端支持哪些业务。

### 2.6 UI 编排层（app.asar JS，未混淆的枚举）

- 接收链路事件：`CONTINUITY_FILE_RECEIVE`、`CONTINUITY_REQUEST`、`HONOR_SHARE_DEVICE_FOUND`、`HONOR_SHARE_RECEIVE_COMMON_MSG`。
- 文件流转发消息：`MSG_FILECIRCULATION_RECV_FILE_DLG`（接收弹窗）、`MSG_FILECIRCULATION_CANCEL_TRANS`、`MSG_FILE_SHARE_GET_RECEIVE_PATH_MSG/RSP`（接收路径协商）、`MSG_FILE_SHARE_SERVICE_ADD_TASK_MSG`、`MSG_FILE_SHARE_MAIN_UI_SEND_ONE_TASK_SPEED_MSG`（实时速率）。
- Info.plist 注册 URL scheme `honorshareext://`，配合 shareextension.appex 接收 macOS 分享。

## 三、集成路径评估

| # | 路径 | 内容 | 推荐等级 |
|---|---|---|---|
| 1 | **自有通道接管体验** | Android 端注册完整 `ACTION_SEND` 分享目标（当前仅 SaveAs），手机任意应用分享→FileWeave→已配对 Mac；Mac 菜单栏做接收中心 UI（视觉方案见配套报告） | **推荐** |
| 2 | 荣耀分享「共享至电脑」拉取 | 若手机侧暴露 SMB/类 SMB 服务，FileWeave Mac 端浏览拉取手机文件（待实测验证协议细节） | 可选（需先验证） |
| 3 | 与 HonorConnect 共存联动 | 监视荣耀互联接收目录，FileWeave 提示/整理新文件 | 可选（收益有限） |
| 4 | 逆向重实现 HonorConnect 协议 | 复刻 SoftBus-CoAP-DTLS 发现 + HiChain 账号配对 + ECDH/GCM + pcSync HTTP | **不推荐** |

路径 4 不推荐的三个理由：

1. **工程量**：四层协议栈全部加密（DTLS、PAKE、GCM），需抓包+密钥提取+逐版本维护，属持续对抗而非一次开发。
2. **账号依赖**：官方明确要求同一荣耀账号，配对受 GRS 云端与账号体系辅助；第三方实现要么复刻账号登录（不可行），要么只能覆盖"同网段匿名发现"这一小段。
3. **合规与稳定**：无公开规范与授权接口，荣耀版本更新可随时破坏兼容；与本项目"加密复用成熟格式与实现"的保守立场不符。

## 四、对路径 1 的落地要点（衔接既有架构，不在本次实施）

- Android：`ACTION_SEND` / `ACTION_SEND_MULTIPLE` 接收任意类型 → 复用「发送到 Mac」传输服务（已存在，FEATURES.md 主要流程 4）；注意 Android 17 `ACCESS_LOCAL_NETWORK` 前瞻（android-interop-capabilities.md 结论 3）。
- Mac：接收中心 = 文件列表面板的传输任务区（入口、确认、进度、完成"在 Finder 中显示"），视觉与交互方案见 `docs/research/honorconnect-receive-visual-design.md`（配套调研）。
- 语义对齐：目标是"手机上点分享→选 FileWeave→Mac 菜单栏收到"，与荣耀互传的操作步数相同（分享面板多一步选应用），差异可接受；快捷入口（磁贴/快捷方式）可再降一步。

## 五、来源清单

本机证据（均可复现）：

- `/Applications/HonorConnect.app/Contents/Info.plist`、`OfficeCenter/`、`PlugIns/`、`Frameworks/`
- `OfficeCenter/config/`：`defaultConnectProcedure.json`、`VirtualBusinessMap.json`、`business_config.json`、`ControlCenterServiceIdsConfig.xml`、`magiclink.xml`、`local_config/{grsConfig,dmGrsConfig,cloud_sync_cfg}.json`
- `app.asar` 解包：`package.json`、`node_modules/@honor/app-honor-share-main/index.js`（枚举证据）
- 原生库 strings：`libCoapResourceManager.dylib`、`libpcSyncSDKLibrary.dylib`、`libHnDeviceAuthSDK.dylib`、`libDeviceManagerClient.dylib`、`libFileSyncSDK.dylib`
- 运行时：`lsof`（HnOfficeCenter 端口表）、`pgrep`（进程树）
- rea 6.8.0 静态分析（`analyze-javascript-application`，integrity-policy record-and-continue）

网络来源：

- [荣耀官方：跨品牌互传文件（Mac 需超级工作台/荣耀互联 App、同一账号、同一局域网）](https://www.honor.com/cn/support/content/zh-cn15886296)
- [荣耀官方：荣耀分享快速分享文件（共享至电脑）](https://www.honor.com/cn/support/content/zh-cn15819241)
- [App Store：荣耀互联](https://apps.apple.com/us/app/id6739699059)
- [OpenHarmony communication_softbus_lite 源码](https://gitee.com/openharmony/communication_softbus_lite)
- [OpenHarmony 软总线源码分析（CSDN）](https://blog.csdn.net/weixin_44821644/article/details/123726279)
- [软总线设备发现详解（cnblogs）](https://www.cnblogs.com/zjra/p/16123446.html)
- [OpenHarmony 4.0 分布式软总线解析（官方论坛）](https://forums.openharmony.cn/forum.php?mod=viewthread&tid=2444)
