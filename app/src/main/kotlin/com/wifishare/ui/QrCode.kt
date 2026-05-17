package com.wifishare.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR code rendered straight onto a Compose canvas — no Bitmap, no
 * scaling artifacts at any size. Memoizes the bit-matrix so the
 * encode runs once per (value, size) pair.
 */
@Composable
fun QrCode(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Black,
) {
    val matrix = remember(value) {
        runCatching {
            QRCodeWriter().encode(
                value,
                BarcodeFormat.QR_CODE,
                256, 256,
                mapOf(
                    EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                    EncodeHintType.MARGIN to 0,
                ),
            )
        }.getOrNull()
    } ?: return

    Box(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color.White)
            .padding(12.dp),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cells = matrix.width
            val cell = size.width / cells
            for (y in 0 until cells) {
                for (x in 0 until cells) {
                    if (matrix.get(x, y)) {
                        drawRect(
                            color = color,
                            topLeft = Offset(x * cell, y * cell),
                            size = Size(cell, cell),
                        )
                    }
                }
            }
        }
    }
}
