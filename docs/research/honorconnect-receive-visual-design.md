# HonorConnect 替代 · 手机→Mac 文件接收的菜单栏视觉设计调研报告

- 调研日期：2026-10-25。方法：竞品检索 + FileWeave Mac 端代码盘点；仅调研，不含任何已实施改动。
- 对象：FileWeave Mac 菜单栏程序（`mac/Sources/FileWeaveMenuBar` + `mac/Sources/FileWeaveMacKit`）承接「手机通过 FileWeave 向 Mac 推送文件」场景的接收入口、请求确认、传输中状态、完成与失败态的视觉与交互设计。
- 前置结论：`docs/research/honorconnect-protocol-assessment.md` 已评估「逆向 HonorConnect 协议」为不推荐，推荐改为把**互传体验**集成进菜单栏面板；本报告把其中的「接收中心」落到具体 UI 设计。竞品对照：AirDrop、LocalSend、KDE Connect、PairDrop、荣耀互联官方 Mac 端体验与 Apple HIG。
- 推荐等级沿用既有调研约定：**必须**（不做会坏）/ **推荐**（收益明确、成本可控）/ **可选**（特定场景有价值）/ **不推荐**（成本或风险大于收益、或与项目边界冲突）。

## 结论先行（TL;DR）

1. **接收入口推荐「分段页 + 状态段入口」组合**：复用面板里那个写死 `.constant(0)` 的分段 Picker 预留槽位（`MenuView.swift:53-55`）新增「互传」页承载请求/进度/历史；状态段「收件箱」入口加计数角标；右键精简菜单加接收开关项——三处全部沿用现有挂载模式，不引入新容器。
2. **接收请求 = 系统通知（主通道）+ 面板内请求卡片（第二通道）双通道并存**。FileWeave Mac 端目前完全没有接入 UNUserNotificationCenter（本次 `grep -rn "UNUserNotificationCenter" mac/Sources/` 零匹配验证）；而面板是 transient popover（`AppController.swift:191`），绝大多数时间是收起的，**绝不能假设用户开着面板才收得到请求**。反向同理：AirDrop 与 KDE Connect 都有「通知不弹出」的真实故障记录，只靠通知也会丢请求。菜单栏图标变化只能做辅助信号——HIG 明确系统空间不足时会隐藏额外项。
3. **传输中**：面板内确定性进度 + 具体到文件名的文案 + 可取消（HIG 进度规范）；行骨架直接套 `RecordingRow`，忙碌态沿用 `Set<String>` 换小进度圈的现成写法；同一时刻只允许一个接收会话，后来的请求明确反馈「对方忙碌」，不静默吞掉。
4. **完成**：系统通知带「在访达中显示」动作，面板内复用「N 条新增」水位线记账模式；**失败**至少细分 忙碌/已拒绝/已取消/部分失败 四种可见结果，显式「重试」按钮、不自动重放，沿用既有安全模型（证书固定 + Bearer + 仅已配对设备）。
5. **明确不做**：账号云端绑定式接收、对局域网任何人默认自动接收、跨互联网中继、后台自动双向同步、把配对/PIN 称为「加密」等，与 `docs/PROJECT.md` 第一版边界和 AGENTS.md 约定冲突的设计（详见第六节）。

---

## 一、接收入口设计：功能入口放在哪

### 现状基线

