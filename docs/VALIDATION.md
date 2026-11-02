# 验证记录

日期：2026-10-22 起，2026-11-02 更新。此记录说明实际完成的验证，不等同于独立安全审计或全设备兼容性认证。

**最新状态（2026-11-02，菜单栏稳定性与导航）：Mac 已保数据覆盖更新至止抖、非根目录面包屑及隐藏文件偏好版本。** 前一轮头部／行状态改版保留为历史记录；本轮证据位于 `build/outputs/popover-stability/` 和 `build/outputs/density-acceptance/shake/`。

## 菜单栏止抖、面包屑与隐藏文件（2026-11-02）

- 独立代理在此前实际安装版本复现左键打开抖动：三次打开都有 12pt 纵向变化，第一次出现多次往返。另一个原生 QA 采样还记录到首次宽度从 746pt 缩至 649pt。原因是 SwiftUI preferredContentSize 与手动 contentSize 同时控制尺寸，且打开后多个定时回调和 resize 回调又重新定位窗口。
- 现在显示前按状态栏所在屏幕确定有界内容尺寸，由 AppKit 单独管理尺寸、NSPopover 管理锚点位置。移除打开后的多轮 setFrame／makeKey，改用显示及激活生命周期完成焦点准备。独立 QA 连续三次实际打开、每次至少 1.2 秒，位置与尺寸变化均为零；外部点击关闭正常。 最终安装版另有五次完整 ≥1.2 秒采样，位移及尺寸变化同为零、内容区为 720×640；提前关闭的不完整采样未计作通过，未将关闭原因无证据归因于环境。
- 非根目录恢复独立一行面包屑，支持祖先点击、当前层级优先显示及完整路径访问；实际存储根目录不保留空行。工具栏范围菜单保留文件范围职责，不再替代路径导航。
- Mac 增加全局持久化“显示隐藏文件”，默认关闭，菜单栏和独立窗口的手机文件／收件箱共用。显示过滤先于搜索、数量和全选，关闭后清除不可见选择；收件箱保留 macOS 原生隐藏标记规则。普通用户点号文件随偏好显示，但两种实际接收临时命名（32 位十六进制及带连字符 UUID）始终排除。没有改变文件内容、权限、传输或录音扫描语义。
- Android 已有全局持久化同名设置，默认关闭，本轮未修改或重新部署 Android。其现有 provider 隐藏判定保持原状，本次没有扩展压缩包或递归搜索语义。
- Swift 全套 68 项测试：0 failures、1 个既有 live 测试跳过；随后针对最终临时命名修正的 5 项隐藏规则回归再次通过。中英资源检查、diff check、debug／release 构建和严格签名校验通过。
- Mac 最终源包与已安装二进制 SHA-256：`3fad0ac13540a0494124a7b428b4c07038fd72ee9d2b2c639c5c5424044c71af`。覆盖过程中 2386 项已有数据未缺失，7 个配对／凭据文件及偏好文件校验不变；旧 bundle 留存本地临时备份。独立复核首次点击 More 可展开；搜索焦点、面包屑导航和隐藏开关重启的独立 GUI 检查未完成，不计作通过。实际安装版本连续采样与交互范围详见 `build/outputs/density-acceptance/shake/REPORT.md`。


## 头部密度、文件状态与路径（2026-11-02）

- Mac 面板统一设备／连接、当前位置、搜索、刷新和更多；按实际内容宽度收纳，不再固定给搜索单独一行。当前位置菜单按短标题所需宽度呈现，长目录限制宽度并保留完整路径及祖先导航；次要活动可并排或退到紧凑第二行。独立窗口仍保留完整路径工具。
- 修复原双击手势拦截单击选择的问题，使用原生 List primaryAction；实际单击、Shift 扩选、Cmd 增减选择、键盘导航和双击目录通过。移除叠加的自定义选中描边／底色，文件、录音及收件箱统一行布局；真实鼠标移动与点击的成对截图证实悬停和原生选中宽度一致：浅色 720pt、深色长路径 420pt。早期列挤压、重复选中样式和悬停宽度不一致均已修复。安装验收另发现真实 NSPopover 的列表内在高度折叠问题，恢复稳定工作区高度后重新构建覆盖；该小修重新完成 debug／release 构建及签名检查，未重复整套单元测试。 独立代理最终在已安装实例的真实菜单栏弹出框复验：列表可正常滚动，27 项中约 16 行同时可见，头部与列表没有越界。
- Android 主存储标签使用应用中英资源，保留自定义存储名称和真实文件名。工具栏按实际文件区域宽度与字体缩放收纳筛选／视图，已启用筛选仍显示提示。侧栏长标题可换行，大字体时开关与设置移到标题下；面包屑随宽度变化重新显示当前层级，完整路径可达。最近文件目录去掉重复主存储前缀并优先显示尾端。
- Android 196 项 JVM 测试通过，lint 0 errors、1404 warnings、3 hints；906 项英文资源均有简中对应。隔离界面检查覆盖中英、窄／宽屏及 150% 字体；不把编译成功当成视觉通过。Mac 60 项测试中 59 通过、1 项真实转录测试按既有条件跳过，release bundle 及严格签名校验通过。
- Android 用覆盖安装保留数据，包与设备 base.apk SHA-256 均为 `556c33e145861f7bb05ea72cc98cae183452d201ee74c8914db22a23d9c2f7a8`；AppId 10390、安装前偏好及 4 个配对凭据校验不变，冷启动返回 ok。调试亮度／超时等已恢复。独立复核时用户正在其他应用，未切回或盲点；本轮不宣称独立完成了运行中真机的全部视觉流程。
- Mac 已替换旧进程并正常启动最终 release，安装与源二进制 SHA-256 均为 `c777463f2cdc33581dd878612b3f17570b0182bbe6e9fe4d6f5ac442d413d837`。2386 项已有数据未缺失，7 个配对／凭据文件及偏好文件校验不变；旧 bundle 留在本地临时备份。没有清空数据或配对。
- 本轮证据与独立报告位于 `build/outputs/density-acceptance/`。真实手机原始数据及截图保留在本地私有临时目录，归档报告仅保留必要摘要；没有扩测真实传输、媒体或转录语义。


## Mac 正常覆盖升级与无标签概况（2026-11-01，后续授权）

用户随后明确授权更新正在运行的 Mac 旧版、保留数据并实际运行，再调整菜单栏减少标签。本节是上述重构验收后的后续阶段；下方“未覆盖安装”的表述保留为当时的历史结果。

- 先定向停止旧安装实例 PID 63592 及其已核实、属于本仓库 state 的 bridge PID 63603，覆盖 `/Applications/FileWeave Mac.app` 并实际启动 PID 90007；完成界面调整后再次定向更新，最终部署验收实例 App PID 95622、直属 bridge PID 95628；验收结束后去掉临时截图／自动展开参数正常重启，同一二进制的新 App PID 96588、直属 bridge PID 96595。没有宽匹配停止其他 Python／QA／开发实例。
- 两次覆盖均保留 2386 个已有数据文件，覆盖完成、启动之前 missing=0；7 个配对、凭据与证书文件哈希一致。最终正常运行后再次检查，原有数据文件仍 missing=0。旧 bundle 在 `/tmp/FileWeave-before-initial-20261101-213354.app` 与 `/tmp/FileWeave-before-final-20261101-215727.app` 留存可回滚，Application Support、偏好及 `mac/.state` 未清空。
- 最终源包与安装后二进制 SHA-256 均为 `f657c63f5af89c11cd0c555c45e341026f6976149b13493719e5359dab7823f7`；bundle id `org.fileweave.mac.dev`、版本 `1.0 (1)`，以哈希补充区分本次开发构建。安装包严格签名校验、英中本地网络用途 plist 校验通过。
- 菜单栏默认页不再有四个等权标签：连接、任务／错误与最近录音、收件概况直接可见，主要文件紧接着显示；录音／收件通过概况链接进入，设备等通过状态或 More 按需进入，次级内容有 Back to Files。每次左键回到文件工作面板，但共享的目录、搜索与选择保留。概况与页面局部任务状态去重，独立窗口保留自己的状态栏。
- 未读取的录音不显示假 0；只有成功扫描才给出数目，离线旧结果标为上次记录。收件首次读取、失败与成功空结果分别呈现，最近项按实际修改时间读取，概况读取不创建不存在的收件目录。
- Swift debug、60 项测试及实际 release bundle 构建通过：59 项通过、1 项需显式启用的真实转录测试跳过；原始警告仍保留。增加了概况已读取／未知计数、无目录不创建、临时项过滤及最近项的回归验证。`mac/install.sh` 已去掉宽 `pkill`，只匹配安装路径及其事先确认的 child bridge，验证 staged bundle 并备份旧 bundle；本次两次实际覆盖另使用核实 PID 的同等定向流程。
- 真实已安装面板截图 `/tmp/fileweave-mac-overview-installed-en.png` 来自正常 release 进程，capture 时 key=true、appActive=true。当前手机未连通，面板准确显示离线及等待读取录音；Mac 本地收件箱正常读取到 4 项并显示最近项。不得据此声称新的手机覆盖安装或跨端传输通过。
- 没有添加只读产品开关或禁用用户正常后台任务。运行前后均为 30 个本地转录记录、20 个待手机同步；正常进程仍可按既有逻辑自动续同步。人工验收不主动发送、下载、转录、摘要或删除真实文件，因此“人工操作只读”不等同于整个后台进程没有写入。
- 独立代理在真实 release 面板点击 Inbox（4 项真实只读元数据）→ Back → More → Device → 语言选项（English 已选，简体中文可达，取消未改偏好）→ Back → Open in Window 均通过；未打开真实文件内容或人为发起复制、下载、转录、删除。CUA 对 Close 的状态读取可能隐式重开，故不据此重复判断关窗；此前原生生命周期 smoke 结果仍单独保留。
- 最后正常实例 96595 的 TCP `*:8787` 监听和本机 `_fileweave._tcp.` Bonjour 发布均实测存在；手机当前离线，不将服务在本机正常运行称为跨端连接通过。证据整理至 `build/outputs/redesign-acceptance/mac/overview-live/`。

## 原生工作界面、间距与中英双语（2026-11-01）

用户批准原生重构，并进一步要求菜单栏左键直接可用、注意间距与排版。本轮由 Android、Mac、语言实现代理和独立验收代理分工，均按用户要求使用 GPT-6.1 Sol / high。工作区原有改动保留，没有提交或发布。

