package com.wifishare.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * On-device WebP compression for share-sheet image uploads.
 *
 * Uses Android's built-in libwebp via Bitmap.compress(WEBP_LOSSY/WEBP) so
 * we don't have to bundle a WASM runtime. EXIF orientation is applied
 * manually because re-encoding loses the original EXIF tag.
 */
object ImageCompressor {

    /**
     * Preset bundles a scale fraction (0.0..1.0 of original dimensions)
     * with a WebP quality (0..100). ORIGINAL is a pass-through marker —
     * the caller skips compression entirely.
     */
    enum class Preset(val label: String, val scale: Float, val quality: Int) {
        ORIGINAL("Original", 1.0f, 100),
        P70("70%", 0.85f, 85),
        HALVED("Halved", 0.50f, 75),
        P25("25%", 0.25f, 65),
        CUSTOM("Custom", 0.50f, 75);
    }

    fun isImageMime(mime: String?): Boolean =
        mime != null && mime.substringBefore(';').trim().lowercase().startsWith("image/")

    fun compress(
        context: Context,
        sourceUri: Uri,
        scale: Float,
        quality: Int,
        outDir: File,
        baseName: String,
    ): File? {
        val (origW, origH) = readDimensions(context, sourceUri) ?: return null
        val targetW = (origW * scale).toInt().coerceAtLeast(1)
        val targetH = (origH * scale).toInt().coerceAtLeast(1)

        val decoded = decodeSampled(context, sourceUri, targetW, targetH) ?: return null
        val oriented = applyExifOrientation(context, sourceUri, decoded)

        val scaled = if (oriented.width != targetW || oriented.height != targetH) {
            val s = Bitmap.createScaledBitmap(oriented, targetW, targetH, true)
            if (s !== oriented) oriented.recycle()
            s
        } else oriented

        val out = File(outDir, baseName.substringBeforeLast('.', baseName) + ".webp")
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        val ok = out.outputStream().use { fos ->
            scaled.compress(format, quality.coerceIn(0, 100), fos)
        }
        scaled.recycle()
        return if (ok) out else null
    }

    private fun readDimensions(context: Context, uri: Uri): Pair<Int, Int>? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
        return if (opts.outWidth > 0 && opts.outHeight > 0)
            opts.outWidth to opts.outHeight
        else null
    }

    private fun decodeSampled(context: Context, uri: Uri, targetW: Int, targetH: Int): Bitmap? {
        val (w, h) = readDimensions(context, uri) ?: return null
        val opts = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(w, h, targetW, targetH)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }

    /**
     * Largest power-of-two sample size that still leaves both dimensions
     * at or above the target. Halves memory by 4× per step compared to
     * decoding full-res then scaling.
     */
    private fun computeSampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
        if (targetW <= 0 || targetH <= 0) return 1
        var sample = 1
        while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifOrientation(context: Context, uri: Uri, src: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
                ?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        val rotated = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (rotated !== src) src.recycle()
        return rotated
    }
}
