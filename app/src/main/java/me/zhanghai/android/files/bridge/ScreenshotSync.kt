package me.zhanghai.android.files.bridge

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import java.io.File
import me.zhanghai.android.files.settings.Settings
import me.zhanghai.android.files.util.valueCompat

/**
 * 截屏自动互通：系统截屏落盘到 Pictures/Screenshots 后，把这张图推送到已配对
 * Mac 的收件箱（bridge.py 的 POST /v1/file），做到“截完屏 Mac 即可读取”。
 *
 * 为什么用 ContentObserver + MediaStore 而不是定时轮询：图片入库时 MediaStore 会
 * 主动通知，事件驱动零空转；截屏是低频事件，轮询只会在绝大多数周期里白白查库
 * 耗电。API 29+ 用 RELATIVE_PATH 圈定 Pictures/Screenshots；本项目 minSdk 23，
 * 低于 29 没有 RELATIVE_PATH，退回对 /storage/emulated/0/Pictures/Screenshots 的
 * FileObserver（CLOSE_WRITE / MOVED_TO 都意味着写入方已收尾，避免读到半截文件）。
 *
 * 隐私边界：只上传路径明确位于 Screenshots 目录内的图片（目录本身或其子目录），
 * 相册里的其他图片一概不碰，也不做任何内容分析。注册时不回扫历史，避免每次
 * 重连把旧截屏重传一遍；只处理注册之后的新通知。
 *
 * 传输策略：与 BridgeActivity 一致，每次上传用独立的短连接 BridgeClient，不复用
 * 常驻服务的长连接，后台传输的成败不干扰连接状态机。失败静默放弃——截屏随时
 * 可以重拍，不值得为它建重试队列，用户下次截屏自然就是一次新尝试。
 */
object ScreenshotSync {
    /** 只认落库时间在此窗口内的图片，防止迟到的重复通知翻出陈旧行。 */
    private const val RECENT_WINDOW_SECONDS = 30L

    /**
     * 进程内去重表容量：MediaStore 对同一次落盘可能连发多次通知（插入、扫描完成
     * 等），32 条足以覆盖一次通知风暴；超量整体清空——截屏低频，老 key 留着没有
     * 意义，这比逐条过期简单且不会无界增长。
     */
    private const val MAX_REMEMBERED = 32

    /** 服务侧期望：连接成功为 true，断开/停用为 false。仅在主线程访问。 */
    private var serviceConnected = false

    /** 观察器是否真正注册（开关关闭时为 false，即使服务仍连接）。仅在主线程访问。 */
    private var observing = false
    private var appContext: Context? = null
    private var handlerThread: HandlerThread? = null
    private var worker: Handler? = null
    private var contentObserver: ContentObserver? = null
    private var fileObserver: FileObserver? = null
    private var settingsObserved = false

    /**
     * 已上传/已放弃的文件 key -> 记录时间。仅在 worker 线程读写，stop 时在主线程
     * 清空，用锁串一下避免结构被并发写坏。
     */
    private val remembered = LinkedHashMap<String, Long>()

    /** 让截屏推送开关即时生效：关掉立刻注销观察者，重开且仍连接则立刻恢复。 */
    private val settingObserver = Observer<Boolean> { enabled ->
        if (enabled) {
            if (serviceConnected) startObserving()
        } else {
            stopObserving()
        }
    }

    /** 连接成功后由 BridgeConnectionService 调用；重复调用无副作用。 */
    fun start(context: Context) {
        val app = context.applicationContext
        ContextCompat.getMainExecutor(app).execute {
            serviceConnected = true
            // 单例随进程存活，持应用级 Context 即可，不持有任何组件引用。
            appContext = app
            if (!settingsObserved) {
                // 常驻单例随进程存活，观察器只注册一次，不需要反注册。
                Settings.SCREENSHOT_SYNC.observeForever(settingObserver)
                settingsObserved = true
            }
            if (Settings.SCREENSHOT_SYNC.valueCompat) startObserving()
        }
    }

    /** 断开或停用时由 BridgeConnectionService 调用；重复调用无副作用。 */
    fun stop(context: Context) {
        ContextCompat.getMainExecutor(context.applicationContext).execute {
            serviceConnected = false
            stopObserving()
        }
    }