- Android 主导航与设备、传输、共享文件、剪贴板、保险库及转录列表接入 Compose / Material 3。原有文件 Fragment 保留路径、滚动和选择；文件浏览、设置、局域网分享管理和部分预览继续复用 Views，不宣称全应用已改写。系统选择器不显示主导航，任务标题与路径分行；窄屏次要工具进入菜单。音频进度触摸高度增至 48dp。
- Mac 默认只驻留菜单栏，左键打开文件／录音／收件箱／设备工作面板，直接搜索和操作；独立可调整窗口为可选入口。面板与窗口共享目录、选择和文件任务；当前窗口接收搜索快捷键，容量历史放在设备页按需展开。用户标注的固定快捷面板空槽已移除，顶部状态紧凑分组，主要区域用于实际列表或页面。
- Android、Mac、局域网网页默认英文并支持简体中文，保存显式语言选择。Android 旧版明确中文选择可迁移，API33+ 系统应用语言作为来源；网页切语言保留目录／预览／剪贴板页面及授权范围。用户文件名、路径、剪贴板和转录正文保持原样。Mac bundle 包含英中本地网络用途说明。

### 构建与自动检查

- 最终 Android `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 成功；直接统计 XML 为 27 suites、192 tests，0 failure／error／skipped；lint 0 error、1402 warnings、3 hints。警告没有被整体关闭。稳定 APK 为 `build/outputs/fileweave-native-redesign-debug.apk`，核对 `org.fileweave.android`／45／0.4.0-dev。
- `python3 scripts/check-i18n.py` 通过：905 项英文 UI 资源有简体中文对应，资源类型、数组、格式参数和复数形式一致。独立 Kotlin/JUnit 语言及分享内核检查 37 项通过，已包含在正常 Gradle 测试范围内，不叠加计数；覆盖语言、Cookie、权限范围、HTML 转义、随机读取、HEAD、取消与撤销。
- Mac debug 构建、59 项 Swift 测试和 `mac/build.sh` 的 release 构建／组装成功；58 项通过、1 项需显式启用的真实转录测试跳过。`mac/build/FileWeave Mac.app` 的签名校验及 Info.plist、英中 InfoPlist.strings 解析通过。构建仍有既有 Swift 警告；QA 原生表格出现过 reentrant-operation 警告，不记为无警告。
- 根代理复跑资源检查、统计 Android 测试／lint XML、检查 Mac bundle 与 `git diff --check` 均通过。未安装到真实手机，未覆盖 `/Applications` 中的产品。

### 实际界面和回归

- API34 隔离 `org.fileweave.android.redesignqa` 包，在 360×640dp 下执行英文／简体中文、浅／深主题矩阵，并验证真实自定义绿色主题。设备和传输页额外覆盖包内 150% 字体；不是所有页面的大字认证。四种文件选择 Intent 检查无主导航、标题无省略／重叠且筛选排序可达。文件路径、滚动和选择跨页／返回保留；隐藏文件页生命周期受限。
- 保险库使用生成数据验证实际密码解锁、随机偏移读取、点击锁定后旧读取句柄失效，以及离开页面时密码草稿立即清空。仅 debug 测试在确认隔离包后临时解除其截图标志以捕获生成内容，产品 FLAG_SECURE 保留；不读取真实系统剪贴板。
- 独立验收在 640dp 横屏实际发现 DrawerLayout 非 EXACT 测量崩溃；修复水平 LinearLayout 的 baseline 预测量后，宽屏三入口、返回及真实横竖屏重建通过。进一步发现文件页与设备页切换时整个根内容区下移 24dp；主导航壳固定 edge-to-edge 坐标，再统一导航系统栏留白后，实际 rail 标签位置为 128→128px，三页根区域同为 [0,0]／1280×720，切页与旋转断言通过。早期还发现并修复返回回调优先级、选择器标题叠字／截断和工具动作 ID 冲突。无效菜单排序导致的 QA 崩溃与蓝色冒充绿色的旧截图均不作为通过证据。
- Mac 隔离 QA 通过实际状态按钮 action 链路展开工作面板：主窗口未打开时，面板搜索获得真实 NSTextView 编辑焦点，可全选文件、切页返回；可选窗口共享选择。原生 smoke 检查 840×560 窗口、关闭面板后主窗仍保持轮询、关闭主窗进程仍运行、重开成功，以及 720×640 面板 key／active 状态。此为 debug 自动化调用真实控件链路，不能等同于独立人工点击。
- Mac 修复普通文件下载切页自动取消／任务不可见、关闭面板误停止主窗口轮询、搜索快捷键无焦点、活跃状态切语言不更新等问题。两宿主共享文件加载以 generation、路径和范围拒绝迟到结果，延迟根目录响应覆盖新目录的回归测试通过。
- Mac 最终去掉文件列表无内容区域的斑马条纹与录音页重复任务提示；根代理及独立代理目视最终英中面板、录音忙态和真正深色代表窗口。深色图由 DebugApp 局部 appearance 渲染，不冒充实际 active NSPopover，也未更改系统外观。21:05 有一轮 smoke 在桌面焦点未隔离时失焦，保留原始失败日志；隔离其他原生窗口后，21:08 当前源码完整控件链路通过，最终日志为 `build/outputs/redesign-acceptance/mac/smoke-isolated.log`。
- 局域网网页使用纯生成数据在实际浏览器检查默认英文、中文切换、访问码错误／成功、目录／图片预览保留、剪贴板原文、真实下载和语言持久化。390px 长中英文件名无水平溢出，800×600 图片按 324×243 显示；1280px 桌面布局可读。早期深色预览另行目视，最终长文件名与大图片截图为浅色，未以文件名冒称深色。

独立验收最终结论：在上述可验证范围内通过，无已知未修复 P1／P2。验收代理独立冷启检查修复后的横屏，文件／设备／传输三页 rail 标签 bounds 完全一致，再确认返回与竖屏底部导航。另实际点击 Android 设置的 Language→简体中文，页面即时更新；force-stop 后冷启主页面仍为中文，再从设置选择 English 恢复英文。此流程没有用直接写 LocaleManager 代替用户操作；完整系统语言矩阵仍不在覆盖范围内。

验收后 QA 包 force-stop，模拟器显示恢复为 1080×1920／320dpi、accelerometer_rotation=1、user_rotation=0，系统字体和语言未改。测试期间模拟器进程退出，曾无清空数据重启原 AVD；环境退出与坐标断言失败都保留原始记录。仅本次 Mac QA／Debug 进程已清理，真实已安装 Mac 程序仍运行；合成网页服务已停止，浏览器 QA 空间已结束。

截图与报告集中保存在 `build/outputs/redesign-acceptance/`，独立报告为其中 `independent-acceptance.md`。Android 完整英中／主题矩阵后，对最后主导航系统栏修复追加英文竖屏、真实绿色及宽屏坐标／旋转回归；没有把所有早期截图冒称最后源码重新生成。Mac 最终主证据为 `mac/files-en.png`、`files-zh.png`、`recordings-en.png`、`dark.png`；更早的固定快捷卡片和空条纹截图不代表最终实现。

### 验证边界

本轮未新增验证真实手机覆盖安装、API23 执行、中文系统首次启动及 API33+ 系统语言重置的完整冷／热启动矩阵、真实跨设备长时间传输、厂商后台策略、全部媒体／生物识别场景或真实 ASR 全链路。异常 Instrumentation 中断后曾观察到后台前台服务启动限制，该异常后台重启场景未修复或计入通过范围。Mac 界面数据为隔离 QA 合成样本；上述自动检查、模拟器和本机 UI 证据不能替代这些设备验收。既有历史验证记录保留在下方，不自动代表重构后的全量回归通过。

## Mac 手机存储容量与历史（2026-11-01）

- 最终按用户反馈将全部容量内容集中到右侧 240 点栏：剩余／总容量、占用比例、更新时间、当天变化、历史入口及趋势。左侧从文件／录音标签直接开始；历史在右栏切换，时间范围使用窄栏下拉选择，左侧页面切换不改变右侧历史状态。恢复缓存时仅显示上次更新时间，清除首次连接等待文案。49 项 Swift 测试中 48 项通过、1 项真实转录默认跳过；debug／release 构建、安装与签名核对通过。使用仓库外 `/tmp/fileweave-capacity-right.png` 和 `/tmp/fileweave-capacity-right-history.png` 检查两栏、摘要及窄栏历史排版；未声称完成真实鼠标点击回归。
- 后续按用户要求改为左右布局：左侧 440 点保留原信息／文件／录音／完整历史，右侧 240 点常驻 24 小时趋势，总宽 681 点，高度沿用 500 点上限。横轴为当前时刻往前完整 24 小时，每分钟推进；超出窗口／未来记录不画，现场样本去重，离线超过两小时或总容量变化分段，不将旧数据外推至现在。新增 3 项窗口／去重／分段／空时段测试，全包 49 项：48 项通过，1 项真实转录默认跳过。debug／release 构建和安装通过；仓库外 `/tmp/fileweave-split-panel.png` 检查两栏尺寸、真实单采样点与离线状态。未声称已积累完整 24 小时真实趋势或完成鼠标选点回归。
- Android `/v1/status` 在原有配对鉴权后提供主共享存储 `StatFs.totalBytes`／`availableBytes`，不扫描目录，不统计应用私有文件。JVM 回归覆盖鉴权前不读取容量、64 位字节值、未知／读取失败／无效容量仍可探测；`assembleDebug`、182 项 JVM 测试与 `lintDebug` 通过，lint 0 error、1327 warnings。构建使用本机 Homebrew OpenJDK 26.0.2.1；未提交本地环境配置。
- Swift 新增 7 项回归，覆盖旧端／畸形可选字段、未知卷、负数与超过总量、满盘、同名不同设备隔离、跨重启保存、五分钟限频／每小时首末记录、90 天淘汰、时钟回拨、总容量变化及写入失败不伪报保存。全包共 46 项：45 项通过，1 项真实转录／联网测试默认跳过；本轮不调用摘要 API。debug 和 release 构建通过，已有并发与弃用警告未在本轮处理。
- 先查询无线 ADB 在线 transport 与 mDNS，确认荣耀 VER-AN10 和已安装的 `org.fileweave.android`，再 `install -r` 覆盖安装并启动，核对更新时刻及两项共享前台服务。Mac 使用 `mac/install.sh` 更新 `/Applications/FileWeave Mac.app` 并核对进程启动。
- 现有配对证书固定 HTTPS 通道实际取得主存储总量 **492432261120 字节**、可用 **314446266368 字节**（菜单约 492.43 GB／314.45 GB），与同期手机 `df` 的分区总量一致；首次样本在本地历史文件落盘。手机随后无线 ADB 与服务域名解析都离线，重查 transport／mDNS 并扫描当前局域网的服务端口未找到在线服务；未将此状态称为重新连接成功。
- 使用共用面板的调试程序导出并人工检查 440 点宽菜单和历史页：容量／比例、首次历史点、设备名、时间范围、采样行、离线更新时间与返回入口均可见。截图位于仓库外 `/tmp/fileweave-storage-panel.png`、`/tmp/fileweave-storage-history.png`。原生 CUA 读取 `/Applications/FileWeave Mac.app` 超时（`Computer Use server error -10005: timeoutReached`），因此使用项目已有 NSView 截图入口，不声称完成真实鼠标点击回归。
- 历史从本轮启用后开始，90 天留存／压缩由确定性时间测试验证；没有积累 90 天真实运行数据，也没有实测厂商深度休眠后的长期在线采样。未收集或提交个人文件、配对凭据与历史数据。

## Mac 转录环境、摘要与旧应用退役（2026-11-01）

- 后续开启 FileWeave 登录自启：使用应用内置 LaunchAgent 后端，`~/Library/LaunchAgents/org.fileweave.mac.dev.plist` 指向 `/Applications/FileWeave Mac.app/Contents/MacOS/FileWeaveMac`，`RunAtLoad=true`，仅 Aqua 图形登录会话加载；launchctl 已 enable/bootstrap，核验实际运行 PID 与其持有 8787 监听端口的桥接子进程。重载时清理了上一个实例的孤儿桥接进程，防止端口占用。没有执行注销或重启电脑，不将当前会话的系统拉起验证称为重启实测。
- 使用 `env -i` 仅保留 HOME／USER／LOGNAME／PATH／TMPDIR，并显式开启真实回归输出目录；无 `FILEWEAVE_BAILIAN_ENV` 等配置覆盖。`TranscriptSyncTests` 3 项全部通过，默认应用数据目录中的 Python、模型和摘要凭据可加载；已安装的转录脚本与测试源码一致。登录项本身只设置 PATH，不依赖终端的凭据环境变量。报告位于应用数据目录 `validation/20261101-login/`，凭据仍为 0600。
- 发现迁移后的 `asr/venv/bin/python3.13` 仍指向已不存在的 Homebrew Python；改为 Python 3.12.8 重建，保留全部识别／说话人模型。安装脚本改用 `python -m pip`，修复失效环境、失败恢复、系统 CA 补齐及已有说话人模型目录；固定 sherpa-onnx 1.13.8／numpy 2.5.4。`setup-asr.sh` 必须成功打包，不再忽略复制失败。
- 将用户已有摘要凭据的必要字段写入 FileWeave 私有配置（0600），未写入仓库。真实调用使用 `qwen-flash`，普通测试不触发收费 API。
- 真机经 Bonjour 发现、既有配对指纹固定 HTTPS、Bearer 鉴权、扫描与完整下载后，88.1 秒真实通话完成本地识别，产出 27 段有效时间戳、正文与 JSON，推理耗时 6.6 秒。摘要接口返回 20 字结果，人工对照转录主题一致。说话人自动聚类为 5 类，仅验证产出与标签完整性，不代表真实人数或归属准确率验收。
- 首次选中的 13 秒录音音量极低、未识别出有效语音，1.13.8 与临时安装的 2.0.1 均失败，并出现 ONNX 负形状错误；未将该样本计入通过项。最终固定原引擎版本，使用已有转录证明含语音的完整录音复测成功；未宣称已解决静音／极低音量输入的模型错误。
- `swift test --package-path mac`：普通运行 35 项，0 failure，1 项真实手机／联网测试按默认跳过；旧组件卸载后显式启用真实回归，完整 35 项全部通过、无跳过。release 构建、安装、严格签名校验及启动通过；已有 Swift 并发与弃用警告仍在。最终重连时手机 AOD 休眠曾令服务探测失败，唤醒并将 FileWeave 切回前台后复测通过；不据此承诺厂商深度休眠长期可达性。
- 原有 30 条转录路径有效；模型与转录共 75 个文件建立 SHA-256 清单，清理前后核对。验证用个人音频、文本、摘要与报告保存在应用数据目录的私有 validation 子目录，均在仓库外。
- 关闭旧「荣耀录音监视器」macOS 登录项、正常退出并卸载；旧仓库完整 tar 和全历史 Git bundle 校验后清理本地工作目录，GitHub 仓库设为 archived。旧 Android `com.ericyim.recbridge` 也禁用自启、停止并卸载，事先保留 APK，手机 Sounds 目录按文件 SHA-256 核对未变。FileWeave 自身包与数据保留。
- 本机可恢复归档位于 `~/Archives/HonorRecorderBar-20261101/`，包含旧工作目录、Git 历史、App、偏好、旧签名材料及校验清单；未将这些材料提交到本仓库。

## 目录菜单精简与分层返回（2026-11-01）

- 目录菜单收敛到六个入口，应用级主页归侧栏；单文件和多选分享统一分类。生命周期绑定的 `ActionPanel` 提供标题返回、系统返回、父层位置恢复与短页自适应高度；文件菜单使用状态快照和捕获的文件对象，避免列表条目复用后错对象派发。后台／视图销毁时安全关闭，未承诺旋转后恢复打开的面板。
- 视图排序改为可连续调整的表单；格式入口复用筛选表单与后缀建议，顶部漏斗保留原有按需展开行为；编码提供搜索和未保存内容确认；队列条目、重排和清空取消共用层级容器。短菜单保持单层。
- 使用 JDK 21 与独立输出目录执行 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug`；180 项 JVM 测试，0 failure/error/skipped。最终构建成功，lint 0 error、1327 warnings、3 hints；日志、截图和产物指纹保存在 `build/outputs/menu-validation/`。不视为全仓无警告。
- Android 14 模拟器中，以独立 `org.fileweave.android.menuqa` 包运行 `MenuPanelInstrumentation`，仅生成自身缓存内文本和 WAV。通过目录六项、标题／系统逐级返回、筛选及普通目录压缩取消回原层、视图切换保持打开、快捷筛选展开／收起、队列返回／重排／清空取消、编码搜索／父层返回／重新加载的真实界面回归。测试包首次权限准备仅用于隔离 QA，不是产品默认依赖。
- 横屏、字体 1.3 倍的目录分享二级菜单截图与 UI 层级检查通过：返回按钮可见、操作文字完整，点击后恢复父菜单。旋转后面板安全关闭。检查后恢复字体与旋转设置，移除共享存储中本次生成的布局测试文件。
- 编辑期间两次 lint 分析出现 Kotlin FIR 内部异常，原始消息分别为 `FirAnonymousObjectSymbol expected, but FirRegularClassSymbol found`、`No fir element was found for KtParameter`；稳定源码后重新执行检查。初始模拟器首装权限弹窗及关闭动画曾造成自动回归等待超时，隔离权限准备与等待窗口恢复后复跑通过，没有把失败轮次记为通过。
- 交付包为 `build/outputs/fileweave-menu-debug.apk`，已核对 `org.fileweave.android` 包名，以 `install -r` 更新模拟器并保留数据；FileListActivity 冷启动 `Status: ok`。隔离 QA 包已移除。
- 本轮未做真机完整菜单回归、TalkBack 实机朗读、真实折叠切换及所有网络／压缩包／只读路径组合的人工验收；未改动播放数据源或保险库读取实现，也未将本轮菜单回归描述为这些模块的完整验证。


