package me.zhanghai.android.files.lanshare

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanShareQrTest {
    @Test fun ordinaryScannerDecodesFullCapabilityUrl() {
        val url = "http://192.168.1.25:54321/aBcDeFgHiJkLmNoPqRsTuV/"
        val matrix = LanShareQr.encode(url)
        val pixels = IntArray(matrix.width * matrix.height) { index ->
            if (matrix[index % matrix.width, index / matrix.width]) -0x1000000 else -1
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        assertEquals(url, QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source))).text)
        assertTrue((0 until matrix.width).all { !matrix[it, 0] && !matrix[it, matrix.height - 1] })
    }
}
