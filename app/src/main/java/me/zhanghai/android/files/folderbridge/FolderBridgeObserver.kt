package me.zhanghai.android.files.folderbridge

import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import me.zhanghai.android.files.bridge.BridgeClient
import me.zhanghai.android.files.bridge.BridgeStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 录音实时性观察者：共享目录里出现新录音（通话录音、微信通话、系统录音机落盘）后，
 * 秒级 POST /v1/notify 告知 Mac“有新文件”，Mac 菜单栏程序读 .state/folder-events.jsonl
 * 即刻刷新，不必等自己的下一轮轮询。
 *
 * 通知只是“去拉一次列表”的提示信号，不是事件流水：真正的文件清单始终由 Mac 调
 * /v1/tree 获得。因此观察者的正确性要求是“宁可漏报、绝不误阻塞”——任何失败都静默，
 * 漏报由 Mac 自身的轮询兜底。
 *
 * 两种后端两条监听路：
 * * File 后端（自动识别目录）：[FileObserver] 是 inotify 的直接封装，只监听单个目录；
 *   录音实际存在嵌套子目录里（如 Recordings/Call），必须为共享根与每个子目录各注册
 *   一个 observer。目录增删（录音应用按日期建目录等）触发延迟合并的重扫，补注册新
 *   子目录、注销消失的目录。
 * * SAF 后端：授权树没有文件系统事件可挂，改盯 MediaStore 音频表的 ContentObserver，
 *   用 RELATIVE_PATH 把范围圈回共享目录。代价与取舍见 [registerContentObserver]。
 *
 * 线程模型：所有状态（待发送集合、去重记录、observer 列表）都串行跑在自有
 * HandlerThread 上——FileObserver 的事件来自它的公共分发线程、ContentObserver 的
 * 回调来自构造时注入的 handler，两者都只往我们的线程转发，不在此做任何 IO。网络
 * 发送在另一个独立的单线程 executor 上，用完即弃的短连接 BridgeClient。二者都不碰
 * FolderBridgeServer 的任何线程，观察者再慢、再堵也不会影响服务本身。
 */
class FolderBridgeObserver(context: Context, private val backend: FolderBridgeBackend) {

    private val appContext: Context = context.applicationContext

    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var executor: ExecutorService? = null

    /** File 后端的共享根目录；SAF 后端为 null。仅在观察者线程读写。 */
    private var fileRoot: File? = null

    /** 已注册的目录监听（共享根 + 递归子目录）。仅在观察者线程读写。 */
    private val fileObservers = mutableListOf<FileObserver>()

    private var contentObserver: ContentObserver? = null

    /**
     * 去重：进程内只记最后一条已通知的文件名与时刻（elapsedRealtime）。同名文件在
     * 压制窗口内的重复命中（续写收尾、媒体扫描器重复通知）不再重发；不同文件名不受
     * 影响。超出窗口后的再次写入至多多提醒一次，Mac 拉列表后自然收敛，无害。
     */
    private var lastName: String? = null
    private var lastNotifiedAt = 0L

    /** 已进入 debounce 等待的名字集合。仅在观察者线程读写。 */
    private val pending = mutableSetOf<String>()