## 筛选统一漏斗图标（2026-11-01）

- 共用 `filter_icon_control_normal_24dp.xml` 由递减横线改为清晰漏斗，文件列表及用户／用户组筛选共同生效。保留上游版权、24dp 尺寸、主题着色与文字标签，排序图标不变；UI_GUIDELINES.md 记录后续筛选入口统一漏斗的规范。
- assembleDebug／lintDebug 成功，lint 0 error、1327 warnings、3 hints，git diff --check 通过。APK 中编译后的 drawable 路径数据与新漏斗一致。本轮为图标资源修改，没有增加镜像实现的测试或重复运行无关播放／加密回归。
- 用户打开无线调试后，自行发现在线已配对 transport、核对荣耀 VER-AN10，使用 install -r 覆盖安装并保留数据；已安装 APK SHA-256 与 `build/outputs/fileweave-funnel-filter-debug.apk` 一致。FileListActivity 冷启动 Status: ok，进程存在。验收摘要位于 `build/outputs/funnel-icon-validation/`。

## 快捷筛选按需展开与稳定选中态（2026-11-01）

- 快捷栏初始为 GONE，搜索／显式筛选展开时显示，仅类型／格式／日期条件可以让其在收起搜索后驻留；递归开关、无效扩展名或纯名称不作为驻留条件。恢复全部且退出搜索／筛选后隐藏，导航清空展开状态，多选结束也不会无条件显示。保留 ViewModel 内的显式展开状态以随旋转保留当前操作。
- 顶部提供直接筛选入口，工具栏标题／路径预留四个操作位以免覆盖；筛选 Chip 保留 checked 状态和主题色，禁用 checkedIcon。没有通过固定文字宽度或移除选中语义掩盖布局变化。
- 独立 `org.fileweave.android.filterui.recentqa` 模拟器包运行扩展的 SearchFiltersInstrumentation 通过：普通目录与最近入口均验证默认隐藏、搜索／筛选展开、Back 收起、具体类型驻留、恢复全部隐藏；实际图片／视频／音频切换前后，全部四个类别按钮的 width 和 left 完全一致，重复选中保持状态。格式 PNG／PDF、复杂条件、搜索清空与关闭、排序升降和导航重置的既有实际点击回归也通过。原生生成数据截图已目视，无勾号与工具栏重叠，结果位于 `build/outputs/filter-disclosure-preview/`。
- 正式包最终 assembleDebug／testDebugUnitTest／lintDebug 成功；180 项 JVM 测试无失败／错误／跳过，lint 0 error、1303 warnings、3 hints；git diff --check 通过。核对 APK 为 `org.fileweave.android`／45／0.4.0-dev，稳定包 `build/outputs/fileweave-compact-filters-debug.apk`。本轮未改索引、文件读取或分享协议，没有重复进行无关播放／加密测试。
- 生成缓存与仅本次隔离 QA 包已清理，未停止模拟器或修改产品 Root／全局显示设置。用户重新打开无线调试后，自行发现并扫描当前手机连接；广播存在旧端口及变化的服务名，最终动态选取在线 transport 确认荣耀 VER-AN10，执行 install -r 成功，手机 base.apk 的 SHA-256 与验收 APK 一致。FileListActivity 冷启动 Status: ok，进程存在；未把本轮真机安装／启动检查扩大称为全部真机交互回归。后续真机更新的自行发现偏好已写入 AGENTS.md。

## 默认全局与持续局域网分享（2026-11-01）

