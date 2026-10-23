package me.zhanghai.android.files.folderbridge

import android.content.Context
import android.os.Build
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import me.zhanghai.android.files.R
import me.zhanghai.android.files.bridge.BridgeClient
import me.zhanghai.android.files.bridge.BridgeConfig
import me.zhanghai.android.files.bridge.BridgeStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Minimal DER writer, enough for one PKCS#10 certification request; unit-tested on the JVM. */
object FolderBridgeDer {
    fun sequence(vararg parts: ByteArray): ByteArray = tagged(0x30, *parts)

    fun set(vararg parts: ByteArray): ByteArray = tagged(0x31, *parts)

    /** [0] IMPLICIT SET OF with no elements: an empty context tag. */
    fun emptyContext0(): ByteArray = byteArrayOf(0xA0.toByte(), 0x00)

    fun integer(value: Int): ByteArray {
        require(value in 0..127) { "Only small non-negative integers are needed" }
        return tagged(0x02, byteArrayOf(value.toByte()))
    }

    fun oid(vararg arcs: Int): ByteArray {
        require(arcs.size >= 2)
        val content = mutableListOf(arcs[0] * 40 + arcs[1])
        for (arc in arcs.drop(2)) {
            require(arc >= 0)
            if (arc < 0x80) {
                content.add(arc)
            } else {
                val bytes = mutableListOf<Int>()
                var value = arc
                bytes.add(0, value and 0x7F)
                value = value ushr 7
                while (value > 0) {
                    bytes.add(0, (value and 0x7F) or 0x80)
                    value = value ushr 7
                }
                content.addAll(bytes)
            }
        }
        return tagged(0x06, content.map { it.toByte() }.toByteArray())
    }

    fun utf8String(value: String): ByteArray = tagged(0x0C, value.toByteArray(Charsets.UTF_8))

    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)

    fun bitString(data: ByteArray): ByteArray =
        tagged(0x03, byteArrayOf(0x00) + data) // No unused bits.

    private fun tagged(tag: Int, vararg content: ByteArray): ByteArray {
        val body = ByteArray(content.sumOf { it.size })
        var offset = 0
        for (part in content) {
            part.copyInto(body, offset)
            offset += part.size
        }
        val length = when {
            body.size < 0x80 -> byteArrayOf(body.size.toByte())
            body.size < 0x100 -> byteArrayOf(0x81.toByte(), body.size.toByte())
            body.size < 0x10000 -> byteArrayOf(
                0x82.toByte(), (body.size ushr 8).toByte(), body.size.toByte()
            )
            else -> throw IllegalArgumentException("DER object too large")
        }
        return byteArrayOf(tag.toByte()) + length + body
    }
}

/** Builds a PKCS#10 CSR: version v1, a CN-only subject, the key's SPKI, no attributes. */
object FolderBridgeCsr {
    private val CN_OID = FolderBridgeDer.oid(2, 5, 4, 3) // 2.5.4.3 commonName
    private val SHA256_WITH_RSA_OID = FolderBridgeDer.oid(1, 2, 840, 113549, 1, 1, 11)
    private val PEM_HEADER = "-----BEGIN CERTIFICATE REQUEST-----"
    private val PEM_FOOTER = "-----END CERTIFICATE REQUEST-----"

    /**
     * @param sign signs the DER-encoded certificationRequestInfo (SHA256withRSA over it),
     *   injected so both local key pairs and JVM test key pairs can drive it.
     */
    fun buildDer(commonName: String, subjectPublicKeyInfo: ByteArray,
                 sign: (ByteArray) -> ByteArray): ByteArray {
        val subject = FolderBridgeDer.sequence(
            FolderBridgeDer.set(FolderBridgeDer.sequence(CN_OID, FolderBridgeDer.utf8String(commonName)))
        )
        val info = FolderBridgeDer.sequence(
            FolderBridgeDer.integer(0),
            subject,
            subjectPublicKeyInfo,
            FolderBridgeDer.emptyContext0()
        )
        val signature = sign(info)
        return FolderBridgeDer.sequence(
            info,
            FolderBridgeDer.sequence(SHA256_WITH_RSA_OID, FolderBridgeDer.nullValue()),
            FolderBridgeDer.bitString(signature)
        )
    }

