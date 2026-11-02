package me.zhanghai.android.files.lanshare

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter

object LanShareQr {
    /** Quiet zone and high-contrast modules are left intact for ordinary phone cameras. */
    fun encode(url: String, size: Int = 512): BitMatrix = QRCodeWriter().encode(
        url, BarcodeFormat.QR_CODE, size, size,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4)
    )
}