- 正式应用 ID、独立 buildDirectory 最终 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 成功；180 项 JVM 测试无失败／错误／跳过，lint 0 error、1303 warnings、3 hints。稳定 APK 为 `build/outputs/fileweave-global-share-debug.apk`，已核对应用 ID `org.fileweave.android`、45／0.4.0-dev。第一轮单测因网页精简文案后仍断言旧英文失败；改为验证未分享状态 HTTP 200 且不输出文本框，保留 token／文本转义／撤回断言后整套重跑通过。
- Android 14 模拟器独立 `org.fileweave.android.globalui.recentqa`：新空入口自动全局、初始无剪贴板快照，根页直接列存储目录；通过独占生成目录验证列目录、图片和下载 Range。全局排除 Android/data、Android/obb、存储别名／边界／符号链接逃逸；指定文件／文本和外部导入不因空参数扩大为全局，已有指定会话打开管理页仍保持范围。
- 实际点击停止，Activity 重建与返回前台不自动重开；重新开始得到新会话。模拟器实际休眠后专属测试文件仍可读，同一访问码继续授权。注入单调时钟验证 11 分钟及 3 天后访问码仍有效，保留全局每分钟 5 次限次、HEAD 不授权和停止／替换撤销。短时模拟器熄屏不代表厂商深度休眠长期可达性。
- 真实菜单点击验证「仅剪贴板文本」重建窄范围、「附加剪贴板文本」保持原范围以及清除快照；Popup 窗口使用同进程前台生命周期门槛，系统剪贴板权限限制仍保留。复制链接不改变网页快照，系统分享外部内容仍先确认。
- 在独立生成 gocryptfs 测试库上真实 unlock／lock，确认已打开保险库锁定会同步撤销分享；已锁状态的后台空 lock 不会停止普通文件共享。会话切换增加串行转换锁，原 native 读取监控与密码清零保留。Smoke 完整通过跨块随机读取、锁后失效、错误密码／重开、篡改检测、超过 4 GiB 读取、普通及库内 MP4 播放和跳转、中断导入清理、密文备份恢复与逐字节恢复比对。
- Ego Lite 通过仅本轮 ADB 转发访问模拟器上的生成目录：390px 浅深网页目视、documentWidth=innerWidth=390、图片 naturalWidth>0、浏览器实际下载事件保存的 TXT 与源 62 字节一致，以及剪贴板文本框通过。浏览器任务已结束，转发与生成数据已清理；不是 Windows／其他手机 Wi-Fi 网络实测。
- 原生页面浅色／深色共 18 张生成数据截图已逐张目视：默认全局、停止、指定内容，各含正常宽度、360dp 及 1.5 倍字体。复制／发送在所有运行大字视口中完整可见；二维码与地址没有重复说明或十分钟提示。真实停止后重建仍保持停止，已有指定范围打开新管理页不扩大。仅改隔离 QA 包主题／语言并恢复，测试服务、夹具和包均已清理。原生预览位于 `build/outputs/global-share-preview/native/`。
- 本轮已通过有效的无线 ADB 服务连接在荣耀 VER-AN10 上执行 `adb install -r` 覆盖正式包，保留应用数据；手机已安装 base.apk 的 SHA-256 与验收 APK 一致。启动 FileListActivity 返回 `Status: ok`／`LaunchState: WARM`，进程存在。真机此次仅验证安装与启动，不替代完整局域网分享／长期省电策略实测。
- 日志位于 `build/outputs/global-share-preview/`，网页预览位于 `build/outputs/share-ui-preview/global-browser-*.png`。真机相机扫码、厂商长时间省电策略和跨设备 Wi-Fi 访问仍待回归；服务运行期间无代码设定的到期，不承诺进程结束或换网后的旧地址继续有效。

## 最近文件搜索、快捷筛选与分享直达（2026-11-01）

- 最终 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 成功（独立 buildDirectory 的正式应用 ID 构建）；176 项 JVM 测试无失败／错误／跳过，lint 0 error、1298 warnings、3 hints。`git diff --check` 通过，稳定交付包为 `build/outputs/fileweave-search-sharing-debug.apk`，已核对 `org.fileweave.android`／45／0.4.0-dev。

- 最近查询与类型／扩展名／日期条件在完整索引候选进入分页前匹配；新增 4 项纯逻辑测试覆盖 600 个非匹配条目之后的 500＋1 匹配分页、目录排除、名称／类型／日期组合、未知创建时间以及有效搜索状态。旧源关闭时取消查询，generation 与 CancellationSignal 拒绝迟到结果。
- Android 14 模拟器独立 `org.fileweave.android.searchui.recentqa` 包：自建 1 个 PNG 后再建 501 个较新文本文件，确认未筛选首批只有 500 个且不含 PNG；完整索引图片、忽略大小写名称和 PNG 扩展名匹配均找到旧 PNG。实际最近页点击图片、清空名称、关闭搜索保留类型和切换入口清空条件通过；原 2＋1 分页、刷新窗口、白名单／排除及取消旧追加仍通过。
- 普通目录实际点击图片／视频／音频／全部及 PopupMenu 的 PNG／PDF／更多条件，验证快捷类型只看当前目录，名称清空与关闭保留类型，导航清空条件。类型筛选下实际切换名称升序／降序，结果顺序正确；重复点击已选图片仍保持选中。最后独立回归系统 Back：展开搜索时先关闭并保留类型，子目录返回父目录时清除全部条件，均通过。
- 分享 GET `/?code=123456` 与已有 POST 共用限次／有效期，HEAD 不授权；新增 9 项协议／续码测试覆盖 malformed／重复／额外查询参数、限次、到期和续码。独立 QA 包实际验证 Copy 复制完整直达链接，到期禁用 Copy／Send，更新码后旧码拒绝、新码直达，强令牌 QR／文件范围／剪贴板快照不变，以及停止／替换／导入临时快照清理。
- 分享原生页面的浅色／深色各 5 张生成内容预览已逐一目视，包含正常宽度、360dp 和 1.5 倍字体；断言实际夜间模式与列左右边界。保留 FLAG_SECURE，以 View.draw 绘制本次生成内容，没有修改全局主题／字体。调试截图初次遇到异步布局未收敛、应用主题覆盖和硬件位图不能软件绘制的问题，分别修正 QA 布局测量、包内主题设置和截图时的临时软件副本后重跑，未把脚本失败记为产品通过。
- 本轮自建 502 个 MediaStore 文件、3 个初始分页夹具及相应自动生成目录索引均按精确 URI 清理。普通文件 UI 测试仅在隔离 QA 包临时使用 RootStrategy.NEVER 并恢复，最近数据源仍以原策略验证；未修改产品 Root 默认值。并行聊天构建使用独立临时 buildDirectory，避免互相覆盖 APK。
- 页面预览在 `build/outputs/search-ui-preview/` 与 `build/outputs/share-ui-preview/`。本轮模拟器 QA 包已卸载，未关闭模拟器或清除其他包数据。真实相机扫码、Windows／其他手机跨 Wi-Fi、厂商后台与省电策略仍未验证。

## 最近文件按需分页（2026-11-01）

- 最终 `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 成功，163 项 JVM 测试无失败／错误／跳过；lint 0 error、1218 warnings、3 hints，`git diff --check` 通过。保存独立 APK `build/outputs/fileweave-recent-pagination-debug.apk`，核对应用 ID 为 `org.fileweave.android`，避免其他并行调试构建覆盖交付包。
- 顶部数量提示的布局、引用和字符串已移除；底部加载更多是实际列表末行，在网格中占满一行。保留文件行原索引、拖动选择边界和快速滚动提示。首批／追加均按可访问、目录规则、隐藏设置及去重后的文件计数，使用一个额外合格条目判断是否还有下一页。
- 新增 5 项纯分页测试覆盖 500／501 边界、相同新增时间下的 ID 游标、新文件插入、过滤／失效／重复／隐藏条目、缺失新增时间与取消。已有窗口在刷新时保留，修改范围或隐藏设置会清空旧结果并重置。
- 在已运行模拟器上安装独立 `org.fileweave.android.paging.recentqa`，仅新增 3 个独立目录的文本夹具。通过实际 MediaStore 数据源，以测试页大小 2 验证 2＋1 追加、无重复、末页状态、刷新保留窗口、范围重置及旧追加取消；正常产品页大小仍为 500。测试包和本次 3 个夹具已清理，未清空原应用或其他测试目录。
- 首次整仓构建被同时开发的 `PdfViewerActivity.kt` 中 `abc_action_bar_up_description` 未解析引用阻断；引用修正后重跑，没有把该次失败记为通过，也未覆盖其他文档预览改动。

## 循环播放、队列管理与图片沉浸（2026-11-01）

- 音频新增顺序播放、列表循环、单曲循环和随机循环。当前播放队列支持选择播放、上移／下移、移除和确认清空；移除仅操作播放队列，不删除文件。重排时保留 MediaItem 的原始 URI，与初始数据源索引对应，避免列表顺序变化后读取错误文件。本轮未实现命名列表持久保存或跨重启恢复。
- 视频锁定按钮移至设置入口右边、控制栏最右侧；解锁入口固定在相同位置。视频溢出菜单提供「循环播放」，并保存旋转时的循环状态。图片默认隐藏菜单与系统栏，点按图片显示，再点隐藏；隐藏的工具栏设为 INVISIBLE，避免拦截触摸。
- 隔离 `.playerqa` 包构建通过，使用临时 Gradle init script 将 buildDirectory 移至 `/tmp/fileweave-player-isolated-build`，避免共享构建输出被并行工作覆盖；未修改产品构建配置。该包的 `testDebugUnitTest` 成功：176 项测试，0 failure/error/skipped，按该目录 XML 报告统计。
- Android 14 模拟器：`AudioPlayerInstrumentation` 通过横竖屏播放与进度回归，额外检查队列重排／移除后源 URI 和当前条目保持一致、120 秒 WAV 末尾实际单曲循环回开头，以及生成 PNG 初始菜单隐藏、真实点按显示／再次隐藏。最初图片点按检查在 drawable 刚设置、布局／淡入尚未稳定时过早注入事件，最终改为等待窗口焦点、图片可见、尺寸和淡入完成后通过。
- `PlayerGestureInstrumentation` 横竖屏通过，包括锁定后误触防护、解锁和自动隐藏；新增检查锁定／解锁按钮的屏幕坐标逐项相差不超过 1 像素，并检查菜单／锁定状态截图，确认入口位于设置右侧。
- 普通 `assembleDebug` 和 JVM 测试曾完成，但整仓 lint 未通过：最后一次普通检查报告 12 errors、1284 warnings、3 hints，首项为 `RecentFilesLiveData.kt:168` 的 `SuspiciousIndentation`，其余包含 `strings_quick_filters.xml` 的 `ExtraTranslation`。这些文件属于工作区其他修改，本轮未调整或禁用检查。更早中断及共享构建还出现 Gradle daemon disappeared、打包 NullPointerException、`R.jar (No such file or directory)`；改用独立构建目录后验收包与单元测试通过，不声称整仓 lint 通过。
- 待真机验证循环／队列操作、厂商导航栏与超大字体；顺序／列表／随机模式已接入 Media3 原生 repeat/shuffle，本轮实际末尾自动回播验收覆盖单曲循环，未穷举随机顺序与所有队列末尾组合。

## 播放器界面与进度控制（2026-11-01）

- 采用用户确认的「简洁克制，跟随应用主题」方向：视频调节反馈放在底部，包含目标时间、变化量、总时长与细进度条；播放菜单新增触控锁定，点按唤出解锁按钮，返回键先解锁；锁定状态随 Activity 状态保存。音频使用 ViewBinding + XML 的主题化底部面板，补齐可拖动进度、已播放／总时长、图标控制、倍速与队列，并为文件浏览区域预留高度。
- 最终执行 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain` 成功；158 项 JVM 测试，0 failure/error/skipped；lint 0 error、1213 warnings、3 hints，保留既有及翻译等警告。`git diff --check` 通过。正式 applicationId 的 debug APK 已生成，未向真机安装。
- Android 14 模拟器独立 `.playerqa` 包运行 `PlayerGestureInstrumentation`：横竖屏、各区域横拖、底部反馈位置／高度、取消不提交、播放／暂停状态保留、亮度／音量、双击 ±10 秒、锁定时屏蔽横拖／亮度／音量／双击、解锁、自动隐藏和拖动中退出，均通过。截图在下一帧渲染后捕获，已检查底部提示与播放菜单的实际外观。
- `AudioPlayerInstrumentation` 使用本次生成的两份 120 秒 WAV，分别在浅色／深色模式和横竖屏运行通过：文件名与总时长、文件区域预留空间、实际 time-bar MotionEvent 拖动、取消不跳转、保留原播放／暂停状态、上下首、进度定时更新、旋转重新绑定与关闭。已检查明暗主题截图。进度回归等待播放器实际开始推进后再检查，避免把换曲缓冲耗时误判为定时器失效。
- `SmokeInstrumentation` 通过：跨块随机读取、锁定后旧读取失效、超过 4 GiB 的随机读取、本地与保险库 MP4 准备／播放／跳转、密文篡改拒绝、中断导入清理和备份恢复。数据源及加密实现沿用原实现。
- QA 包通过临时 Gradle init script 单独设置 applicationId 和 provider authority；生成媒体及截图保存在模拟器缓存，音频测试清理自身 WAV。首次音频验收触发已有文件列表 `PathObserver` 的 Root 服务初始化问题（`ExceptionInInitializerError`，`The main shell was already created`）；后续仅在隔离 QA 包设为 `RootStrategy.NEVER`，并预授予该包的文件／通知权限，避免首次启动对话框遮挡截图。未修改产品 Root 策略或把特权设为播放条件，也不声称本轮修复了该既有问题。
- 待真机验收：厂商系统手势／导航栏、触控锁定交互、超大字体、真实 SMB／SFTP／Mac 视频及长时间音频播放。本轮结果不代表这些场景已覆盖。