    fun start() {
        if (handlerThread != null) {
            return
        }
        val thread = HandlerThread("FileWeave-folder-observer")
        thread.start()
        handlerThread = thread
        val handler = Handler(thread.looper)
        this.handler = handler
        executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "FileWeave-folder-observer-send").apply { isDaemon = true }
        }
        fileRoot = (backend as? FolderBridgeFileBackend)?.rootDirectory
        handler.post {
            val root = fileRoot
            if (root != null) {
                registerFileObservers(root)
            } else {
                registerContentObserver()
            }
        }
    }

    fun stop() {
        val handler = handler ?: return
        // 摘监听的动作发到观察者线程上执行，与事件回调串行化，避免并发改列表；
        // quitSafely 会让这条已就绪的消息执行完，仍在延迟等待的 debounce 任务则被丢弃。
        handler.post {
            for (observer in fileObservers) {
                observer.stopWatching()
            }
            fileObservers.clear()
            contentObserver?.let { observer ->
                try {
                    appContext.contentResolver.unregisterContentObserver(observer)
                } catch (_: Exception) { }
            }
            contentObserver = null
            pending.clear()
        }
        handlerThread?.quitSafely()
        handlerThread = null
        this.handler = null
        executor?.shutdownNow()
        executor = null
    }

    // ---------------------------------------------------------------- File 后端

    /**
     * 递归注册共享根与全部子目录。FileObserver 的 mask 里，CLOSE_WRITE（写完关闭，录音
     * 落盘的典型信号）与 MOVED_TO（录音应用先写临时名再改名的常见套路）用于命中文件；
     * CREATE/DELETE/MOVED_FROM 只用来发现目录结构变化（新子目录），触发延迟重扫。
     */
    @Suppress("DEPRECATION")
    private fun registerFileObservers(root: File) {
        for (directory in collectDirectories(root)) {
            try {
                // String 路径构造器在 API 29 被标记废弃，但 File 版本要 API 29，
                // minSdk 23 只能用前者（与 bridge 包 ScreenshotSync 的选择一致）。
                val observer = object : FileObserver(directory.path, WATCH_MASK) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path == null) {
                            return
                        }
                        val name = path.substringAfterLast('/')
                        if (event and HIT_MASK != 0 && FolderBridgeDefaults.isAudioName(name)) {
                            // 只往自己的线程转发：绝不占用 FileObserver 的公共分发
                            // 线程做任何处理。
                            handler?.post { onAudioSeen(name) }
                        }
                        if (event and STRUCTURE_MASK != 0) {
                            handler?.post { scheduleRescan() }
                        }
                    }
                }
                observer.startWatching()
                fileObservers.add(observer)
            } catch (_: Exception) {
                // 单个目录注册失败（inotify watch 达到内核上限等）不影响其余目录，
                // 该子目录退回 Mac 轮询兜底。
            }
        }
    }

    /**
     * 有界广度优先收集要监听的目录。跳过点开头目录：顶层 .Trash 是我们自己的回收站，
     * 进出回收站本就是 Mac 侧发起的 trash/restore，它不需要再收到“有新文件”的回声；
     * 用 canonicalFile 记录已访问目录，防符号链接造成循环。64 个是防御性上界——录音
     * 目录通常只有两三层、个位数子目录，异常巨大的目录树不该拖慢启动，超出的子目录
     * 不注册实时监听，照旧靠 Mac 轮询。
     */
    private fun collectDirectories(root: File): List<File> {
        val out = mutableListOf<File>()
        val visited = mutableSetOf(root)
        val queue = ArrayDeque<File>()
        queue.add(root)
        while (queue.isNotEmpty() && out.size < MAX_WATCHED_DIRECTORIES) {
            val directory = queue.removeFirst()
            out.add(directory)
            val children = directory.listFiles() ?: continue
            for (child in children) {
                if (!child.isDirectory || child.name.startsWith(".")) {
                    continue
                }
                val canonical = try {
                    child.canonicalFile
                } catch (_: IOException) {
                    child.absoluteFile
                }
                if (visited.add(canonical)) {
                    queue.add(child)
                }
            }
        }
        return out
    }

    /**
     * 目录结构变化后的重扫：全部摘掉、按当前目录树重新注册。中间会有短暂的监听空窗，
     * 空窗内漏掉的事件由 Mac 轮询兜底，换来实现上的简单（无需做增量 diff）。
     */
    private val rescanRunnable = Runnable {
        val root = fileRoot ?: return@Runnable
        for (observer in fileObservers) {
            observer.stopWatching()
        }
        fileObservers.clear()
        registerFileObservers(root)
    }

    /** 多个创建/删除事件合并成一次重扫，避免每个事件都遍历一遍目录树。 */
    private fun scheduleRescan() {
        val handler = handler ?: return
        handler.removeCallbacks(rescanRunnable)
        handler.postDelayed(rescanRunnable, RESCAN_DELAY_MILLIS)
    }

    // ---------------------------------------------------------------- SAF 后端

    /**
     * SAF 树没有文件系统事件可订阅（DocumentsProvider 不保证通知），只能退而求其次盯
     * MediaStore 音频表的 ContentObserver，用 RELATIVE_PATH 前缀把变化圈回共享目录。
     * 权衡：
     * * 事件依赖系统媒体扫描器把新文件扫进 MediaStore——录音应用写完通常会主动触发
     *   扫描，秒级可见，但规范上无硬保证；扫描慢时这条实时通道就慢，Mac 轮询兜底。
     * * 查询 MediaStore 需要相应可见性（本项目以所有文件访问授权为主路径）；查不到
     *   行就查不到，静默退化为轮询，绝不误报。
     * * API<29 没有 RELATIVE_PATH，无法圈定范围，全表监听会把设备上任何音频变动都当
     *   成事件——宁可不实时，也不制造噪音，直接不注册（注释即取舍）。
     */
    private fun registerContentObserver() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        // ContentObserver 的回调走构造时注入的 handler，即我们自己的观察者线程。
        val handler = this.handler ?: return
        val tree = (backend as? FolderBridgeSafBackend)?.treeUri
        if (tree == null) {
            return
        }
        val documentId = try {
            DocumentsContract.getTreeDocumentId(tree)
        } catch (_: Exception) {
            null
        } ?: return
        val separator = documentId.indexOf(':')
        if (separator <= 0) {
            return
        }
        // documentId 形如 "primary:Sounds/Recordings" 或 SD 卡的 "XXXX-XXXX:Sounds"；
        // 冒号前是对应的 MediaStore 卷名，冒号后换算成 RELATIVE_PATH 前缀。
        val volume = documentId.substring(0, separator)
        val basePath = documentId.substring(separator + 1).trim('/')
        if (basePath.isEmpty()) {
            // 直接共享整个存储根：任何音频变动都会命中，误报面太大，放弃实时。
            return
        }
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                if (!selfChange) {
                    queryMediaStore(volume, basePath)
                }
            }
        }
        try {
            // notifyForDescendants：音频表整棵树都通知，圈定范围交给查询条件。
            appContext.contentResolver.registerContentObserver(
                MediaStore.Audio.Media.getContentUri(volume), true, observer
            )
        } catch (_: Exception) {
            // 卷名不被接受（陌生的树来源）等：注册失败即无实时，轮询兜底。
            return
        }
        contentObserver = observer
    }

    /**
     * 变化通知只给了“音频表变了”，不含具体行；用 DATE_ADDED 回看窗口圈住新行。
     * RELATIVE_PATH 的圈定不用 LIKE 'Sounds/Recordings%'——那会误伤同前缀目录（如手工
     * 建的 RecordingsBackup），而是“等于目录本身或其一级及更深的子目录”两段式，与
     * bridge 包 ScreenshotSync 的做法一致。
     */
    private fun queryMediaStore(volume: String, basePath: String) {
        val since = System.currentTimeMillis() / 1000 - LOOKBACK_SECONDS
        val names = mutableListOf<String>()
        try {
            appContext.contentResolver.query(
                MediaStore.Audio.Media.getContentUri(volume),
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DATE_ADDED} >= ?" +
                    " AND (${MediaStore.MediaColumns.RELATIVE_PATH} = ?" +
                    " OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)",
                arrayOf(since.toString(), "$basePath/", "$basePath/%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val nameColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext() && names.size < MAX_MEDIA_ROWS) {
                    cursor.getString(nameColumn)?.let { names.add(it) }
                }
            }
        } catch (_: Exception) {
            // 查询失败（无可见性等）说明此路不通：静默放弃，不向用户报错。
            return
        }
        for (name in names) {
            if (FolderBridgeDefaults.isAudioName(name)) {
                onAudioSeen(name)
            }
        }
    }

    // ---------------------------------------------------------------- 通知

    /**
     * 音频命中后的公共入口（观察者线程）。先做同名去重，再进 debounce：
     * 1.5 秒的取值是体感与归并的折中——微信通话、录音应用常见“先写临时文件/分段续写/
     * 写完改名”的连续动作，1.5 秒足够把它们归并成一次提醒，又不至于让“录制结束→Mac
     * 提示”超过秒级体感。窗口内同名续写不重置计时（首个事件起 1.5 秒后必发），避免
     * 长通话期间被持续续写无限推迟。
     */
    private fun onAudioSeen(name: String) {
        val now = SystemClock.elapsedRealtime()
        if (name == lastName && now - lastNotifiedAt < REPEAT_SUPPRESS_MILLIS) {
            return
        }
        if (!pending.add(name)) {
            return
        }
        val handler = handler ?: return
        handler.postDelayed({
            pending.remove(name)
            lastName = name
            lastNotifiedAt = SystemClock.elapsedRealtime()
            executor?.execute { sendNotify(name) }
        }, DEBOUNCE_MILLIS)
    }

    /**
     * 发送线程上执行：每次通知读一次最新配对（用户可能重新配对）、用一条独立的短连接
     * BridgeClient——不复用也不惊动任何常驻连接，事件稀少（一次录音一条），不值得为它
     * 保活。任何失败都静默：这是尽力而为的辅助通道，丢了 Mac 下轮轮询自然补上，下个
     * 文件就是一次新的尝试。
     */
    private fun sendNotify(name: String) {
        val config = try {
            BridgeStore.read(appContext)
        } catch (_: Exception) {
            null
        } ?: return
        val client = BridgeClient(config)
        try {
            val body = JSONObject()
                .put("type", "folder-update")
                .put("name", name)
                .toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val call = client.http.newCall(client.request("notify").post(body).build())
            // 收紧整体超时：一个不可达的 Mac 不值得占住发送线程到默认超时。
            call.timeout().timeout(NOTIFY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Mac returned ${response.code}")
                }
            }
        } catch (_: Exception) {
        } finally {
            client.close()
        }
    }

    companion object {
        private const val WATCH_MASK = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO or
            FileObserver.CREATE or FileObserver.DELETE or FileObserver.MOVED_FROM
        private const val HIT_MASK = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        private const val STRUCTURE_MASK = FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_FROM

        /** 命中后到发送的 debounce 延迟；归并续写/改名等连续动作。 */
        private const val DEBOUNCE_MILLIS = 1500L
        /** 同名事件的去重压制窗口；窗口内同名文件不重复提醒。 */
        private const val REPEAT_SUPPRESS_MILLIS = 60_000L
        /** 目录结构变化合并成一次重扫的延迟。 */
        private const val RESCAN_DELAY_MILLIS = 1000L
        /** 递归注册的目录数上界；超出部分不实时监听，靠 Mac 轮询兜底。 */
        private const val MAX_WATCHED_DIRECTORIES = 64
        /** MediaStore 回看窗口（秒）；ContentObserver 不给出具体行，按落库时间圈新行。 */
        private const val LOOKBACK_SECONDS = 120L
        /** 一次 MediaStore 查询最多采用的行数。 */
        private const val MAX_MEDIA_ROWS = 8
        /** 单条通知的整体超时（秒）。 */
        private const val NOTIFY_TIMEOUT_SECONDS = 10L
    }
}