    fun buildPem(commonName: String, subjectPublicKeyInfo: ByteArray,
                 sign: (ByteArray) -> ByteArray): String =
        pemOf(buildDer(commonName, subjectPublicKeyInfo, sign), PEM_HEADER, PEM_FOOTER)

    fun pemOf(der: ByteArray, header: String, footer: String): String {
        val base64 = Base64.encodeToString(der, Base64.NO_WRAP)
        val builder = StringBuilder(header).append('\n')
        for (line in base64.chunked(64)) {
            builder.append(line).append('\n')
        }
        return builder.append(footer).append('\n').toString()
    }
}

/**
 * Folder-share TLS identity: an RSA key pair generated in-app, kept in the app's
 * noBackupFilesDir (protected by file-based encryption), with a certificate issued
 * by the paired Mac's CA over the existing pairing channel.
 *
 * Deliberately NOT an AndroidKeyStore key: this device's Conscrypt drives TLS
 * server signatures through a raw `RSA/ECB/NoPadding` cipher upcall, which the
 * keystore rejects (INCOMPATIBLE_PADDING_MODE) regardless of the key's declared
 * paddings or digests, so a hardware key can never complete the handshake here.
 */
object FolderBridgeKeys {
    private const val KEY_FILE = "folder-key.pem"
    private const val PUBLIC_FILE = "folder-public.pem"
    private const val CERT_FILE = "folder-cert.pem"
    private const val CN_PREFIX = "FileWeave Folder"
    private const val KEY_SIZE = 2048

    @Throws(Exception::class)
    fun ensureKeyAndCsr(context: Context): String {
        val pair = loadKey(context) ?: generateKey(context)
        val commonName = "$CN_PREFIX ${Build.MODEL}"
        val sign: (ByteArray) -> ByteArray = { info ->
            Signature.getInstance("SHA256withRSA").apply { initSign(pair.private) }
                .apply { update(info) }
                .sign()
        }
        return FolderBridgeCsr.buildPem(commonName, pair.public.encoded, sign)
    }

    /** A certificate issued by the Mac is only meaningful together with its matching key. */
    fun hasCert(context: Context): Boolean =
        certFile(context).exists() && keyFile(context).exists() && publicFile(context).exists()

    @Throws(Exception::class)
    fun storeCert(context: Context, pem: String) {
        val factory = CertificateFactory.getInstance("X.509")
        val certificates = factory.generateCertificates(ByteArrayInputStream(pem.toByteArray(
            Charsets.UTF_8)))
        val certificate = certificates.singleOrNull() as? X509Certificate
            ?: throw IOException("Expected exactly one certificate")
        certificate.checkValidity()
        val pair = loadKey(context) ?: throw IOException("Local key missing; cannot attach certificate")
        if (!pair.public.encoded.contentEquals(certificate.publicKey.encoded)) {
            throw IOException("Issued certificate does not match the local key")
        }
        writePrivate(certFile(context), pem)
    }

    fun forgetAll(context: Context) {
        keyFile(context).delete()
        publicFile(context).delete()
        certFile(context).delete()
    }

    /** SHA-256 hex of the issued certificate's DER encoding, advertised over NSD. */
    fun fingerprint(context: Context): String? {
        if (!hasCert(context)) {
            return null
        }
        return try {
            val factory = CertificateFactory.getInstance("X.509")
            val certificate = factory.generateCertificate(
                ByteArrayInputStream(certFile(context).readBytes())
            ) as X509Certificate
            sha256Hex(certificate.encoded)
        } catch (_: Exception) {
            null
        }
    }