- 面板 = `NSStatusItem` + transient `NSPopover`，左键面板 / 右键精简 `NSMenu`（`AppController.swift:17-18`、`189-198`、`320-334`）。
- 面板纵向结构：bridge 服务状态段 `statusSection` → 分隔线 → 单页分段 Picker（只有「录音」一个 tag）→ `RecorderMenuView` 录音页（`MenuView.swift:49-65`）。代码注释明确这是「状态段 + 分段页」的合并面板设计（`MenuView.swift:33-41`）。
- 分段 Picker 的 selection 写死 `.constant(0)`，是一个**预留但未启用的页面切换槽位**（`MenuView.swift:53-55`）。
- 状态段已常驻两个手机↔Mac 传输入口：「打开共享文件夹」与「收件箱」（`MenuView.swift:82-102`），由 `BridgeProcess.openShared()/openInbox()` 打开 `.state/shared` 与 `.state/inbox`（`BridgeProcess.swift:190-196`）；右键精简菜单里有同名项（`AppController.swift:270-280`）。
- 现状「接收」没有请求确认环节：手机「发送到 Mac」直接落盘收件箱，Mac 端只事后打开目录查看（`docs/FEATURES.md:47`）。
- HIG 提醒：菜单栏额外项是否放在菜单栏由用户决定，系统在空间不足时会隐藏它、**不能依赖它始终在场**；点击额外项的期待形态是菜单/面板，复杂交互应收进面板内部（来源见文末 Apple HIG The Menu Bar）。

### 候选方案

**候选 A（推荐）：分段 Picker 加「互传」页，作为接收中心**
把 `MenuView.swift:53-55` 的 selection 换成真实的页面状态、加一个「互传」tag。互传页内自上而下：接收开关/自动接收档位 → 待处理请求卡片 → 传输队列 → 接收历史。同时把状态段「收件箱」按钮升级为带计数角标、右键菜单加「允许接收」勾选项（仿「登录时启动」的 `NSMenuItem.state` 勾选模式，`AppController.swift:290-297`）。
理由：与面板既有的「状态段 + 分段页」设计意图完全吻合（槽位本身就是为此预留的）；录音页已有的 header/筛选/列表/footer 结构证明这个容器装得下同等状态密度；与 AirDrop 把接收范围设置收进菜单栏的先例一致；三处挂载沿用现有「面板 + 精简菜单」双入口模式，不新增互不相干的工具页（`docs/PROJECT.md` 对新功能进入已有导航流程的要求）。

**候选 B：不开新页，状态段下方加常驻「来自手机」区块**
请求卡片、进度、历史全部塞在 `statusSection`（`MenuView.swift:69-106`）与分段 Picker 之间。
优点是完全不动 Picker。缺点：状态段的语义是 bridge 服务状态（状态点 + 状态行 + 两个文件夹按钮），往里塞请求确认和滚动列表会把它撑成第二个主页，面板纵向失衡；且请求卡片需要显眼的临时置顶位置，混在常驻区块里权重不对。只适合作为候选 A 里「有未处理请求时互传页顶部置顶卡片」的局部形态，不适合做整体入口。

**候选 C：菜单栏图标状态变化 + 纯通知驱动，不进面板**
有请求/传输时换菜单栏图标或加徽标，一切确认与进度靠系统通知完成。
不推荐：HIG 明确额外项可能被系统隐藏、不可依赖始终在场；`NSStatusItem` 图标承载不了「谁在发什么文件」的信息量；传输进度这种持续性状态放进一次性通知也不成立。图标变化最多作为候选 A 的**辅助**信号（未处理请求时加个徽标），不承担主入口职责。

**推荐：候选 A**，并吸收 C 的辅助徽标与 B 的置顶卡片形态（卡片属互传页内部布局，见第二节）。

## 二、接收请求呈现：手机发起传输时 Mac 端如何提示

### 现状与约束

- Mac 端没有任何系统通知能力：`grep -rn "UNUserNotificationCenter\|UNNotification\|usernotifications" mac/Sources/` 零匹配（本次实际执行）。现有唯一的弹窗是配对批准，走 `NSAlert.runModal()` 模态对话框（`BridgeProcess.swift:139-147`），文案含「批准后此手机可访问共享文件夹并主动操作剪贴板」（`BridgeProcess.swift:141`）。
- 面板是 transient popover，点外部即收起（`AppController.swift:191`），且代码注释记录了面板外提示要走 `notify()` 底栏出口的原因（`AppState.swift:447-460`）——**面板不是可靠的即时提醒面**。

### 三个呈现位对照

