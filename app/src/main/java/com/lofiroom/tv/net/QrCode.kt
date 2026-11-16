package com.lofiroom.tv.net

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * #23 二维码编码：zxing core 仅做生成（无 javase 依赖）。
 * 深色模块画在浅色底上（面板浅色主题），纠错等级 M 折中容量与识别率。
 */
object QrCode {

    fun encode(text: String, sizePx: Int): Bitmap? = try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        bmp
    } catch (e: Exception) {
        null
    }
}