    @Throws(Exception::class)
    fun serverSslContext(context: Context): SSLContext {
        val pair = loadKey(context) ?: throw IOException("Folder key missing")
        val factory = CertificateFactory.getInstance("X.509")
        val certificate = factory.generateCertificate(
            ByteArrayInputStream(certFile(context).readBytes())
        ) as X509Certificate
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null) }
        store.setKeyEntry("folder", pair.private, null, arrayOf(certificate))
        val manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        manager.init(store, null)
        return SSLContext.getInstance("TLS").apply {
            init(manager.keyManagers, null, SecureRandom())
        }
    }

    /**
     * Ensures a Mac-issued certificate exists, enrolling over the pinned pairing channel when
     * needed. Returns the certificate fingerprint.
     */
    @Throws(Exception::class)
    fun ensureCertificate(context: Context): String {
        if (hasCert(context)) {
            fingerprint(context)?.let { return it }
        }
        forgetAll(context)
        val config = BridgeStore.read(context)
            ?: throw IOException(context.getString(R.string.fw_folder_bridge_error_no_pairing))
        val csr = ensureKeyAndCsr(context)
        val certificate = requestCertificate(config, csr)
        storeCert(context, certificate)
        return fingerprint(context)
            ?: throw IOException("Fingerprint unavailable after enrollment")
    }

    @Throws(Exception::class)
    private fun requestCertificate(config: BridgeConfig, csr: String): String {
        val client = BridgeClient(config)
        try {
            val body = JSONObject().put("csr", csr).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())
            val call = client.http.newCall(client.request("folder-key").post(body).build())
            call.timeout().timeout(60, TimeUnit.SECONDS)
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Mac returned ${response.code}")
                }
                val text = response.body?.string() ?: throw IOException("Empty response")
                if (text.length > 64 * 1024) {
                    throw IOException("Response too large")
                }
                return JSONObject(text).getString("cert")
            }
        } finally {
            client.close()
        }
    }

    private fun generateKey(context: Context): KeyPair {
        val pair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(KEY_SIZE)
        }.generateKeyPair()
        // The public half is stored next to the private key: deriving it back from the
        // private parameters is not supported by every RSA KeyFactory on Android.
        writePrivate(keyFile(context), FolderBridgeCsr.pemOf(
            pair.private.encoded, "-----BEGIN PRIVATE KEY-----", "-----END PRIVATE KEY-----"
        ))
        writePrivate(publicFile(context), FolderBridgeCsr.pemOf(
            pair.public.encoded, "-----BEGIN PUBLIC KEY-----", "-----END PUBLIC KEY-----"
        ))
        return pair
    }

    private fun loadKey(context: Context): KeyPair? {
        val keyPem = keyFile(context).takeIf { it.exists() }?.readText() ?: return null
        val publicPem = publicFile(context).takeIf { it.exists() }?.readText() ?: return null
        return try {
            val factory = java.security.KeyFactory.getInstance("RSA")
            val private = factory.generatePrivate(PKCS8EncodedKeySpec(decodePem(keyPem)))
            val public = factory.generatePublic(java.security.spec.X509EncodedKeySpec(decodePem(publicPem)))
            KeyPair(public, private)
        } catch (_: Exception) {
            null
        }
    }

    private fun decodePem(pem: String): ByteArray =
        Base64.decode(pem.lines().filter { !it.startsWith("-----") }.joinToString(""), Base64.NO_WRAP)

    private fun writePrivate(file: File, content: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(content)
        if (!temp.renameTo(file)) {
            throw IOException("Unable to save ${file.name}")
        }
    }

    private fun keyFile(context: Context): File = File(context.noBackupFilesDir, KEY_FILE)
    private fun publicFile(context: Context): File = File(context.noBackupFilesDir, PUBLIC_FILE)
    private fun certFile(context: Context): File = File(context.noBackupFilesDir, CERT_FILE)

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