| 呈现位 | 竞品实践 | 结论 |
|---|---|---|
| **系统通知（推荐为主通道）** | AirDrop：接收方在通知上点「接受」，可选拒绝/存储位置（Apple 支持文档）；荣耀官方 Mac 端同样是「Mac 电脑上自动弹出提示，根据提示接收文件」（荣耀支持 zh-cn15899624） | 通知详情视图最多 4 个动作按钮、应提供省去打开应用的动作、反对「仅打开应用」式按钮（HIG Notifications）。→ 通知带「接受」「拒绝」两个动作，点「接受」直接开始接收，与现有配对弹窗「允许/拒绝」的语义衔接 |
| **面板内请求卡片（推荐为第二通道）** | AirDrop 先例：若接收 Mac 在 Finder 中打开了 AirDrop 窗口，接受/拒绝提示出现在**该窗口内**而非通知中心——「应用前台在场时把请求放回应用」 | 面板展开时，同一请求同步显示为互传页顶部置顶请求卡片，两个入口状态一致。卡片信息结构对齐 LocalSend 接收页：「{设备} 想给你发送 n 个文件」+ 保存位置选择 + 发送方附言（localsend i18n receivePage 键）。键盘操作学 PairDrop：Enter 接受 / Esc 拒绝（PairDrop docs/faq.md；面板已有 `onExitCommand` 处理先例，`MenuView.swift:165`） |
| **菜单栏图标变化（仅辅助）** | 无主流竞品把请求确认放在菜单栏图标上 | 可做「未处理请求」徽标，但 HIG 明确额外项会被系统隐藏，不能作为可靠通道，只作辅助信号 |

### 可见性为什么必须双通道

AirDrop 有「只有提示音、通知横幅不弹出」的社区故障报告（Apple Discussions 251841064）；KDE Connect 官方文档把「收不到配对确认提示」的最常见原因列为系统通知权限未授予，论坛亦有同类报告（KDE 论坛 45828）。结论：**未处理请求必须同时存在于面板内**，以未读计数/待处理区块呈现（`MenuView` 已有 `filterTab` 计数徽标模式，`MenuView.swift:305-318`；「N 条新增」模式见第四节），通知通道失效也不丢请求。互传页内可给一句「系统通知权限未开启」的提示，仿 `disconnectedState` 的分步指引（`MenuView.swift:564-585`）。

### 安全分级与文案

- 默认**每次询问**；提供三档「自动接收：关 / 仅已配对设备 / 开」，对齐 LocalSend「快速保存：关 / 仅收藏设备 / 开」；开启任意自动档时弹一次性警示（对齐 LocalSend quickSaveNotice：「文件请求将被自动接受，局域网内任何人都可以给你发送文件」）。
- 可选 PIN 校验档参考 LocalSend 实现：错误 PIN 返回 401、单 IP 三次失败后 429 封禁（localsend `pin.rs`，`MAX_PIN_ATTEMPTS = 3`）。
- UI 文案不得把 PIN、配对或证书固定称为「加密」（AGENTS.md：加密复用成熟格式，不将应用锁等称为加密）。
- 「仅已配对设备」档直接读现有 `PairedDeviceStore`（`FolderSource.swift:73-116`），传输通道复用 `FileWeaveFolderSource` 的证书固定 + Bearer + 禁重定向安全模型（`FolderSource.swift:498-536`），不另起无鉴权通道。

### 忙碌态

LocalSend 协议同一时刻只允许一个活跃会话，忙时对新请求明确拒绝（localsend `v2.rs` prepare-upload 单活跃会话规则）。FileWeave 接收也应单会话：后来的请求明确反馈「对方（本机）忙碌，请稍后再发」，不静默吞掉。

## 三、传输中状态：进度、取消、多文件队列

