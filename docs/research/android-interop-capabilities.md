# Android 互通能力与"类拖拽"体验调研报告

- 调研日期：2026-10-22。方法：官方文档 + 2026 年现状检索逐条查证（引用见文内与文末清单）；仅调研，不含任何已实施改动。
- 对象：FileWeave Android 端（`org.fileweave.android`）与 Mac 菜单栏配对程序的互通体验增强。
- 结论中的"推荐等级"：**必须**（不做会坏）/ **推荐**（收益明确、成本可控）/ **可选**（特定场景有价值）/ **不推荐**（成本或风险大于收益、或与项目边界冲突）。

## 结论先行（TL;DR）

1. **Android 没有任何官方"手机↔电脑跨设备拖拽"通道**。DeX/Phone Link 的拖拽是微软–三星私有集成（DeX for PC 已于 2024 年 12 月停用）；华为多屏协同拖拽是系统私有（HarmonyOS 的跨设备拖拽 API 只开放给鸿蒙应用）；荣耀 MagicOS 不开放拖拽，但开放了**一碰传 OneTap Kit**（MagicOS 11.0+，且只解决荣耀/小米/OPPO/vivo 手机间互传，**不通向 Mac**）。与 Mac 的"类拖拽"只能走替代路径。
2. **最务实的"类拖拽"路径排序**：① Android 系统分享目标（ACTION_SEND）+ **Mac 端菜单栏图标接收真实拖放**（把"拖拽"体验做在完全支持它的 macOS 侧）；② 快捷入口矩阵（QS 磁贴 + 应用长按快捷方式 + 常驻通知动作按钮）；③ 应用内拖拽投放区（DragStartHelper/DropHelper）+ 剪贴板放文件 URI。Android 端做"拖出"只有多窗口/桌面模式下的 `DRAG_FLAG_GLOBAL`，到不了 Mac。
3. **对产品影响最大的前瞻性系统变化是 Android 17 的本地网络权限 `ACCESS_LOCAL_NETWORK`**（SDK 37+ 强制，Android 16 可选启用）：届时本应用对 Mac 的 TCP 直连（8787/8791）、mDNS 解析都会被默认阻断，必须运行时申请该权限或改走系统服务选择器（`DiscoveryRequest.FLAG_SHOW_PICKER`）。这是所有条目中唯一"必须提前规划"的项。
4. 剪贴板自动同步三条"取巧"路线（通知监听 / 悬浮窗焦点 / 无障碍）中，**通知监听读剪贴板在原理上不可行**（复制提示是 Toast 不是通知）；悬浮窗焦点法在 Android 13/14 社区验证可用但 OEM 差异大且与本产品"遵循系统限制、显式操作"的定位冲突；无障碍法可行但维护成本和脆弱性最高。三者均建议**不采用**，维持现有的显式发送/预览确认交互。

### 评级总表

| # | 能力 | 推荐等级 | 一句话理由 |
|---|------|---------|-----------|
| 0 | `ACCESS_LOCAL_NETWORK`（Android 17） | **必须（前瞻规划）** | target SDK 37 起不申请就连不上 Mac |
| 1 | 系统分享目标（ACTION_SEND 收文件并发往 Mac） | **推荐** | 零新权限、全应用通用，最接近"拖出去" |
| 2 | Mac 端拖放接收（macOS 侧能力） | **推荐** | 唯一能实现真正"拖拽"语义的一端 |
| 3 | 快捷入口（磁贴/Shortcuts/通知动作） | **推荐** | 把常用互通动作降到一次点击 |
| 4 | WorkManager（未来后台队列） | **推荐（届时）** | 约束式后台工作的正统选择 |
| 5 | FGS 类型迁移 `connectedDevice` | 可选 | 语义更准但现 `specialUse` 可用，回归成本 > 收益 |
| 6 | 通知监听镜像通知到 Mac | 可选 | 对剪贴板无用；做"通知转发"才有点价值 |
| 7 | 悬浮窗快捷入口 | 可选 | 与系统分享表重叠，增益有限 |
| 8 | 剪贴板放文件 URI（ClipData.newUri） | 可选 | 小成本语义补全 |
| 9 | 精确闹钟 | 可选 | 无定时场景；Android 14 起默认不授予 |
| 10 | 多用户/工作资料适配 | 不相关 | 单用户自用产品；仅作认知记录 |
| 11 | CDM 伴生设备 / NEARBY_WIFI_DEVICES | 不推荐（现状） | 面向 BLE 伴生设备与 Wi-Fi P2P，与 NSD+TCP 架构无关 |
| 12 | IME 读剪贴板 | 不推荐 | 需接管默认键盘，代价远超收益 |
| 13 | 无障碍自动化 | 不推荐 | 脆弱、绕过厂商确认设计、升级易碎 |
| 14 | 通知监听读剪贴板 | 不可行 | 复制提示是 Toast，不是 Notification |
| 15 | 悬浮窗焦点读剪贴板 | 不推荐 | 取巧路径，OEM 差异大，与产品定位冲突 |