## 分享入口、短地址与二维码（2026-11-01）

- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain` 成功；158 项 JVM 测试，0 failure/error/skipped；lint 0 error、1176 warnings、3 hints。新增测试覆盖六位码有效期／全局限次、二维码编码解码、UTF-8 文件名长度与 MIME 后缀，以及未授权根地址表单和 POST 登录协议。`git diff --check` 通过。
- Android 14 模拟器、独立 `.recentqa` 包实际验证：停止状态的分享页点击剪贴板按钮即可创建空文件范围的文本分享；网页根页直接显示文本；显示的二维码 bitmap 经 ZXing 解码与当前 22 字符强令牌 URL 完全一致；根地址不含文件／文本内容，错误访问码拒绝，正确 POST 返回授权跳转；停止、替换和旧连接撤销继续通过。
- 实际系统分享入口收到文本／MediaStore 文件后先预览，确认前不启动服务器；确认后文件复制到会话缓存。修改本次生成的来源文件、复制分享链接后，原快照的 `Range: bytes=3-5` 仍返回 `345`；停止后缓存删除，生成的 Downloads 行按精确 URI 清理。入口拒绝外部提供的内部路径和同 UID 文件提供器 URI，不向外部调用者开放私有路径读取。
- 真实文件列表 UI 使用生成文件验证：长按复制，点击底栏局域网分享，经短地址访问码登录后下载字节一致；返回列表后复制操作栏仍在，原文件保留。
- Ego Lite 浏览器经 ADB 端口转发访问模拟器：实际填入六位码并提交 HTML POST 表单，跳转后直接显示指定文本快照；390px 视口检查通过。不是实际 Windows／其他手机 Wi-Fi 网络验收，也未将二维码解码测试称为手机相机实扫。
- 最初验收在应用自己的 FileProvider 测试 URI 上触发既有 Root 初始化错误：`ExceptionInInitializerError`，原因为 `The main shell was already created`；随后系统尝试后台重启时还触发既有 Bridge 前台服务启动限制。本轮服务现会把提供器 LinkageError 转为可见失败，避免工作线程直接导致进程崩溃；最终改用新建的系统 MediaStore URI 完整回归通过，不声称修复了所有既有 Root／后台启动问题。
- 外部文件导入限制为最多 100 个、合计 1 GiB，保留至少 32 MiB 可用空间；来源 URI 临时授权由私有快照隔离，正常停止／锁屏／替换时清理，异常结束遗留缓存在下次分享服务启动时清理。厂商省电、大规模导入、异常提供器长期阻塞与真实手机相机仍需进一步验证。

## 最近文件与临时浏览器分享（2026-10-31）

- 最终执行 `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain` 成功；147 项 JVM 测试，0 failure/error/skipped；lint 0 error、1151 warnings、3 hints。`git diff --check` 通过。保留既有警告，未把本次检查描述为全仓零警告。
- 使用 Android 14 模拟器、独立 `org.fileweave.android.recentqa` 测试包，生成 3 个共享存储索引文件；验证最近数据源从 Loading 到 Success、与 MediaStore DATE_ADDED／ID 降序一致、白名单的 `/sdcard` 别名、递归排除和规则持久化。实际首页确认最近文件在内部存储上方，默认进入，显示来源目录；目录范围设置可以从菜单和侧栏长按打开。
- 验收发现并修复默认 Root 策略下的自动扫描错误：无权限的旧索引路径触发 `RootFileService`，原始异常为 `ExceptionInInitializerError`，原因 `IllegalStateException: The main shell was already created`。最近扫描改为普通权限的本地元数据读取，跳过不可访问／失效条目，并在底层初始化失败时显示错误。修复后在 AUTOMATIC 策略下重新运行最近数据源测试通过；不将关闭 Root 设置作为产品运行前提。
- 目录选择器实际启动回归还发现初始路径尚未派发时，最近状态观察回调提前更新底栏造成空路径 NPE；改为路径就绪后更新，重新构建后已正常显示选择器和当前目录选择操作。
- 实际 UI 验证仅包含目录后只出现 2 个生成文件；切为排除相同目录后这 2 项消失、另一目录中的生成文件仍显示。「添加目录」可逐层选中目录并追加路径，取消不保存；选择器隐藏最近入口。最终本地元数据路径直接使用 LocalLinuxFileAttributeView，不经过 Rootable 属性封装；将隔离 QA 设置为 ALWAYS 后，最近数据源测试仍通过，证明自动扫描不依赖 Root 服务。
- 隔离 QA Instrumentation 使用生成的缓存文件，实际启动分享 Activity，检查目录列表、图片预览 HTML 和原始 PNG 字节、下载 Range／HEAD、错误 token 拒绝；实际点击剪贴板分享／清除，检查转义和网页快照；新分享清空快照并撤销旧 URL 与已经接受的连接，停止按钮关闭监听，全部通过。早期验收脚本的缓存路径别名断言和复用 Activity 等待问题已修正后重跑；不把脚本故障计为产品通过。
- Ego Lite 桌面浏览器通过 ADB 端口转发访问真实模拟器 HTTP 服务：目录入口、图片加载（naturalWidth 验证）、原图下载与生成文件字节一致、未分享剪贴板提示均通过；390px 移动视口没有横向溢出。模拟器熄屏后确认分享 socket 关闭。此传输设置只用于测试，不是产品依赖。
- 临时网页使用 HTTP 和随机访问链接，没有声明 HTTPS 加密。剪贴板仅提供手机页面获得焦点时用户点击分享的文本快照；浏览器刷新不会调用 Android 剪贴板。符合系统限制，参见 [Android 10 剪贴板访问说明](https://source.android.com/docs/whatsnew/android-10-release)。
- 尚未完成真实 Windows／其他手机经 Wi-Fi 的端到端回归、各厂商后台省电与锁屏策略、超大目录／文件长期传输；不能据模拟器与桌面 Chromium 检查声称所有设备兼容。最近文件依赖系统索引，不包含其他 App 私有目录或未索引文件；上限 500 在目录规则和可访问性过滤之后生效。

## Mac 紧凑原生界面（2026-10-25）

- 合并品牌区与页面切换区，设备状态和访达入口统一放到底栏。文件范围与搜索共用一行；子目录才展示路径栏。文件名、大小同行显示，复制操作放入右键菜单。文件与录音采用 SwiftUI 系统 List，录音列表、搜索建议及转录正文占满剩余内容区，不再手算列表高度。
- 面板宽度保持 440 点，高度上限由 540 缩至 500 点；菜单栏宿主与调试窗口共用尺寸。调试截图改为捕获挂载后的 NSView，覆盖原生 List；可用 `SNAPSHOT_PAGE=recordings` 检查录音页。
- 使用 macOS 26.5 SDK 的 Swift 调试构建、release 构建、`mac/build.sh` 打包及 ad-hoc 签名验证通过；`git diff --check` 通过。原有 actor 隔离及未使用变量等编译警告仍存在。
- 实际调试窗口检查文件与录音断连状态；另在临时源码副本中注入生成数据，检查长文件名、27 项文件列表、24 条录音及多选操作栏、20 段转录滚动区、深色搜索无结果状态。示例数据未写入产品源码或用户资料；这些检查不代表真实手机传输与转录端到端回归通过。
- `swift test --package-path mac --sdk /Library/Developer/CommandLineTools/SDKs/MacOSX26.5.sdk --build-system native` 被当前 CLT 环境阻断，原始错误：`FolderSourceTests.swift:1:8: error: no such module 'XCTest'`。未声称 Swift 单元测试通过。

## 共享、剪贴板与菜单栏体验（2026-10-24）

- Android `assembleDebug`、`testDebugUnitTest`、`lintDebug` 通过；137 项 JVM 测试无失败，覆盖共享路径边界、Range、撤销、鉴权及剪贴板请求等。Mac Python 23 项测试通过。
- Mac 使用 Command Line Tools 的 macOS 26.5 SDK 构建、打包及本地签名通过。默认 27 SDK 存在 SwiftUIMacros 编译问题；当前 CLT 环境缺少 XCTest，未声称 Swift XCTest 通过。独立 Swift 面板几何检查 27 个场景通过，实际 NSPopover 快照完整显示顶部和底部，窗口位于屏幕可见区域内；仍是点击外部关闭的 transient 面板。
- 本机 Swift → ffmpeg → Python ASR 流程生成 3 个句子。通过固定证书指纹的真实局域网 TLS 请求读取手机存储，生成文件的 `Range: bytes=3-5` 返回 206 和预期字节；转录上传后核对手机持久化 JSON，生成文件及转录夹具均清理。
- 真机非 instrumentation 检查通过：后台读取设置、真实文件及本地 HTTP Range、符号链接边界、共享撤销。Honor 系统曾以 `iaware pevent send broadcast` 拦截无 action 的开发检查广播，显式 action 后正常执行。此调试入口仅在 debug 构建中，受 DUMP 权限保护，不是产品运行前提。
- 修复真实崩溃：后台首次初始化 `FolderBridgeSettings` 时创建 LiveData，触发主线程约束；后台路径改为直接读取偏好，界面 LiveData 延迟初始化。最新安装后的进程退出记录没有新增应用崩溃；此前 instrumentation 启停及 APK 更新造成的退出不能算作应用闪退，也不再在用户操作期间运行会结束应用的 instrumentation。
- 因原安装签名不同，真机更新使用独立 `org.fileweave.android.qa` 测试包，没有卸载或清空原应用。已运行本机正式菜单栏 bundle，而非独立调试窗口。
- 剪贴板目前支持文本。Mac 复制自动发送至已连接手机；FileWeave 前台能合法读取的复制自动发送至 Mac。其他 Android 应用复制后，通过常驻通知、快捷磁贴或系统文字菜单发送；不承诺跨应用后台自动读取，也不依赖 Root、ADB 或悬浮窗授权。完整多厂商剪贴板交互与长时间后台耗电尚未验证。
- 全局文件共享指已授权的手机主存储，不包括其他应用私有目录；Mac 浏览及下载为只读。局域网链接停止、锁屏或新共享替换后失效。长时间休眠、超大文件及所有厂商后台限制仍需进一步验证。

## 搜索筛选、生物识别与空状态接手验证（2026-10-23）

接手另一聊天遗留的未完成增量，补齐资源与界面，并恢复构建。前文记录的保险库生物识别缺失资源构建错误已解决。

- 搜索新增筛选对话框：图片／视频／音频／Markdown／指定扩展名；包含子文件夹开关；修改／创建日期；今天、昨天、近 7 天、自选起止日。日期按设备时区自然日处理，首尾日均包含。取消与重载通过请求代次抑制旧结果回写。
- 真实创建时间仅由明确提供该数据的 SMB、WebDAV、压缩包源标识；本地 Linux 属性当前没有 birth time，筛选创建日期时不会用其修改时间替代，界面说明缺失时间的文件被排除。
- 保险库新增显式启用／停用的系统强生物识别入口。AndroidX BiometricPrompt CryptoObject 授权 Android Keystore AES-GCM 加解密本机凭据；gocryptfs 格式与备份不变，绑定在 noBackupFilesDir，主密码始终保留。系统只会提供满足强认证要求的指纹／人脸，不承诺所有设备的人脸传感器均可用于密码学操作。
- 凭据与 vault 配置摘要绑定；配置替换、密钥失效或解密失败要求重新启用。验证取消／后台切换清理待处理凭据；会话代次防止锁定前的迟到认证结果重新打开保险库。临时传感器不可用保留绑定。真实人脸／指纹、重新录入后密钥失效、旋转／切后台的系统提示行为仍需人工真机验收；JVM 不能验证这些硬件行为。
- 文件夹为空、搜索无结果、预览未选择文件共享 `file_empty_state.xml`，在所属内容区域居中展示图标、标题与次级提示。约定见 `docs/UI_GUIDELINES.md`。
- JVM 测试 120 项通过；新增测试覆盖类型／扩展名、日期首尾与 DST、缺失创建时间、加密封装往返／篡改／错误绑定／畸形输入，以及锁定后的迟到认证拒绝并清空凭据。
- 模拟器（2160×2344、480dpi）端到端通过：当前文件夹与递归搜索、Markdown／扩展名／日期过滤、未知创建时间排除、筛选对话框、搜索和预览空状态居中。修正仪器测试对文件列表与侧栏同名 RecyclerView 的定位及溢出按钮落点后，大屏 LIST 与 GRID 的长按扩选、回拖、普通滚动、边缘自动滚动、取消均通过。只使用生成的应用缓存文件，沿用临时隔离 Root/桥接自启的模拟器条件。

最终检查：`:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 全部通过（lint 0 error、1102 warnings、3 hints）；荣耀 VER-AN10 通过 `adb install -r` 安装最新调试包。未执行真实生物识别认证，未将该项人工验收记为通过。