- **HIG 要求**：尽量用确定性进度指示并保持推进、位置一致；可中断就给取消按钮；描述文案要准确具体，避免「加载中」这类空话（HIG Progress Indicators）。
- **面板内呈现**：互传页列表行直接套 `RecordingRow` 行骨架——勾选标记（14×14）/类型 SF Symbol 图标 + 双行文本（13pt 标题 + 11pt 次行）+ 行尾动作槽 + `contextMenu`（`MenuView.swift:844-923`）。多文件时每文件一行，行内状态：等待 / 接收中 / 已完成 / 失败。
- **进度指示**：进行中行的行尾动作按钮换成 12×12 小 `ProgressView` 或自绘 `ProgressRing`——两者都是现成写法（`MenuView.swift:950-955`、`970-976`、`1294-1309`）。整体进度在请求卡片/页头给确定性进度，文案具体到文件与序号：「正在接收 照片_013.jpg（2/9）」，仿转录流程 phases 字典 + 底栏 infoText 的「正在做什么」写法（`AppState.swift:127-128`、`1363-1440`）。
- **取消**：卡片/页头与行内都给取消按钮（HIG：可中断就给取消）。取消后已落盘的部分文件如何处置要在文案里明示。
- **状态建模**：沿用忙碌集合模式——`Set<String>` 管逐条忙碌、`[String: Double]` 管进度、`[String: Phase]` 管阶段（`AppState.swift:112-128`）；互传连接状态仿 `LinkState` 建枚举，title 供状态行、hint 供「用户该动哪一边」的一句指引（`AppState.swift:5-37`）。
- **提示出口**：接收相关的错误/信息提示必须走 `AppState.note()/notify()` 单一出口与 12 秒 TTL 过期机制（`AppState.swift:436-460`）——代码注释点名的实测坑：绕过该出口直接给 `errorText` 赋值，提示会被过期清理漏掉而卡死在底栏。
- **队列规则**：同一时刻一个活跃接收会话；会话内多文件串行排队展示（LocalSend 单活跃会话；PairDrop 多文件传输显示整体进度条、完成自动落盘）。
- **面板收起时**：传输不应中断，状态要在下次展开面板时如实回放。轮询节奏已有 `isPanelVisible` 密/疏切换的先例（面板开 6 秒 / 关 30 秒，`AppState.swift:300-314`），接收进度刷新可同法。

## 四、完成与失败

### 完成

- **系统通知**：「已接收 n 个文件」+「在访达中显示」动作按钮——HIG 允许最多 4 个动作且反对仅打开应用式按钮，点击动作直接 `NSWorkspace` reveal，不先开面板。
- **落盘位置**：沿用 `.state/inbox` 与 `BridgeProcess.openInbox()`（`BridgeProcess.swift:190-196`），入口保持在状态段 + 右键精简菜单双挂载（`MenuView.swift:93-102`、`AppController.swift:276-280`）。
- **面板内**：复用「有新文件」的现成模式——footer 的 sparkles「N 条新增」按钮 + Library 水位线记账（`MenuView.swift:645-659`；`Library.swift:191-204`），或在「收件箱」按钮旁加计数角标（`MenuView.swift:93-102`）。接收历史行提供 **打开 / 在访达中显示 / 从历史移除** 三个动作，对齐 LocalSend 历史页的 打开/在文件夹中显示/信息/删除（砍掉本产品用不上的「信息」）。

### 失败

- **结果细分**：对齐 LocalSend 接收会话 9 态状态机，至少取四种可见结果——忙碌（recipientBusy）/ 已拒绝（declined、canceledByReceiver）/ 已取消（canceledBySender）/ 部分失败（finishedWithErrors），每种都给可见状态，不静默吞掉。
- **重试**：显式「重试」按钮，不自动重放——与既有约定「用户文件操作失败不会被自动重放」一致（`docs/FEATURES.md:50`）。错误文案走底栏 `note()` 出口 + 行内状态标注。
- **指引**：失败态文案仿 `LinkState.hint`——每种失败给一句「下一步怎么办」（`AppState.swift:5-37`、`24-36`），例如「对方取消了这次发送」「部分文件失败，点重试只补失败的文件」。
- **破坏性操作的确认**：清空接收历史等动作沿用「再点一次同图标」armed 模式（`MenuView.swift:129-151`）；批量级确认用 `NSAlert` 而非 SwiftUI `.alert`——popover 内 SwiftUI 弹层易失焦，代码注释点名（`MenuView.swift:1239-1240`）。