## 现状基线（Manifest 盘点）

已具备：`MANAGE_EXTERNAL_STORAGE`（所有文件访问）、SAF 目录授权、`INTERNET`/`ACCESS_NETWORK_STATE`/`ACCESS_WIFI_STATE`、`POST_NOTIFICATIONS`、`WAKE_LOCK`、4 个 `specialUse` 前台服务（文件任务 / FTP / 文件夹共享 / Mac 连接，均带 subtype 说明）、2 个快速设置磁贴、`ACTION_SEND`（仅 SaveAs 保存用途）。NSD 发布/发现走 `NsdManager`。依据：`app/src/main/AndroidManifest.xml`。

---

## 问题一：Android 权限与系统能力逐项评估

### 1. 通知监听（NotificationListenerService）——能否借此读剪贴板？**不能**

- **能做什么**：经用户在系统设置显式授权后，读取状态栏通知内容与动作。
- **"读剪贴板"可行性**：原理性不可行。Android 各处"已复制到剪贴板"提示是系统或应用弹出的 **Toast，不是 Notification**（AOSP 框架字符串 `text_copied` 走 Toast 机制），`NotificationListenerService` 从设计上收不到 Toast。Android 12+ 新增的剪贴板读取提示同样是 Toast（且只在应用**读取**时弹，不是写入时）。不存在任何标准系统通知承载剪贴板内容。
- **对本产品的具体增益**：读剪贴板为零。唯一正当用途是**通知镜像**——把手机通知（如录音完成、传输完成、聊天消息摘要）转发到 Mac 菜单栏显示，在 Mac 上获得"手机在响什么"的感知。
- **申请与合规成本**：用户须到"通知访问权"设置页手动授予（无法运行时弹窗）；上架 Google Play 需为 NotificationListenerService 提交使用声明（本产品不上架，无此成本）。
- **用户感知**：强。设置页会标注"此应用可读取所有通知"，信任成本高；通知镜像还会把消息内容送上网（到 Mac），需明示。
- **推荐等级**：作为剪贴板方案**不可行**；作为通知镜像**可选**（非当前主线）。
- **依据**：[Android 10 隐私变更（剪贴板仅默认 IME/焦点应用可读）](https://developer.android.com/about/versions/10/privacy/changes)、[Android 12 行为变更（剪贴板读取提示）](https://developer.android.com/about/versions/12/behavior-changes-all)、[剪贴板安全处理指南](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling)、[AOSP 框架 Toast 字符串](https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/res/res/values/strings.xml)。

### 2. 输入法（IME）方案读剪贴板

- **能做什么**：**默认输入法**是 Android 10+ 剪贴板限制的两个豁免方之一（另一是焦点应用），可随时 `getPrimaryClip()`，因此理论上能实现"手机剪贴板变更即推送到 Mac"的全自动同步。输入法还能看到剪贴板预览条。
- **对本产品的具体增益**：解决 Mac←手机方向的剪贴板自动同步（当前为显式"发送手机剪贴板到 Mac"）。这是唯一**官方 sanctioned** 的后台读剪贴板路径。
- **申请与合规成本**：必须让用户把 FileWeave 设为**默认键盘**——意味着要么完整实现一个可用输入法（工程量大、多语言/滑动输入/候选词都是深坑），要么用户的打字体验直接劣化。敏感内容可用 `ClipDescription.EXTRA_IS_SENSITIVE` 对 IME 隐藏预览，说明系统本身也在收紧 IME 看剪贴板的语义。上架 Play 需声明（不上架则无）。
- **用户感知**：极强。键盘是最敏感的组件；任何打字故障都会被归咎于本应用。
- **推荐等级**：**不推荐**。收益（省一次点击）与成本（维护一个输入法 + 打字风险 + 默认键盘占位）严重不对称；且与项目"用户明确操作后发送剪贴板"的既定交互设计（FEATURES.md）相悖。
- **依据**：[Android 10 隐私变更](https://developer.android.com/about/versions/10/privacy/changes)、[Secure Clipboard Handling（EXTRA_IS_SENSITIVE）](https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling)。

### 3. 悬浮窗（SYSTEM_ALERT_WINDOW）快速分享入口

- **能做什么**：在任意应用上方绘制入口（悬浮球/按钮），点击即唤起"发送到 Mac"等动作。附带一个社区熟知的"取巧"用法：**可获焦的悬浮窗**让应用短暂成为"焦点应用"，从而通过剪贴板焦点检查读取剪贴板。
- **对本产品的具体增益**：悬浮入口 = 不切应用即可发起发送；焦点法 = 理论上后台自动读手机剪贴板。但前者与系统分享表（任何应用都能 `ACTION_SEND`）功能重叠，后者是绕过限制的灰色路径。
- **申请与合规成本**：特殊权限，须引导用户到"显示在其他应用上层"设置页开启；Play 政策对 overlay 用途有审查（不上架则无）。焦点法在 Android 13/14 有社区验证可用，但 OEM 行为不一（如三星已封堵"叠窗遮挡剪贴板提示"的变体），且 Android 12+ 读取剪贴板会向用户弹提示，除非再叠加遮挡窗口——那就是明确的对抗行为了。
- **用户感知**：悬浮球易被当作"流氓软件"特征；后台读剪贴板会触发系统"xx 读取了剪贴板"提示。
- **推荐等级**：悬浮快捷入口**可选**（与分享表重叠，增益有限）；借悬浮窗自动读剪贴板**不推荐**——不稳定、OEM 易碎，且与 ROADMAP.md 明确的"不承诺普通 Android 后台全自动读取剪贴板"边界冲突。
- **依据**：[Fingerprint：Android 14 剪贴板与悬浮窗研究](https://fingerprint.com/blog/android-14-clipboard-security-leak)、[Settings.ACTION_MANAGE_OVERLAY_PERMISSION](https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION)、[Android 12 行为变更](https://developer.android.com/about/versions/12/behavior-changes-all)。

### 4. 无障碍服务（AccessibilityService）——全自动化？

- **能做什么**：读取屏幕内容、模拟点击/手势（`performGlobalAction`、dispatchGesture）、监听窗口变化；配置 `canReadClipboard` 等能力。剪贴板管理类应用常用它实现后台捕获；对荣耀机型，理论上还能自动点击系统安全确认弹窗（ROADMAP 中"阻塞在荣耀安全机制下的人工确认点击"一类）。
- **对本产品的具体增益**：全自动剪贴板同步、自动确认弹窗。这是唯一能"什么都自动化"的入口，也正是问题所在。
- **申请与合规成本**：Google Play 对 AccessibilityService API 有严格政策（须声明为无障碍工具或提交权限声明表单、演示视频、证明无更低侵入替代方案）——**本产品不上 Play，此成本为零**，这是"纯自用是否值得"的核心变量。剩下的成本是：Android 13+ 对侧载应用的"受限设置"要求用户多步操作才能启用无障碍；系统每次重启/更新后可能要求重新确认；厂商（含荣耀）对无障碍有额外风控，可能被静默关闭或触发安全提示。能力上还受应用用 `EXTRA_IS_SENSITIVE`/Android 14 敏感标记的内容会被隐藏的限制。
- **用户感知**：系统设置会持续提示"此应用具有完整屏幕读取与操作能力"；信任与误操作风险全由自己承担（自用场景可接受，但工程上脆弱）。
- **推荐等级**：**不推荐**。纯自用确实"能用"，但每一次系统升级、每一次厂商风控调整都可能破坏它，维护成本长期高于"多点一次按钮"；自动点击厂商安全确认框本质是绕过设备安全设计，与 AGENTS.md 精神（不以特权为默认条件）一致地应予回避。
- **依据**：[Play 无障碍 API 政策](https://support.google.com/googleplay/android-developer/answer/10964491)、[敏感剪贴板内容对无障碍隐藏（Android 14）](https://fingerprint.com/blog/android-14-clipboard-security-leak)、[Android 10 隐私变更](https://developer.android.com/about/versions/10/privacy/changes)。

### 5. CompanionDeviceManager / NEARBY_WIFI_DEVICES / BLUETOOTH

- **能做什么**：CDM 用于声明"伴生设备"关联（API 26+），`REQUEST_OBSERVE_COMPANION_DEVICE_PRESENCE`（API 33+）可让系统在 BLE 设备出现/消失时回调应用而无需常驻服务；`NEARBY_WIFI_DEVICES`（API 33+）是 Wi-Fi P2p/Aware/RTT/热点等 API 的新运行时权限。
- **对本产品的具体增益**：**现状下很低**。Mac 不是 BLE 伴生设备，CDM 的存在感知、免运行时申请等红利都拿不到；本产品的发现/连接/信任体系（NSD + 配对令牌 + 证书固定）已经自洽。`NEARBY_WIFI_DEVICES` 的官方受影响 API 清表中**并不包含 `NsdManager`**，现有 NSD 发现今日无需它。
- **申请与合规成本**：CDM 关联需系统选择器流程；NEARBY_WIFI_DEVICES 是运行时权限（可用 `neverForLocation` 免除位置关联）。均为低，但收益同样低。
- **用户感知**：多一个"附近的设备"权限弹窗，用户会疑惑为何文件管理器要它。
- **推荐等级**：**不推荐（现状保持）**。唯一例外见下节——Android 17 起"本地网络"归入 `NEARBY_DEVICES` 权限组，若用户已授予过该组权限（如蓝牙），`ACCESS_LOCAL_NETWORK` 可免重复弹窗，这属于下节的规划范畴。
- **依据**：[Companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing)、[CompanionDeviceManager 参考](https://developer.android.com/reference/android/companion/CompanionDeviceManager)、[Wi-Fi 权限（NEARBY_WIFI_DEVICES 受影响 API 清单）](https://developer.android.com/develop/connectivity/wifi/wifi-permissions)。

### 6. 前台服务类型：`connectedDevice` 等还能怎么用

- **能做什么**：Android 14 起每个前台服务必须声明类型。`connectedDevice` 语义为"与外部设备的蓝牙/NFC/IR/USB/网络连接交互"，前置条件宽松：声明 `FOREGROUND_SERVICE_CONNECTED_DEVICE` + 满足其一即可启动——包括**普通权限** `CHANGE_NETWORK_STATE`/`CHANGE_WIFI_STATE`/`CHANGE_WIFI_MULTICAST_STATE`（无需运行时弹窗）或运行时 `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN` 等。
- **对本产品的具体增益**：`BridgeConnectionService`/`FolderBridgeService` 当前用 `specialUse`（带 subtype 说明，无时长限制，已工作）。迁移到 `connectedDevice` 语义更准确（"与已配对电脑的局域网连接"），且不再依赖 specialUse 的"特殊用途说明"审查逻辑。但 specialUse 对本产品同样无时长限制、不上 Play 无声明表单成本，迁移收益主要是语义正确性。
- **申请与合规成本**：低（普通权限 + manifest 类型变更）；风险在回归——OEM 对不同 FGS 类型的后台管控策略不同，需要真机回归（VALIDATION.md 已列 Android 15+ 前台服务行为待回归）。
- **用户感知**：无（通知样式不变）。
- **推荐等级**：**可选**。等真机回归窗口一起做，不单独驱动一次发版。注意不要迁移到 `dataSync`（Android 15 起有 6 小时时限、且 BOOT_COMPLETED 不能拉起）。
- **依据**：[前台服务类型（官方前置条件清单）](https://developer.android.com/develop/background-work/services/fgs/service-types)、[Android 14 起必须声明 FGS 类型](https://developer.android.com/about/versions/14/changes/fgs-types-required)。

### 7. 精确闹钟 / WorkManager 对后台同步的意义

- **能做什么**：`SCHEDULE_EXACT_ALARM` 提供准点唤醒；WorkManager 提供带约束（网络/充电/空闲）的持久化后台任务与加急（expedited）通道。
- **对本产品的具体增益**：当前设计（用户显式操作、前台页面保持连接）**都不需要**。若未来做 ROADMAP"后续完善"中的后台队列/断点续传，WorkManager（约束 unmetered 网络 + 加急）是正统实现；精确闹钟仅在有"定点任务"（如每天 9 点同步录音目录）时才有意义。
- **申请与合规成本**：Android 14 起 `SCHEDULE_EXACT_ALARM` 对新装应用**默认拒绝**，须引导用户去设置开启并处理授权变化广播；`USE_EXACT_ALARM` 仅限闹钟/日历类应用（Android 15 起不可撤销）。WorkManager 无特殊权限。
- **用户感知**：闹钟路径会多一次"去设置开权限"的打断；WorkManager 无感。
- **推荐等级**：WorkManager **推荐（做后台队列时采用）**；精确闹钟**可选（低）**——无定时需求前不引入。
- **依据**：[Android 14 精确闹钟默认拒绝](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)、[WorkManager 概览](https://developer.android.com/topic/libraries/architecture/workmanager)。

### 8. 多用户 / 工作资料场景

- **事实**：工作资料、应用分身、Android 15 私密空间（Private Space）各自运行在独立 Android 用户下，外部存储视图按用户隔离（`/storage/emulated/<userId>/`），`MANAGE_EXTERNAL_STORAGE` 等"所有文件访问"**按用户（资料）授予**，互不可见。
- **对本产品的具体增益/风险**：单用户自用产品基本不相关。仅需认知两点：若用户把 FileWeave 装进私密空间或分身，配对凭据（Keystore）与保险库会和主空间实例完全隔离；主空间实例也看不到私密空间里的录音目录。
- **推荐等级**：**不相关**（不做适配，文档记录一句即可）。
- **依据**：[工作资料隔离说明](https://support.google.com/work/android/answer/6191949)、[MANAGE_EXTERNAL_STORAGE 指南](https://developer.android.com/training/data-storage/manage-all-files)。

### 特别前瞻：Android 17 本地网络权限 `ACCESS_LOCAL_NETWORK`（本报告唯一"必须规划"项）

官方文档（2026-10 更新）确认的推进节奏：

- **Android 16**：仅可选启用（`adb shell am compat enable RESTRICT_LOCAL_NETWORK`）；临时借用 `NEARBY_WIFI_DEVICES`，且 NsdManager 等进程外 API 不受影响。
- **Android 17（target SDK 37+）**：**强制执行**。新权限 `ACCESS_LOCAL_NETWORK`（归入 `NEARBY_DEVICES` 权限组），在**网络栈深处**拦截：到局域网的 TCP 连接、UDP 单播/组播/广播、`.local` 解析（mDNS）、SSDP、`NsdManager` 全部需要授权，"本地网络默认对所有更新 target 的应用关闭"。对本产品即：连不上 Mac 的 8787/8791、发现不了 `_fileweave-folder._tcp.`。target < 37 的旧应用经 `INTERNET` 获得隐式授予（过渡期）。
- **官方给的两条路**：A. 隐私友好的**系统服务选择器**（`DiscoveryRequest.FLAG_SHOW_PICKER`，选择后对所选服务的连接免权限）；B. 直接运行时申请 `ACCESS_LOCAL_NETWORK`（若用户已授予同组的蓝牙等权限则不再弹窗；有"拒绝后反悔再给机会"策略）。已授权 Mac 传输这种**自选对端、长期使用**的场景显然应走 B（选择器会破坏"自动发现自动连"的现有体验），A 可作为用户拒绝授权后的降级发现路径。
- **行动项**：在把 targetSdk 升到 37 之前，把"运行时申请 ACCESS_LOCAL_NETWORK + 拒绝后的引导/降级（手动输入地址 + 可选系统选择器）"列入 ROADMAP；平时跟踪该权限在 Beta 版的行为微调。
- **依据**：[Local network permission（官方，2026-10）](https://developer.android.com/privacy-and-security/local-network-permission)。

---

## 问题二：与传统"互联互通"（拖拽等）的接口支持

### 1. Android 官方有没有跨设备拖拽 API？——没有；所有手机↔PC 方案都是私有集成

- **AOSP 标准**：只有**单设备内**的拖拽（含多窗口/分屏/桌面模式下跨应用），无任何 phone↔PC 拖拽 API。
- **三星 DeX / Link to Windows**：DeX for PC 客户端已于 2024 年 12 月宣布停用，并入微软 Phone Link；"文件拖拽"是 Phone Link 的**三星专属**功能（要求预装版 Link to Windows ≥1.0.52.30、同一网络），属微软–三星私有通道，第三方应用无接口参与。Google 官方的跨设备文件方案是 Quick Share（官方 Windows 应用），**没有 macOS 官方端**（2026 年现状，仅有社区项目 NearDrop）。
- **华为多屏协同**：手机↔PC 拖拽是系统级私有能力。HarmonyOS 确向**鸿蒙三方应用**开放了跨设备拖拽事件与分布式文件系统 hmdfs，但那是 ArkTS/HarmonyOS 应用生态的接口，对 FileWeave 这个 Android/Kotlin 应用**不可用**。
- **荣耀 MagicOS**：多屏协同拖拽同样私有不开放；开放的是"一碰传"（见第 3 节）。
- **Google Cross Device SDK**：截至 2026 年 5 月仍是 Developer Preview，面向 Android↔Android/Chrome 生态且依赖 Play services，**不支持 macOS 对端**，对本产品无可用性。
- **结论**：不存在可依赖的标准通道；"拖拽到 Mac"必须自行搭建（FileWeave 已有的 TLS+令牌通道即是承载层）。
- **依据**：[三星 DeX 传输说明](https://www.samsung.com/us/support/answer/ANS10003643)、[Phone Link 拖拽要求（三星专属）](https://support.microsoft.com/en-us/windows/apps/phonelink/file-drag-drop-in-link-to-windows-pre-installed)、[DeX for PC 停用报道（2024-12）](https://www.theverge.com/2024/12/3/24311882/samsung-dex-app-windows-replaced-link-to-windows-microsoft-phone-link)、[Quick Share 官方页（仅 Windows）](https://www.android.com/quick-share/with-windows-pc)、[Quick Share 无 Mac 官方端的现状](https://www.androidpolice.com/quick-share-for-ios-and-macos-might-be-in-the-works)、[华为多屏协同（拖拽互传）](https://consumer.huawei.com/cn/support/content/zh-cn16026128)、[HarmonyOS 跨设备拖拽最佳实践（面向鸿蒙应用）](https://developer.huawei.com/consumer/cn/doc/doccenter-multi-device/bpta-continue)、[hmdfs 分布式文件系统](https://developer.huawei.com/consumer/cn/doc/HarmonyOS-Guides/distributed-fs-overview)、[Cross Device SDK（仍为 Developer Preview）](https://developer.android.com/guide/topics/connectivity/cross-device-sdk/overview)。

### 2. 本应用作为拖拽"源"与"目标"：Android 端标准做法（与 Mac 无标准通道）

- **作为源（拖出到其他 app）**：`View.startDragAndDrop(clipData, shadow, state, flags)`，跨应用需 `DRAG_FLAG_GLOBAL | DRAG_FLAG_GLOBAL_URI_READ`，URI 经 `FileProvider` 提供（应用已有 FileProvider 权威）。Android 7.0+ 支持，**仅在多窗口/分屏/桌面窗口下可跨应用**（Android 16 QPR 桌面模式让它变得主流，但依然只是"同一台 Android 设备内的两个应用"）。
- **作为目标（拖入）**：两档实现——`View.OnDragListener` + `requestDragAndDropPermissions(event)`（底层），或 AndroidX `DropHelper.configureView()` / `OnReceiveContentListener`（推荐，自动处理 MIME、高亮、URI 权限；且同一接口统一接住拖入、粘贴、键盘输入三种来源）。Jetpack 亦有 `androidx.draganddrop`。
- **与 Mac 之间**：如上，无标准通道。**替代路径**：① 系统分享目标 `ACTION_SEND`/`ACTION_SEND_MULTIPLE`（应用已注册 SEND 但仅作"另存为"；可加"发送到 Mac"直达项）；② 快速设置磁贴/应用快捷方式（已具备磁贴基建）；③ 剪贴板放文件 URI（`ClipData.newUri`，Android 支持把 `content://` URI 放入剪贴板，粘贴语义为"文件"），配合 FileWeave 自己的通道把 URI 指向的文件送 Mac。
- **依据**：[多窗口拖拽（官方）](https://developer.android.com/develop/ui/views/touch-and-input/drag-drop/multi-window)、[拖拽指南](https://developer.android.com/develop/ui/views/touch-and-input/drag-drop)、[OnReceiveContentListener 接收内容](https://developer.android.com/develop/ui/views/touch-and-input/receive-content)、[androidx.draganddrop](https://developer.android.com/jetpack/androidx/releases/draganddrop)、[复制粘贴（ClipData.newUri）](https://developer.android.com/develop/ui/views/touch-and-input/copy-paste)。

### 3. 荣耀 MagicOS 互传开放性：有开放 SDK，但**不通向 Mac**

- **OneTap Kit（一碰传 / MagicRing）**：荣耀开发者服务平台正式开放的 SDK，"面向开发者与厂商提供系统级数据分享体验"，注册碰一碰能力后通过 share 接口发送**文本/链接/文件/应用数据**；NFC 触发建连，传输走 Wi-Fi 直连。**全部能力要求荣耀设备 MagicOS 11.0+**；官方文档明示支持荣耀与 OPPO/vivo/小米设备互传（对接 2026 年 9 月落地的跨品牌"碰一碰互传"联盟标准，荣耀 Magic9 首发，免 App 免流量）。
- **对本产品的适配性**：对端是**手机**（且限联盟品牌），不是 Mac；消费级"手机传电脑"要求荣耀电脑管家/带感应区的荣耀 PC 生态，同样不含 Mac。接入还需荣耀开发者账号与 SDK 合规要求（隐私声明、初始化时机等）。结论：**不解决本产品的 Mac 互通问题**；若未来想"把文件碰给另一台手机"，它是中国厂商生态里目前最开放的选项，可作远期可选项。
- **依据**：[OneTap Kit 业务介绍（"支持荣耀与 OPPO、VIVO、XIAOMI 设备……仅 MagicOS 11.0 及以上支持"）](https://developer.honor.com/cn/docs/onetap/guides/introduction)、[发送文件指南](https://developer.honor.com/cn/docs/onetap/guides/send-file-guide)、[支持设备列表](https://developer.honor.com/cn/docs/onetap/guides/support-devices)、[SDK 合规说明](https://developer.honor.com/cn/docs/onetap/guides/sdk_security_introduction)、[跨品牌碰一碰互传标准落地（2026-09）](https://www.eet-china.com/mp/a523736.html)、[互传联盟报道](https://www.donews.com/news/detail/1/6711659.html)、[荣耀一碰传消费者说明](https://www.honor.com/cn/support/content/zh-cn15890833)。

### 4. 结论：务实"类拖拽"方案排序

1. **分享目标 + Mac 端拖放接收（首推，双向各取所长）**
   Android→Mac：任意应用选文件 → 系统分享 → "FileWeave 发送到 Mac"（`ACTION_SEND(_MULTIPLE)` 接收，FileProvider/SAF URI 经既有 TLS 通道上传；零新权限，成本最低、覆盖面最广，是 Android 语义下最接近"拖出去"的官方通道）。
   Mac→Android：**把真正的拖拽做在 Mac 侧**——菜单栏图标/面板窗口接收 `NSDraggingDestination` 拖放（macOS 完全标准），文件经既有服务下发到手机收件箱。两端都不需要任何新权限。
2. **快捷入口矩阵（次推，减少点击数）**
   已有磁贴基建上增加："发送剪贴板到 Mac"应用长按快捷方式（Shortcuts）、常驻连接通知上的动作按钮（"发剪贴板/打开收件箱"）。把最高频互通动作从"打开应用-进入页面-点按钮"压到一次交互，体验上逼近"随手一拖"。
3. **应用内拖拽投放区 + 剪贴板文件 URI（第三，锦上添花）**
   文件列表内长按拖动到"发送到 Mac"底部投放区（`DragStartHelper`/`DropHelper`），并把所选文件以 `ClipData.newUri` 写入剪贴板（粘贴即文件语义）。仅在本应用内生效，属于多窗口/桌面模式用户的小增益，优先级最低。

---

## 建议实施顺序

1. **P0（随 targetSdk 37 升级前完成，现在是规划）**：`ACCESS_LOCAL_NETWORK` 运行时申请 + 拒绝后降级路径（手动地址 / `FLAG_SHOW_PICKER`）。在此之前保持 target < 37 可继续吃隐式授予过渡期。
2. **P1（下一个功能版）**：系统分享目标"发送到 Mac"（`ACTION_SEND_MULTIPLE`，复用现有传输服务）；同版在 Mac 菜单栏程序加拖放接收（macOS 标准能力，改动集中在 `mac/`）。
3. **P2（小步快跑）**：快捷入口矩阵——Shortcuts、连接通知动作按钮；评估把剪贴板发送做成磁贴。
4. **P3（择机）**：FGS 类型迁移 `connectedDevice`（合并进某次真机回归）；WorkManager 引入（当开始做后台队列/断点续传时）。
5. **观察不做**：通知镜像（NotificationListenerService）、悬浮窗入口、应用内拖拽投放区、ClipData.newUri——按实际使用痛点再单独评估。
6. **明确不做**：IME、无障碍、悬浮窗焦点读剪贴板、通知监听读剪贴板（不可行）、精确闹钟、CDM/NEARBY_WIFI_DEVICES 现状接入、OneTap Kit（不通 Mac）。

## 调研方法与局限

- 全部关键论断基于 2026-10-22 检索到的官方文档或一手来源（引用已内联）；OEM 行为（三星对剪贴板提示的封堵、荣耀安全机制）存在机型差异，最终以真机为准。
- 荣耀开发者平台页面为 JS 渲染，无法整页抓取，引用内容来自搜索索引的文档原文摘录，建议实施前直接复核 developer.honor.com 最新版。
- 本报告仅为调研产出，不声称任何条目已实现；实施前应按 AGENTS.md 完成相应构建检查与真机验证。