## 折叠屏列表与预览（2026-10-23）

- 增加小列表（最小行高 56dp）、中等列表、缩略图网格、列表与预览四种视图；保留原有视图设置序号与按目录记忆机制。列表依据实际内容区域宽度与字体缩放分列，导航侧栏不计入文件区域。
- 「显示文件属性」控制两种列表的第二行；文件显示修改时间和大小，文件夹显示修改时间和类型，不将目录 inode 大小冒充目录总大小。
- 预览模式在至少 640dp（随大字体增加）可用宽度时展示右栏；左栏最少 320dp，较宽窗口采用约 40% / 60% 比例。图片取消固定 240dp 高度，以右栏宽度及可用高度的 68% 为上限保持比例；详情区域可滚动，操作按钮并排。
- 预览复用列表 Coil 管线，提供图片／视频缩略图、类型与 MIME、精确字节数和人类可读大小、完整修改时间、路径，以及提供者支持的链接目标、所有者、组和权限。没有内嵌音视频播放、文本正文或目录递归统计。更换条目、隐藏右栏及离开页面时清理请求与画面；未改动播放器、保险库或底层随机读取实现。
- 长按后滑动选择／取消连续范围，回拖恢复手势前的选择，边缘自动滚动，抬手或取消后停止；普通滑动保留滚动行为，单选文件选择器不启用批量滑选。
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 通过；112 个 JVM 测试通过（含新增 7 个布局／选择范围测试），lint 0 error、1065 warnings、3 hints。
- 荣耀 VER-AN10 展开态安装成功，UI 层级确认双列和文件夹属性行；预览实际显示图片及文件属性。小列表、实体折叠切换、大字体、锁屏后恢复及真实网络文件缩略图的完整人工回归仍待完成。
- 模拟器原生触摸回归：长按、范围扩选、回拖、普通滚动、边缘滚动与取消均通过。测试只使用生成的应用缓存文件；首次运行遇到 Root 配置下 `The main shell was already created` 和后台启动 `ForegroundServiceStartNotAllowedException`，模拟器限定 Root NEVER、关闭桥接自启动后通过；这两个既有启动问题未在本次修复。

补充验证：GRID（双列）与 COMPACT 的上述触摸回归同样通过。展开尺寸追加测试最初因测试通过 Activity 查找同名 RecyclerView 而命中导航侧栏，已将测试定位改为 contentLayout 内的文件列表。修正测试后的追加构建遇到共享工作区同期改动：`VaultActivity.kt:87 Unresolved reference fw_vault_biometric_enabled`、`:243 Unresolved reference fw_vault_biometric_prompt` 等资源缺失，因此此轮展开态仪器测试尚未重跑；不将它记为通过。此前视图与手势主体的完整构建和 JVM/lint 结果仍为上文所记。

## 0.4.0 真机回归（10-23，主体通过）

环境与网络：用户重组网（FlClash→Tailscale）后手机↔Mac 局域网互通恢复；bridge.py + `dns-sd -R` 替代菜单栏程序（Xcode 仍缺失）。

VER-AN10 / API 36 / MagicOS 上全部通过：

- **零接触配对闭环**（含 pm clear 后从零恢复共 4 轮）：NSD 发现→pair/start→auto-approve（标记文件）→poll→自动保存→自动连接，全程无人工点击。
- **常驻连接服务**：应用启动即自启（设置默认开），通知渠道 bridge_connection 注册并显示状态；手机↔Mac 8787 持久连接（netstat ESTABLISHED 验证）。
- **Mac→手机剪贴板自动同步端到端**：Mac 侧 POST /v1/clipboard 写入标记文本，≤5 秒后手机系统剪贴板持有该文本（搜索框长按粘贴菜单直接显示）。
- **抽屉重构**：Mac 剪贴板／Mac 共享文件两个入口就位，标准目录分类已移除；ClipboardActivity/BridgeActivity 的 FLAG_SECURE 均按设计拒绝截图。
- **文件夹共享 TLS 链路**：软件密钥（见下）+ Mac CA 签发 + TLS 1.3 握手成功；服务端证书 SHA-256 与 paired-devices.json folderFp 一致（等价产品端指纹固定）。
- **协议回归（curl 全量）**：/v1/status、/v1/tree（录音过滤、.Trash 元数据 movedAt/trashPath）、完整下载 MD5 与原件一致、Range 206 且字节一致、无鉴权/错 token 401、路径穿越 400、删除（复制＋移原件）与还原（内容逐字节一致）闭环。
- **FGS**：specialUse（types=0x40000000）isForeground=true 稳定运行，通知含文件计数实时更新。
- 快速设置磁贴代码在位但未加入磁贴面板（需用户在 QS 编辑中拖入），待后续人工确认。

真机抓到并修复的缺陷（均回归验证）：

1. `folder_bridge` 通知渠道未注册 → `CannotPostForegroundServiceNotificationException` 崩溃；已加入 AppInitializers 渠道创建。
2. AndroidKeyStore 硬件 RSA 密钥无法完成 TLS 服务端握手：本机 Conscrypt 经 `RSA/ECB/NoPadding` 原始 Cipher 上行调用签名，keystore 不支持原始 NoPadding（INCOMPATIBLE_PADDING/DIGEST），任何密钥规格都无法满足；已改为应用私有目录（文件级加密保护）中的软件 RSA 密钥对，CSR 签发流程不变，代码注释记录取舍。
3. 软件密钥首版从私钥推导公钥在部分 KeyFactory 上失败（"Local key missing"）；改为公钥 SPKI 独立文件保存。

未完成/待验证（2026-10-23 深夜交接，下一台机器继续）：