## 五、与 FileWeave 现有配对、录音共享 UI 的视觉一致性

接收 UI 不引入新的视觉规范，全部沿用录音面板已在用的模式（`docs/UI_GUIDELINES.md:1-11` 只约束 Android 端，Mac 端成文规范缺位，以 `MenuView.swift` 实际写法为准）：

| 维度 | 现有写法（代码依据） | 接收 UI 的沿用 |
|---|---|---|
| 布局/尺寸 | `Metrics` 常量集中管理：面板宽 440、行内边距 14/7、单行高 30、双行高 50、图标 15、列表上限 540 且不超屏 62%（`MenuView.swift:5-31`、`63-64`） | 互传页行高、边距、图标尺寸直接取同一常量 |
| 配色 | 全系统语义色：状态点绿/橙（`MenuView.swift:71-73`、`200-206`）；行背景 选中 `accentColor.opacity(0.15)`、待确认删除 `red.opacity(0.10)`、回收站 `secondary.opacity(0.08)`（`MenuView.swift:940-945`） | 失败行背景用 red 系、等待态用 secondary 系，不新造色值 |
| 字阶 | 显式字号阶梯 13/12/11/10pt，计数与时间一律 `.monospacedDigit()`，强调 `.medium/.semibold`（`MenuView.swift:866-885`、`310-313`、`589-594`） | 「n 个文件」「2/9」等计数用 monospacedDigit |
| 图标 | 全 SF Symbols；文件类型集中在 `RecordingKind.symbol`：phone.fill/message.fill/waveform/doc.fill（`Recording.swift:20-27`）；菜单栏图标 `arrow.left.arrow.right`（`AppController.swift:177`） | 新文件类型只需在 symbol 表补项；接收状态图标从 SF Symbols 取（如 tray.and.arrow.down 沿用收件箱） |
| 控件 | 按钮 `.buttonStyle(.plain)` + `foregroundStyle` + `.help()` 悬浮提示（`MenuView.swift:248-255`）；菜单 `.menuStyle(.borderlessButton)` + `menuIndicator(.hidden)` + `fixedSize`（`MenuView.swift:756-758`、`799-801`） | 接收开关、动作菜单、历史行按钮同款 |
| 忙碌态 | 逐条忙碌用 `Set<String>` 管理，视图把行尾按钮换成 12×12 小 `ProgressView`（`MenuView.swift:950-955`、`970-976`；`AppState.swift:112-128`） | 接收中/取消中同法 |
| 空状态 | 三段式：20pt 图标 + 13pt 状态标题 + 11pt 次级指引，分「筛选后为空 / 整机为空 / 未连接」三种语义（`MenuView.swift:528-585`） | 互传页空态（无历史/未配对/通知权限未开）同结构 |
| 加载态 | header 内 `ProgressView().controlSize(.small).scaleEffect(0.5)`（`MenuView.swift:241-245`）、自绘 12pt `ProgressRing`（`MenuView.swift:1294-1309`） | 传输整体进度与行内进度从这两款里选，不引入第三方进度组件 |
| 状态建模 | `LinkState` 枚举：title 供状态行、hint 供可照做的指引（`AppState.swift:5-37`） | 接收状态枚举同构 |
| 持久化 | `library.json` 容错解码：缺键不解析失败、整份不因加字段作废（`Library.swift:153-167`）；配对记录 `PairedDeviceStore` 按 size+mtime 缓存重读（`FolderSource.swift:73-116`） | 接收历史/自动接收设置按同样容错策略持久化 |
| 安全 | 证书固定 `PinningDelegate` + Bearer + 禁重定向（`FolderSource.swift:498-536`）；配对批准弹窗文案与「允许/拒绝」语义（`BridgeProcess.swift:139-147`） | 接收通道复用，不另起无鉴权通道；请求卡片的允许/拒绝文案与其一致 |

## 六、明确不做：与项目边界冲突的设计

以下设计在竞品中存在、但与 `docs/PROJECT.md` 第一版边界或 AGENTS.md 约定冲突，**本报告明确不推荐纳入**：

