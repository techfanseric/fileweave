package me.zhanghai.android.files.folderbridge

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.*
import org.junit.Test

/**
 * DER/CSR structure self-checks with a small in-test DER reader: tags, lengths (including the
 * long forms) and a real SHA256withRSA signature round trip. No external command is invoked.
 */
class FolderBridgeCsrTest {
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("RSA").apply {
        initialize(2048)
    }.generateKeyPair()

    private val sign: (ByteArray) -> ByteArray = { info ->
        Signature.getInstance("SHA256withRSA").apply {
            initSign(keyPair.private)
            update(info)
        }.sign()
    }

    private fun buildCsr(commonName: String = "FileWeave Folder Test Device"): ByteArray =
        FolderBridgeCsr.buildDer(commonName, keyPair.public.encoded, sign)

    @Test
    fun oidEncodingUsesExactDerBytes() {
        assertArrayEquals(
            byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03), FolderBridgeDer.oid(2, 5, 4, 3)
        )
        assertArrayEquals(
            byteArrayOf(0x06, 0x09, 0x2A.toByte(), 0x86.toByte(), 0x48, 0x86.toByte(),
                0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B.toByte()),
            FolderBridgeDer.oid(1, 2, 840, 113549, 1, 1, 11)
        )
    }

    @Test
    fun smallIntegersAndNullUseMinimalEncoding() {
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x00), FolderBridgeDer.integer(0))
        assertArrayEquals(byteArrayOf(0x02, 0x01, 0x7F), FolderBridgeDer.integer(127))
        assertArrayEquals(byteArrayOf(0x05, 0x00), FolderBridgeDer.nullValue())
        assertArrayEquals(byteArrayOf(0xA0.toByte(), 0x00), FolderBridgeDer.emptyContext0())
    }

    @Test
    fun csrHasPkcs10Structure() {
        val csr = buildCsr()
        val outer = DerReader(csr)
        val body = outer.readTag(0x30)
        assertEquals("whole CSR is one SEQUENCE", csr.size, outer.offset)

        val reader = DerReader(body)
        val infoStart = reader.offset
        val info = reader.readTag(0x30)
        val infoEnd = reader.offset
        val infoBytes = body.copyOfRange(infoStart, infoEnd)

        // signatureAlgorithm: sha256WithRSAEncryption with NULL parameters.
        val algorithm = DerReader(reader.readTag(0x30))
        assertArrayEquals(
            byteArrayOf(0x2A.toByte(), 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(),
                0x0D, 0x01, 0x01, 0x0B.toByte()),
            algorithm.readTag(0x06)
        )
        assertArrayEquals(ByteArray(0), algorithm.readTag(0x05))

        // signature: BIT STRING with zero unused bits.
        val signature = reader.readTag(0x03)
        assertEquals(0, signature[0].toInt())
        assertEquals("info + algorithm + signature fill the CSR body", body.size, reader.offset)

        // certificationRequestInfo: version v1(0), CN-only subject, SPKI, empty attributes.
        val infoReader = DerReader(info)
        assertArrayEquals(byteArrayOf(0), infoReader.readTag(0x02))
        val subject = DerReader(infoReader.readTag(0x30))
        val rdn = DerReader(subject.readTag(0x31))
        val attribute = DerReader(rdn.readTag(0x30))
        assertArrayEquals(byteArrayOf(0x55, 0x04, 0x03), attribute.readTag(0x06))
        assertEquals("FileWeave Folder Test Device",
            String(attribute.readTag(0x0C), Charsets.UTF_8))
        val spkiContent = DerReader(keyPair.public.encoded).readTag(0x30)
        assertArrayEquals(spkiContent, infoReader.readTag(0x30))
        assertArrayEquals(ByteArray(0), infoReader.readTag(0xA0))
        assertEquals("attributes are the last field of the info", info.size, infoReader.offset)

        // The signature really covers the DER of certificationRequestInfo.
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update(infoBytes)
        }
        assertTrue(verifier.verify(signature.copyOfRange(1, signature.size)))
    }

    @Test
    fun longCommonNameForcesLongFormLengthsAndStillVerifies() {
        val commonName = "FileWeave Folder " + "A".repeat(300)
        val csr = buildCsr(commonName)
        // A 300-byte UTF8String forces a long-form length (0x81 xx or 0x82 xx xx) somewhere.
        var longForm = false
        var index = 0
        while (index < csr.size - 1) {
            val byte = csr[index].toInt() and 0xFF
            if (byte == 0x81 || byte == 0x82) {
                longForm = true
                break
            }
            index += 1
        }
        assertTrue(longForm)

        val outer = DerReader(csr)
        val body = outer.readTag(0x30)
        assertEquals(csr.size, outer.offset)
        val reader = DerReader(body)
        val infoStart = reader.offset
        reader.readTag(0x30)
        val infoEnd = reader.offset
        val infoBytes = body.copyOfRange(infoStart, infoEnd)
        reader.readTag(0x30) // signatureAlgorithm
        val signature = reader.readTag(0x03)
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(keyPair.public)
            update(infoBytes)
        }
        assertTrue(verifier.verify(signature.copyOfRange(1, signature.size)))
    }

    /** Minimal DER reader for asserting on our own output. */
    private class DerReader(private val data: ByteArray) {
        var offset = 0
            private set

        fun readTag(expectedTag: Int): ByteArray {
            assertEquals("tag at $offset", expectedTag, data[offset].toInt() and 0xFF)
            offset += 1
            var length = data[offset].toInt() and 0xFF
            offset += 1
            if (length and 0x80 != 0) {
                val count = length and 0x7F
                assertTrue("only one or two length bytes are expected", count in 1..2)
                length = 0
                repeat(count) {
                    length = (length shl 8) or (data[offset].toInt() and 0xFF)
                    offset += 1
                }
            }
            val content = data.copyOfRange(offset, offset + length)
            offset += length
            return content
        }
    }
}