- 全部新功能已实现并集成：录音目录自动识别（真机已验）、互通历史页、截屏自动互通、录音实时推送（bridge.py /v1/notify + folder-events.jsonl）、WebDAV 服务器、SMB 评估暂缓、UI 改版（标题面包屑/根目录隐藏/关于页署名/「共享文件」改名）。**104 个 JVM 测试全绿、lint 0 error、Python 22 个测试通过。**
- 最终真机安装与验收被网络阻塞：手机已切热点网，与开发机仅剩 Tailscale 中继且断连。**最新 APK（36,677,261 字节，MD5 5d8db9a7b275c954b918d5147ad2328b）已完整送达手机 `/data/local/tmp/app-debug.apk`**；手机回到与开发机同网后执行 `adb shell pm install -r /data/local/tmp/app-debug.apk` 完成安装（此前已确认同签名可无人值守安装）。
- 待验收清单：WebDAV（开服务→PROPFIND/GET/PUT→Finder Cmd+K 挂载）；截屏互通（`adb shell input keyevent 120` 触发截屏→查 mac/.state/inbox/）；录音实时事件（共享目录出现新音频→查 mac/.state/folder-events.jsonl；勿对真实 Sounds 写入测试文件）；UI 四项（抽屉无「根目录」、标题面包屑、关于页署名、共享文件改名）截图确认。
- 手机设置仍为调试态（亮度 25、锁屏超时 1 小时）；验收完成后恢复原值：亮度 52+自动（`settings put system screen_brightness_mode 1; settings put system screen_brightness 52`）、锁屏 30 秒（`settings put system screen_off_timeout 30000`）。
- Mac 端菜单栏程序、转录、旧数据迁移仍等 Xcode；本机桥为 bridge.py + `dns-sd -R` 替代运行，新机器需自行拉起（见 docs/DEVELOPMENT.md）。

## 0.4.0 真机回归（第一晚，部分完成）

开发机环境在 10-22 晚间被整体重置（JDK 21、Android SDK/NDK、Go、Xcode、`~/.android` 旧调试密钥与 `mac/.state/` 配对数据全部丢失）。已重装 JDK 21（Homebrew openjdk@21）、SDK Platform 37.2 / Build-Tools 37.0.0 / NDK 30.0.16248370 / CMake 3.22.1 与 Go 1.27，`:app:assembleDebug` 通过并产出 0.4.0-dev（45）；Python 协议测试 18 个通过；Maven Central 对本机 IP 返回 403，经 `~/.gradle/init.gradle` 配置阿里云镜像解决（机器级配置，未改仓库）。

已完成的真机验证（VER-AN10 / API 36）：

- 0.4.0-dev（45）安装成功。旧 0.3.1-dev（44）因旧调试密钥随环境丢失而无法覆盖安装；经 run-as 核实应用数据仅有已失效的旧配对配置、无保险库数据后卸载重装，未触碰用户其他数据。
- 手机侧 NSD 发现与配对协议：`BridgeActivity` 打开后经 `_fileweave._tcp.` 发现 Mac（bridge.py 独立运行 + `dns-sd -R` 代替 Swift 菜单栏程序发布），TLS 固定证书连接、`pair/start`/`pair/poll` 与 Mac 侧决定文件批准全部按预期工作，当晚共 9 轮请求全部完成批准（含双方核对码生成）。
- `FLAG_SECURE` 安全行为在真机确认：连接页面截图与 uiautomator 转储均被拒绝，符合设计。

阻塞项与原始原因（均未绕过、未声称通过）：

- 首次信任需要手机端点击「已在 Mac 核对，完成配对」。荣耀 MagicOS 的多层防护使远程点击不可行：安全安装确认对话框忽略注入触控并阻止独立测试 APK（`INSTALL_FAILED_ABORTED`，`pm install -t` 同样被拒）；配对确认对话框属 FLAG_SECURE 窗口；crash 日志经 Honor 密钥加密不可读；手机上残留的投屏虚拟显示器反复触发 Activity 重建（`Performing restart`，锁定刷新率后仍复现），重建会取消进行中的配对。需要用户在场手动点击一次完成；未为绕过该人工确认修改任何产品代码。
- Xcode 已被卸载，Command Line Tools 不含 SwiftUI 宏插件（`SwiftUIMacros` plugin not found），`swift build`/`swift test` 无法编译菜单栏程序；Mac 面板发现、录音浏览、转录、AI 摘要与旧数据迁移启动项全部保留待 Xcode 恢复。
- 迁移测试准备就绪：`~/Library/Application Support/HonorRecorderBar` 已按旧项目（techfanseric/HonorRecorderBar）格式合成 library.json（含 pendingNew 旧账本）、transcripts 侧车、asr 与 bailian.env 占位（假密钥）；`FileWeave` 新目录确认不存在，首次启动即触发迁移。

辅助调试设施（机器级，未提交）：`mac/.state` 由 bridge.py 重建；自动批准观察器与 `dns-sd` 发布进程按 0.3.1 联测方式运行；Vision OCR（`/tmp/ocr.swift`）用于定位系统界面元素。仓库内新增 debug-only `FolderBridgePairingInstrumentation`（app/src/debug，模式同既有三个 instrumentation，随主 APK 分发），预期在安装确认可完成后替代人工点击，**该 instrumentation 尚未在真机运行过，未验证**。

## 0.4.0 文件夹共享与录音监视开发分支（脱机部分）

本阶段代码完成时全部为脱机验证（真机进展见上方「0.4.0 真机回归（第一晚）」），下列真机项仍保留：

- 手机端 TLS 服务器与 Android Keystore KeyManager 的真实握手、向 Mac 请求证书签发的闭环。
- NSD 双端发现（手机注册 `_fileweave-folder._tcp.`、Mac 浏览并按指纹匹配）。
- SAF 真实 provider 上的递归列表、`.Trash` 删除／还原闭环（复制＋校验＋删原件语义）。
- Android 15+ 的 specialUse 前台服务、快速设置磁贴、通知行为。
- 真实录音目录的扫描性能、转录与 AI 摘要、旧「荣耀录音监视器」数据迁移。

脱机已验证：

- Android `:app:assembleDebug`、`:app:testDebugUnitTest`、`:app:lintDebug`：通过；37 个 JVM 测试（folderbridge 29 个：HTTP 内核鉴权／路由／Range 全边界／头部与正文上限／路径安全／CSR 结构与真实签名、`+` 字面解码、movedAt 缺省），lint 0 error / 973 项结果。
- Mac `swift build`、`swift test`：30 个测试通过（协议解析含 movedAt=0、配对记录、Bonjour TXT、Content-Range、重连退避、水位线、登录项）；`build.sh` 产出 .app（Info.plist 含两类 Bonjour 服务、LSUIElement；ad-hoc 签名），未启动安装。
- Python `test_folder_key` / `test_pairing` / `test_bridge`：18 个通过，含 CA 签发链验证、指纹落账与替换、master token 拒绝、并发签发唯一性、超限请求。
- 两轮独立代码评审（Android 侧、Mac/Python 侧）无 P0 发现；P1（specialUse 服务类型、install.sh 孤儿进程、movedAt=0、迁移清单）与接受的 P2 已全部修复并回归测试。

## 0.3.1 双端操作简化开发分支

- Android `assembleDebug`、`testDebugUnitTest`、`lintDebug`：通过；8 个 JVM 测试，lint 0 error / 942 warning。新增测试覆盖连接退避、上限与恢复后重置。
- Mac Swift 菜单栏程序编译、启动通过，当前本机运行新版；Python 协议测试 12 个通过。
- VER-AN10 / API 36 真机：0.3.1-dev（44）经无线 ADB 覆盖安装成功，沿用原批准凭据。
- `automatic-bridge-smoke.py` 双端联测通过：Bonjour 发现、已有凭据连接、保存地址失效后找回真实地址、错误证书拒绝、共享列表自动增加／移除，以及 Mac 服务被终止后自动拉起、手机恢复并继续更新列表。测试只创建并清理随机生成的文件，不读取真实剪贴板或修改已有共享文件。
- 连接页面已验证不出现旧的手动配对、搜索、刷新和重置控件；Mac 菜单源代码已移除配对信息、服务启停与重置入口，保留首次批准弹窗。
- 联测曾发现主线程关闭 TLS 连接引发 `NetworkOnMainThreadException`，已将连接清理移到专用线程，修复后断线恢复联测通过。修复后另有一次列表更新等待超时，随后同版本连续两轮完整重跑通过；保留为后续长时间网络稳定性观察项。
- 首次人工信任、多个 Mac、Wi-Fi 切换和长时间前后台切换仍需更完整的人工回归。自动发现和列表更新当前仅在 Android 连接页面前台执行，Mac 应用退出后不维持服务。

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


## 2026-11-02 真机保数据覆盖更新（原生界面重构）

此节追加本轮实际部署事实，不撤销此前无线 ADB 断线／pending 的历史记录。fresh 发现并确认唯一 VER-AN10 / API36 后，稳定 `org.fileweave.android` 0.4.0-dev（45）以 `install -r --streaming --user 0` 成功覆盖；未卸载或清数据。安装后设备 base.apk 的 SHA-256 与稳定产物 `66beb9eb452749df278f6d166a2bdf8d4521760a41b11bdeeb1afa91d26f9598` 一致。原签名 hash 预检一致，系统替换安装再次校验完整签名。覆盖前后 AppId 与 CE/DE 数据目录 inode 均不变，冷启动 Status ok。

实际看到 Files 浏览与内外屏 Devices 排版、配对 Mac Connected，设置语言双语入口可达并取消返回；未手动传输、删除、转录真实数据。原无显式语言偏好，初始化采用产品默认英文；未主动改变已选偏好。Transfers 的 SECURE 窗口被系统拒绝截图，未绕过；一次横屏截图仅为过渡白帧，不能算本轮真机横屏目视通过。旋转／自动旋转／息屏超时已恢复原值，fontScale、density、安全锁未改。

脱敏安装及保数据证据位于 `build/outputs/redesign-acceptance/phone-live/`；包含真实内容的私有截图/XML不入库。独立复验由验收代理接续，本轮不扩展到真实数据发送、回收站、转录或长期后台稳定性。


### 2026-11-02 真机独立只读复验补充

独立代理再次核对已装APK hash、版本及AppId／CE／DE数据目录inode，全部与本轮覆盖记录一致。已在实际自然横屏 **2376×1060** 下取得fresh UIA／截图并亲自执行 Files → Devices（Connected，peer匹配本机Mac）→ Back 回 Recent files，无选中和文件动作。因此上节白色过渡帧保留为早期未通过尝试，核心真机横屏只读路径现已独立通过；不据此扩展所有折叠姿态／多窗口长期矩阵。

Mac保持原进程，只读根目录26项→既有QA目录4项→返回26项；没有发起真实数据发送、转录或删除。独立读回原显示与偏好：1060×2376／480dpi／无override、auto1／userRotation0／font1.0／screenOff3600000，最终留Files普通浏览。稳定脱敏汇总在 `build/outputs/redesign-acceptance/phone-live/`；个人文件名、主机名、凭据和私有UI原始材料未导出。