1. **不做「同一荣耀账号」式账号绑定/云端路由接收**。第一版边界明确云账户不进入（`docs/PROJECT.md`）；荣耀官方 Mac 端依赖「同一荣耀账号 + 同一 WLAN」的前置（荣耀支持 zh-cn15899624），其协议侧逆向已另行评估为不推荐（`honorconnect-protocol-assessment.md`）。
2. **不做对局域网内任何设备默认自动接收**。LocalSend「快速保存=开」的能力不作为 FileWeave 的默认态；默认仅已配对设备 + 每次询问，自动档必须是显式开启并带一次性警示（第二节）。
3. **不做跨互联网中继接收**。第一版边界明确排除（`docs/PROJECT.md`）。
4. **不做后台自动双向同步式的「接收」**。既有共享是 Mac 主动拉取的浏览/手动操作，不是后台自动同步（`docs/PROJECT.md`）；接收请求因此保持显式确认/显式自动档语义，不做静默持续对拷。
5. **不做剪贴板历史**。第一版边界明确排除（`docs/PROJECT.md`）；剪贴板互传维持「用户明确操作后发送、接收可预览」的现状（`docs/FEATURES.md:48`）。
6. **不在 UI 文案中把配对、PIN 或证书固定称为「加密」**（AGENTS.md：加密复用成熟格式与实现，不将应用锁等称为加密）。
7. **不自创传输/加密格式，接收不另起无鉴权通道**（AGENTS.md；复用 `FileWeaveFolderSource` 安全模型，`FolderSource.swift:498-536`）。
8. **不做独立的接收窗口/常驻 Dock 界面**。新功能应进入已有导航和打开文件流程，避免多个互不相干的工具页（`docs/PROJECT.md`）；接收中心收在现有菜单栏面板内。
9. **不自动接入统计或崩溃上报、不恢复上游 Firebase 配置**（AGENTS.md）。
10. **不依赖 NFC 碰一碰作为 Mac 端接收触发**。Mac 普遍无 NFC 硬件，荣耀官方「碰 Mac 机身任意位置」的体验不可复制（荣耀支持 zh-cn15899624），本方案以系统通知 + 面板卡片替代其弹窗式接收。

## 调研方法与局限

- FileWeave 现状全部以本仓库代码为准；本报告所引 文件：行号 均在 2026-10-25 逐一读源核对，`UNUserNotificationCenter` 缺失用 `grep -rn "UNUserNotificationCenter" mac/Sources/` 实际执行验证（零匹配）。
- 竞品结论来自 2026-10-25 的检索结果（Apple HIG 四页经其 JSON 数据端点提取正文验证；LocalSend/PairDrop 结论落到具体仓库文件与键名）；本报告未对全部 URL 重新逐条抓取复核。
- **已知缺口**：AirDrop 接收横幅的截图级视觉样式，Apple 文档与可访问页面均未提供，本次只能确认其行为与按钮语义，未能核实视觉细节；PairDrop「按设备自动接受」仅见于网页搜索摘要、未能在其仓库文档核实原文，采信度低，未作为设计依据。
- 本报告仅为调研产出，不声称任何条目已实现；实施前应按 AGENTS.md 完成相应构建检查。

## 来源清单

**Apple（AirDrop / HIG / 社区）**

- https://support.apple.com/guide/mac-help/use-airdrop-to-send-items-to-nearby-devices-mh35868/mac （接受/拒绝行为、下载文件夹、同账号自动接受、AirDrop 码 30 天、Finder 窗口内提示）
- https://support.apple.com/guide/mac-help/use-airdrop-on-mac-to-send-items-to-nearby-devices-mh35868/mac （接收范围设置位于菜单栏控制中心）
- https://developer.apple.com/design/human-interface-guidelines/notifications （通知动作上限、Title Case、反对仅打开应用式按钮）
- https://developer.apple.com/design/human-interface-guidelines/progress-indicators （确定性进度、可取消、具体文案）
- https://developer.apple.com/design/human-interface-guidelines/the-menu-bar （额外项可被隐藏、点击显示菜单、是否放入由用户决定）
- https://developer.apple.com/design/human-interface-guidelines/drag-and-drop （拖放高亮与占位反馈；本报告仅作相邻规范参照）
- https://discussions.apple.com/thread/251841064 （AirDrop 只有提示音、横幅不弹出的故障报告）
- https://apple.stackexchange.com/questions/366451/no-prompt-to-accept-files-when-airdropped-to-myself （同账号设备间无提示直接传输的用户印证）

