package me.zhanghai.android.files.bridge

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject

/** User-approved pairing binds the exact self-signed certificate, host and bearer credential. */
data class BridgeConfig(val host: String, val port: Int, val pin: String, val token: String) {
    fun json() = JSONObject().put("host", host).put("port", port).put("pin", pin).put("token", token)
    companion object {
        fun parse(uri: String): BridgeConfig {
            val parsed = Uri.parse(uri.trim())
            require(parsed.scheme == "fileweave" && parsed.host == "pair")
            return fromJson(JSONObject().put("host", parsed.getQueryParameter("host"))
                .put("port", parsed.getQueryParameter("port")?.toInt())
                .put("pin", parsed.getQueryParameter("pin")).put("token", parsed.getQueryParameter("token")))
        }
        fun fromJson(json: JSONObject): BridgeConfig {
            val value = BridgeConfig(json.getString("host"), json.getInt("port"), json.getString("pin"), json.getString("token"))
            require(value.host.isNotBlank() && value.host.length <= 253 && value.host.none { it.isWhitespace() || it in "/\\@?#" })
            require(value.port in 1..65535 && value.pin.matches(Regex("[a-f0-9]{64}")))
            require(value.token.matches(Regex("[A-Za-z0-9_-]{40,128}")))
            return value
        }
    }
}

object BridgeStore {
    private const val ALIAS = "fileweave-bridge-pairing"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(context: Context, config: BridgeConfig) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = config.json().toString().toByteArray()
        try {
            val data = JSONObject().put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .put("data", Base64.encodeToString(cipher.doFinal(bytes), Base64.NO_WRAP))
            val target = File(context.noBackupFilesDir, "bridge.json")
            val temp = File(context.noBackupFilesDir, "bridge.json.tmp")
            temp.writeText(data.toString())
            if (!temp.renameTo(target)) throw IOException("Unable to save pairing")
        } finally { bytes.fill(0) }
    }
    fun read(context: Context): BridgeConfig? = try {
        val file = File(context.noBackupFilesDir, "bridge.json")
        if (!file.exists()) null else {
            val json = JSONObject(file.readText())
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(json.getString("iv"), Base64.NO_WRAP)))
            }
            val clear = cipher.doFinal(Base64.decode(json.getString("data"), Base64.NO_WRAP))
            try { BridgeConfig.fromJson(JSONObject(String(clear, Charsets.UTF_8))) } finally { clear.fill(0) }
        }
    } catch (_: Exception) { null }
    fun forget(context: Context) { File(context.noBackupFilesDir, "bridge.json").delete() }
}

class BridgeClient(val config: BridgeConfig) {
    val http: OkHttpClient
    init {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw CertificateException("Client certificate unsupported") }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull() ?: throw CertificateException("Missing certificate")
                certificate.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                val expected = config.pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (!MessageDigest.isEqual(actual, expected)) throw CertificateException("Mac certificate changed; pair again")
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        http = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
            .hostnameVerifier { hostname, session ->
                hostname.equals(config.host, true) && try {
                    trust.checkServerTrusted(arrayOf(session.peerCertificates[0] as X509Certificate), "RSA"); true
                } catch (_: Exception) { false }
            }.followRedirects(false).followSslRedirects(false).proxy(java.net.Proxy.NO_PROXY)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).build()
    }
    fun url(endpoint: String, name: String? = null): HttpUrl = HttpUrl.Builder()
        .scheme("https").host(config.host).port(config.port).addPathSegments("v1/$endpoint")
        .apply { name?.let { addQueryParameter("name", it) } }.build()
    fun request(endpoint: String, name: String? = null) = Request.Builder().url(url(endpoint, name))
        .header("Authorization", "Bearer ${config.token}")
    fun json(endpoint: String): JSONObject = readJson(http.newCall(request(endpoint).build()).execute())
    fun pairingRequest(endpoint: String, payload: JSONObject): JSONObject {
        require(endpoint == "pair/start" || endpoint == "pair/poll" || endpoint == "pair/cancel")
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        // Enrollment endpoints carry only an ephemeral nonce, never the saved bearer credential.
        val call = http.newCall(Request.Builder().url(url(endpoint)).post(body).build())
        call.timeout().timeout(5, TimeUnit.SECONDS)
        return readJson(call.execute())
    }
    private fun readJson(response: okhttp3.Response): JSONObject = response.use {
        if (!response.isSuccessful) throw BridgeHttpException(response.code)
        val body = response.body ?: throw IOException("Empty response")
        if (body.contentLength() > 1024 * 1024) throw IOException("Response too large")
        body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (output.size() + n > 1024 * 1024) throw IOException("Response too large")
                output.write(buffer, 0, n)
            }
            JSONObject(String(output.toByteArray(), Charsets.UTF_8))
        }
    }
    fun sendClipboard(text: String) {
        require(text.toByteArray().size <= 256 * 1024 - 1024)
        val body = JSONObject().put("text", text).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        http.newCall(request("clipboard").post(body).build()).execute().use { if (!it.isSuccessful) throw IOException("Clipboard transfer failed") }
    }
    fun upload(name: String, input: java.io.InputStream) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(128 * 1024)
                var total = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Cancelled")
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > 8L * 1024 * 1024 * 1024) throw IOException("File exceeds 8 GiB limit")
                    sink.write(buffer, 0, n)
                }
            }
        }
        http.newCall(request("file", name).post(body).build()).execute().use { if (!it.isSuccessful) throw IOException("File transfer failed: ${it.code}") }
    }
    fun download(name: String, output: java.io.OutputStream) {
        http.newCall(request("file", name).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed")
            val body = response.body ?: throw IOException("Empty response")
            body.byteStream().use { it.copyTo(output) }
        }
    }
    fun close() {
        // TLS shutdown can write close_notify to the socket, including during recovery or
        // Activity destruction. Keep both cancellation and socket eviction off the UI thread.
        cleanupExecutor.execute {
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
        }
    }

    companion object {
        private val cleanupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "FileWeave-bridge-cleanup").apply { isDaemon = true }
        }
    }
}

class BridgeHttpException(val status: Int) : IOException("Mac returned $status")