## 2026-11-02 Mac 工具栏与选中预览

- 连接与设备名合并在文件底栏，全部文件／刚刚共享移至更多；录音、收件箱统一图标尺寸并保留文字。手机文件和本地收件箱增加右侧选中预览，文件夹／未知格式／多选显示属性。
- 图片、PDF、音视频、常见文本／代码／配置／日志／字幕支持预览；Markdown 与 HTML 支持阅读／源码切换，补充 UTF-16／UTF-32／GB18030 与 HTML 声明编码。HTML 采用非持久 WebKit、禁用页面脚本、限制导航及资源。
- 临时下载每个可见宿主一个槽，单项上限 256 MiB，文本只读前 2 MiB，5 分钟过期；切换、关闭、锁定及观察到撤销共享时清理并停止播放器。崩溃遗留超过一小时后由启动和分钟清理回收，不删除收件箱原件。远端撤销依赖既有目录轮询，非即时推送；普通存储接口不提供保险库解密预览。
- `swift test --package-path mac`：82 项、1 项真实录音跳过、0 失败；Debug／Release 构建与 release bundle 签名验证通过。新增本机 TLS、取消、过期／切换、PNG／PDF、WAV 随机跳转／清空、Markdown、编码与 HTML 策略测试。
- 实际 CUA 窗口核对 PDF 内容、Markdown 标题／列表／表格／中文、HTML 中文与脚本未执行、源码切换、更多菜单与未知格式属性；图像／窄面板／深色渲染及证据细节见 `build/outputs/mac-selection-preview/VALIDATION.md`。PDF 的 NSView 位图缺少合成图层文字，不能当作真实内容渲染证据，已用原生画面与 AX 文本复核。
- 未进行真机预览与完整编码／大文件矩阵、真实系统锁定／解锁、覆盖安装或 Android 更新；不将合成本机证据当作真机通过。原有可选 Contacts helper 的 4097 诊断及旧测试 Swift 6 隔离警告已在完整报告记录。


## 2026-11-02 Mac 选中预览覆盖安装与重启验收

- 已按用户授权覆盖 `/Applications/FileWeave Mac.app`，保留旧 bundle 可回滚；最终安装二进制与 release 产物一致、签名验证通过，普通退出／重启后 App 与 bridge 正常运行，8787 监听正常。
- 原有 2,384 个数据文件均未丢失，7 个配对／凭据／证书文件哈希全部一致，30 份原始转录记录内容未变；资料库仅更新扫描时间，其余差异为运行日志、生成的连接配置及容量历史。
- 安装版实测发现收件箱行级 `onDrag` 吞单击，改为 List 原生 `itemProvider`；最终安装版单击、方向键切换、Shift 多选／汇总属性通过，保留拖拽 provider，未实测跨 App 投放。82 项测试含 1 跳过，Debug／Release 重新通过。
- 最终安装版 Markdown／HTML 阅读及源码切换、中文、JSON、PNG、PDF 实际页内容、未知文件和文件夹属性、AAC 播放与 MP4 画面检查通过；最后一次普通重启后重新验证 Markdown 与中文排版通过。UI 视频跳转位置因控件自动隐藏未确认，不将它当成已通过；随机跳转／清空由既有 native WAV 单测覆盖。
- 手机首轮安装后自动重新连接，真实手机文件夹与文本预览通过；手机 QA 音频一次预览失败，随后手机离线，最后 fresh Bonjour 未发现共享服务。手机音频及其它远端媒体未完成复验，不声称通过；未修改手机文件或重置配对。
- 本轮唯一命名的测试文件全部清理，收件箱回到原有 5 项，临时预览缓存文件数为 0。完整范围与回滚路径见 `build/outputs/mac-selection-preview/INSTALLED.md`。


## 2026-11-02 手机截图、剪贴板与最近文件补记

- 截图入口读取手机现有及新增截图，可见时每秒更新，原图复制、手机原件移入可恢复回收站与恢复；安装版以唯一 QA PNG 实测预览／删除／回收站预览／恢复，认证接口字节 hash 往返一致。范围限定三个常见截图目录，不是远程触发截图。
- 收件箱右侧未选中说明来源、临时预览及独立副本；再次点击取消选中，安装版单击／再次点击与 QA 宿主 Shift 多选／方向键／双击打开通过，跨 App 拖放未测。
- 手机前台焦点恢复补发文本剪贴板，Mac 仅保留最新已收到内容于内存；真机复制后回到 FileWeave 的认证链路通过。Android 后台限制仍适用；敏感内容及远端回写不自动发送。录音共享实际已开启且有 48 条，修正未连接／未共享／空目录的不同提示。
- 最近文件保留 MediaStore 系统回收标记过滤，再于分页前排除应用 .Trash 等目录及系统 .trashed- 名称，所有目录规则模式一致；组件匹配回归保留名称含 trash 的普通文件。
- Android assembleDebug／testDebugUnitTest／lintDebug 通过，200 JVM 测试零失败；Swift 86 项含 1 跳过、0 失败，Python 25 项通过，Debug／Release 与签名校验通过。双端保数据覆盖安装；手机 APK 与产物 hash 一致，Mac 已签名 bundle 与安装二进制一致。
- 原有 2,386 个 Mac 数据文件无缺失、7 个身份文件 hash 不变、30 份转录内容不变；手机 CE inode／共享偏好不变，bridge 加密配置重连有正常改写，以实际配对认证成功验证保留。所有本轮唯一 QA 文件及其自动同步副本已清理。详细证据／未测范围见 `build/outputs/mac-selection-preview/SCREENSHOTS.md`。


### 2026-11-02 荣耀相册回收目录遗漏修正

- 用户复查指出最近文件仍混入 recycle/bin 内容。真机当前屏幕截图确认路径为 `Pictures/.Gallery2/recycle/bins/0`，文件为相册私有 `.hndgp`；此前仅 .Trash 等名称覆盖不完整，之前的通过不包含该 OEM 路径。
- 补充按目录组件匹配 `.Gallery2/recycle`、`recycle/bin`、`recycle/bins`、`recyclebin`／`recycle_bin`；不按文件扩展名整体屏蔽，保留普通 bin、recycle-notes、recycle/binary 等正常文件路径。所有目录规则模式均先执行回收过滤。
- assembleDebug／testDebugUnitTest／lintDebug 通过，201 JVM 测试零失败、lint 零错误。当前 VER-AN10 `install -r --streaming` 保数据覆盖成功，安装 APK hash 与产物一致；冷启动两次 Status ok，CE inode、共享偏好和最近文件配置不变，加密 bridge 配置正常重连改写。
- 新增 debug-only 只读 RecentTrashAuditInstrumentation，不改设置、不创建／删除用户文件。实测 MediaStore 仍索引 13 条相册回收内容，实际 RecentFilesLiveData 首批 500 项中独立检查回收泄漏数为 0。重启后的真实屏幕再次截图确认靠前的 .hndgp 回收记录消失，正常截图／视频／录音仍存在。截图与只读系统索引含个人内容，仅留在私有临时目录，不入库；脱敏构建／安装／运行日志见 `build/outputs/recent-trash-fix/`。


### 2026-11-02 Mac 剪贴板排版修复

- 用户截图指出偏左空状态、重复标题与大段说明占用区域，修复工作区填充／居中约束、去掉内部重复标题、底部说明按需展开，长文本使用独立滚动区。
- 原生 Debug 面板中英文／深浅色／540与720宽／空状态与长文本四组位图已目视核对；安装版 AX 空状态及说明展开通过。独立窗口缓存位图合成层不完整、CUA 重激活会收起 popover，未将其误记为安装版完整像素验收。
- Swift 86项含1跳过、0失败，Debug／Release／签名验证通过；保数据覆盖安装后7个身份文件哈希不变，安装二进制与签名产物一致，普通启动后原证书pin和Bearer认证200。详见 `build/outputs/clipboard-layout/VALIDATION.md`。


## 2026-11-02 Mac CLI 安装与验收

- 已提供并安装原生 `fileweave`，随应用签名／打包，链接 `~/.local/bin/fileweave`。JSON schemaVersion1、能力及类型元数据、固定错误／退出码，中英消息不改 GUI 偏好。命令完整列表与限制见 [CLI.md](CLI.md)。
- 复用现有证书固定／Bearer 和配对发现，不新增服务；多历史配对显式指定设备。读取／预览不执行内容，下载限额／长度校验／同目录原子不覆盖发布，SIGINT／SIGTERM取消，临时文本复用预览清理。只读资料库使用既有 ISO8601 格式，不触发迁移／写入。
- Swift99项含1个旧可选实录音跳过、0失败；Python30项通过；最终签名安装版额外5个隔离进程检查通过。Debug／Release构建、签名、安装二进制与产物一致、CLI可从PATH调用。
- 真机 HTTPS 下当前设备状态、目录、已有QA文本读／下载／不覆盖、QA音频和截图原图下载通过；30份已有转录列出并实际读取一份，收件箱／最近收到的手机剪贴板只读查询通过。7个身份文件hash及30份转录记录内容不变。
- ADB此时无在线transport／mDNS为空，创建新手机fixture在首次push前失败，未写入；不影响CLI独立HTTPS访问。未用CLI移动用户录音／截图或改系统剪贴板，相关写操作由合成路由测试覆盖，不称为CLI真机写入通过。初版JSON transport与日期格式问题均已纠正并以真实端点复验。脱敏日志和详细限制见 `build/outputs/mac-cli/VALIDATION.md`。


## 2026-11-02 剪贴板／互通历史单条删除

- Android 互通历史每行新增可访问删除按钮，按持久ID删除一条；相同内容／时间、旧无ID记录、重复ID均独立识别。保留现有全部清空确认；英中说明明确本机预览与系统剪贴板／文件的边界。
- 提交磁盘原子写入后才更新缓存；写入失败保留记录并提示重试，追加与删除串行化，重启不会从旧缓存恢复已删条目。原100条上限、80字预览、noBackup和FLAG_SECURE保持。
- assembleDebug／207项JVM（含6个新增持久化／迁移／并发／失败用例）／lint零错误通过。真机保数据覆盖、最终APKhash一致、正常冷启动Status ok；真实历史文件hash、共享偏好和CE inode不变。
- 真机两阶段debug隔离测试：在独立历史目录渲染实际页面、点击一条删除、保留另一条；新进程读盘核对删除持久、SECURE保持，两阶段PASS。首轮可访问动作查找失败记录保留，修正测试上下文／动作查找后重跑；未删除用户记录、未清系统剪贴板、未绕过截图保护。生成记录清理。详细证据见 `build/outputs/history-single-delete/VALIDATION.md`。