**LocalSend**

- https://github.com/localsend/localsend （`app/assets/i18n/en.json`、`de.json`：receivePage/receiveOptionsPage/general.quickSave/dialogs.quickSaveNotice；`packages/core/src/http/server/common/pin.rs`：MAX_PIN_ATTEMPTS=3、401/429；`packages/localsend_isolates/lib/model/session_status.dart`：9 态状态机）
- https://github.com/localsend/protocol （v2 协议 prepare-upload 单活跃会话、pin/pinAttempts 字段；v1.md 同规则）

**KDE Connect**

- https://userbase.kde.org/KDEConnect （双向确认配对、接收默认存「下载」、可在设置更改）
- https://userbase.kde.org/Tips/Pairing_your_phone_and_PC_with_KDE_Connect （配对确认依赖通知权限）
- https://discuss.kde.org/t/kdeconnect-nearly-never-shows-notifications-on-pc-and-file-transfer-doesnt-work/45828 （通知不弹出的论坛报告）
- https://discuss.kde.org/t/no-longer-able-to-share-files-with-kdeconnect-1-32-5-on-android-14/22935 （Android 14 上共享失效的论坛报告，佐证跨版本脆弱性）

**PairDrop**

- https://github.com/schlagmichdoch/PairDrop （README.md：接受后才传输、完成自动下载、多文件 ZIP、整体进度条；docs/faq.md：Enter 接受 / Esc 拒绝）
- https://pairdrop.net/ （产品形态参照；其中「按设备自动接受」描述未能在仓库文档核实，采信度低）

**荣耀互联（被替代的现行体验）**

- https://www.honor.com/cn/support/content/zh-cn15899624/ （手机→Mac：同一荣耀账号 + 同一 WLAN、碰后 Mac 自动弹提示；一碰传入口）
- https://www.honor.com/cn/support/content/zh-cn15835014/ （Windows 电脑管家连接流程）
- https://www.honor.com/cn/support/content/zh-cn15886296/ （电脑→手机 荣耀分享拖拽）
- https://www.honor.com/cn/tech/connect （荣耀互联产品页）

**FileWeave 仓库（现状依据，均为本次实读核对）**

- `mac/Sources/FileWeaveMacKit/recordings/MenuView.swift`（面板结构、Metrics、行骨架、状态色、字阶、控件样式、空态、footer、armed 确认、NSAlert 注释、ProgressRing）
- `mac/Sources/FileWeaveMacKit/recordings/AppState.swift`（LinkState、忙碌集合、note() 出口、轮询/退避、环境监听、转录 phases 与文案）
- `mac/Sources/FileWeaveMenuBar/AppController.swift`（NSStatusItem、transient popover、精简菜单、状态栏图标）
- `mac/Sources/FileWeaveMacKit/BridgeProcess.swift`（配对批准弹窗、openShared/openInbox）
- `mac/Sources/FileWeaveMacKit/recordings/FolderSource.swift`（PairedDeviceStore、PinningDelegate、dataTaskSync）
- `mac/Sources/FileWeaveMacKit/recordings/Library.swift`（library.json 容错解码、水位线新增记账）
- `mac/Sources/FileWeaveMacKit/recordings/Recording.swift`（RecordingKind.symbol）
- `docs/PROJECT.md`（第一版边界）、`docs/FEATURES.md:47-54`（现有互传流程与不重放约定）、`docs/UI_GUIDELINES.md:1-11`（仅约束 Android 端）
- `docs/research/honorconnect-protocol-assessment.md`（协议逆向不推荐、集成互传体验的推荐路径）
