package me.zhanghai.android.files.webdavserver

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * WebDAV 的 socket 层：接受连接并交给 [WebDavKernel] 处理。
 *
 * 与 folderbridge 的 FolderBridgeServer 同款的有界线程模型：线程数即并发上限，排队的
 * 连接直接关闭——局域网客户端（Finder、Sardine）会自行重试，恶意并发压不垮前台服务。
 * 区别是这里跑明文 HTTP 且支持 keep-alive，因此读超时是“空闲超时”（30 秒无新请求即断）
 * 而不是单请求超时。
 */
class WebDavServer(
    private val port: Int,
    private val kernel: WebDavKernel
) {
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool: ThreadPoolExecutor = ThreadPoolExecutor(
        POOL_THREADS, POOL_THREADS, 30L, TimeUnit.SECONDS,
        SynchronousQueue(), { runnable ->
            Thread(runnable, "FileWeave-webdav-connection").apply { isDaemon = true }
        }
    )

    @Volatile
    private var running = false

    /** 实际监听端口；[port] 传 0 时由系统分配（JVM 集成测试用）。 */
    val localPort: Int
        get() = serverSocket?.localPort ?: -1

    /** 连续接受失败三次时回调（与 folderbridge 相同的持久失败语义）。 */
    var onError: ((Exception) -> Unit)? = null

    @Throws(IOException::class)
    fun start() {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(port))
        serverSocket = server
        running = true
        acceptThread = Thread({
            var consecutiveFailures = 0
            while (running) {
                val socket = try {
                    server.accept()
                } catch (error: Exception) {
                    if (running) {
                        // 退避，避免 EMFILE 之类的持久失败把接受线程打成 100% CPU。
                        if (++consecutiveFailures >= 3) {
                            onError?.invoke(error)
                            Thread.sleep(500)
                        }
                    }
                    continue
                }
                consecutiveFailures = 0
                try {
                    pool.execute { handle(socket) }
                } catch (_: RejectedExecutionException) {
                    try {
                        socket.close()
                    } catch (_: Exception) { }
                }
            }
        }, "FileWeave-webdav-accept").apply {
            isDaemon = true
            start()
        }
        pool.allowCoreThreadTimeOut(true)
    }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            // keep-alive 空闲上限：超时后由读端拿到 EOF/异常，连接自然关闭。
            socket.soTimeout = IDLE_TIMEOUT_MILLIS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            kernel.handleConnection(input, output)
        } catch (_: Exception) {
            // 畸形或截断的请求只能断开连接；协议核在能回答时已经回答过了。
        } finally {
            try {
                socket.close()
            } catch (_: Exception) { }
        }
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Exception) { }
        serverSocket = null
        // 正在传输的大文件流由中断触发关闭，避免传输线程长期滞留。
        pool.shutdownNow()
    }

    companion object {
        private const val IDLE_TIMEOUT_MILLIS = 30_000
        private const val POOL_THREADS = 8
    }
}