    private fun startObserving() {
        if (observing) return
        val app = appContext ?: return
        observing = true
        val thread = HandlerThread("FileWeave-screenshot-sync").apply { start() }
        handlerThread = thread
        worker = Handler(thread.looper)
        if (Build.VERSION.SDK_INT >= 29) {
            val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val observer = object : ContentObserver(worker) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    // 本应用不写 MediaStore，selfChange 理论上不会出现；防御性忽略，
                    // 只处理系统截图管道或其他应用落库引起的通知。
                    if (!selfChange) scanRecent(app)
                }
            }
            // 对整个图片库树注册（notifyForDescendants），具体圈定在查询条件里完成。
            app.contentResolver.registerContentObserver(images, true, observer)
            contentObserver = observer
        } else {
            observeScreenshotsDirectory(app)
        }
    }

    /** API 23–28：没有 RELATIVE_PATH，直接观察 Screenshots 目录的文件事件。 */
    @Suppress("DEPRECATION")
    private fun observeScreenshotsDirectory(app: Context) {
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "Screenshots"
        )
        val observer = object : FileObserver(directory.absolutePath, FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO) {
            override fun onEvent(event: Int, path: String?) {
                // path 只是文件名；转回完整路径后丢给 worker 串行处理，避免占用
                // FileObserver 的公共分发线程做网络 IO。
                if (path != null) worker?.post { uploadLegacy(app, File(directory, path)) }
            }
        }
        observer.startWatching()
        fileObserver = observer
    }

    private fun stopObserving() {
        if (!observing) return
        observing = false
        contentObserver?.let { appContext?.contentResolver?.unregisterContentObserver(it) }
        contentObserver = null
        fileObserver?.stopWatching()
        fileObserver = null
        // quitSafely 让在途的上传跑完再退出线程；之后的 post 会被直接丢弃。
        handlerThread?.quitSafely()
        handlerThread = null
        worker = null
        synchronized(remembered) { remembered.clear() }
    }

    /** API 29+：落库通知到达后，查最近窗口内 Screenshots 目录的新图片。 */
    private fun scanRecent(app: Context) {
        val config = BridgeStore.read(app) ?: return
        val since = System.currentTimeMillis() / 1000 - RECENT_WINDOW_SECONDS
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.RELATIVE_PATH
        )
        // 隐私边界：不用 LIKE 'Pictures/Screenshots%'，那个写法会误伤同前缀目录
        // （如手工建的 ScreenshotsBackup）；只认目录本身或其一级及更深的子目录。
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?" +
            " AND (${MediaStore.Images.Media.RELATIVE_PATH} = ?" +
            " OR ${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?)"
        val arguments = arrayOf(since.toString(), "Pictures/Screenshots/", "Pictures/Screenshots/%")
        try {
            app.contentResolver.query(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                projection, selection, arguments,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1) ?: continue
                    val dateAdded = cursor.getLong(2)
                    uploadFromMediaStore(app, config, id, name, dateAdded)
                }
            }
        } catch (_: Exception) {
            // 查询失败静默放弃：这是尽力而为的辅助通道，不向用户报错。
        }
    }

    private fun uploadFromMediaStore(app: Context, config: BridgeConfig, id: Long, name: String, dateAdded: Long) {
        // 去重 key 用行 id + dateAdded：同秒多张、同名覆盖都能区分；插入/扫描的
        // 重复通知在这里被挡掉。先记录后上传：失败也不重试同一文件（静默放弃）。
        if (!remember("media:$id:$dateAdded")) return
        val bridge = BridgeClient(config)
        try {
            val uri = ContentUris.withAppendedId(
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id)
            app.contentResolver.openInputStream(uri)?.use { input ->
                // 文件名保持原截屏名（DISPLAY_NAME），Mac 端同名不会互相覆盖。
                bridge.upload(name, input)
            }
        } catch (_: Exception) {
            // 上传失败（Mac 临时不可达等）静默放弃，不做重试队列。
        } finally {
            bridge.close()
        }
    }

    /** API 23–28 分支：直接读 Screenshots 目录里的新文件。 */
    @Suppress("DEPRECATION")
    private fun uploadLegacy(app: Context, file: File) {
        // 只推常见截屏图片格式，目录里可能混有第三方应用写入的其他文件。
        if (!file.isFile || file.extension.lowercase() !in setOf("png", "jpg", "jpeg", "webp")) return
        if (!remember("file:${file.absolutePath}")) return
        val config = BridgeStore.read(app) ?: return
        val bridge = BridgeClient(config)
        try {
            file.inputStream().use { input -> bridge.upload(file.name, input) }
        } catch (_: Exception) {
            // 同上：失败即弃，下次截屏再试。
        } finally {
            bridge.close()
        }
    }

    private fun remember(key: String): Boolean {
        synchronized(remembered) {
            if (remembered.containsKey(key)) return false
            if (remembered.size >= MAX_REMEMBERED) remembered.clear()
            remembered[key] = System.currentTimeMillis()
            return true
        }
    }
}
