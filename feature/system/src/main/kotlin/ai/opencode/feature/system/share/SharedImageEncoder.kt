package ai.opencode.feature.system.share

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The same downscale and re-encode the composer applies to imported images. Rendering a fresh JPEG
 * from decoded pixels drops EXIF, GPS, and other metadata, and keeps prompts small. `ImageDecoder`
 * applies the EXIF orientation.
 */
object SharedImageEncoder {
    const val MAX_SOURCE_BYTES = 20_000_000
    const val MAX_PIXEL_SIZE = 1568
    const val COMPRESSION_QUALITY = 80
    const val MIME = "image/jpeg"

    fun jpeg(data: ByteArray): ByteArray? {
        if (data.size > MAX_SOURCE_BYTES) return null
        return encode(thumbnail(ImageDecoder.createSource(ByteBuffer.wrap(data)), MAX_PIXEL_SIZE))
    }

    /** Decodes straight from the file so a large original is never held in memory as a whole. */
    fun jpeg(file: File): ByteArray? {
        val size = runCatching { file.length() }.getOrDefault(Long.MAX_VALUE)
        if (!file.isFile || size > MAX_SOURCE_BYTES) return null
        return encode(thumbnail(ImageDecoder.createSource(file), MAX_PIXEL_SIZE))
    }

    /**
     * Another app's `content://` image. Decoded from the provider when it reports a size within
     * [MAX_SOURCE_BYTES]; otherwise read (capped) into memory first.
     */
    fun jpeg(contentResolver: ContentResolver, uri: Uri): ByteArray? {
        val length = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull() ?: return null
        if (length != AssetFileDescriptor.UNKNOWN_LENGTH) {
            if (length > MAX_SOURCE_BYTES) return null
            return encode(thumbnail(ImageDecoder.createSource(contentResolver, uri), MAX_PIXEL_SIZE))
        }
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_SOURCE_BYTES) return@use null
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }.getOrNull() ?: return null
        return jpeg(bytes)
    }

    fun thumbnail(data: ByteArray, maxPixelSize: Int): Bitmap? =
        thumbnail(ImageDecoder.createSource(ByteBuffer.wrap(data)), maxPixelSize)

    private fun thumbnail(source: ImageDecoder.Source, maxPixelSize: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val longest = maxOf(width, height)
            if (longest > maxPixelSize) {
                val scale = maxPixelSize.toDouble() / longest
                decoder.setTargetSize(
                    maxOf(1, Math.round(width * scale).toInt()),
                    maxOf(1, Math.round(height * scale).toInt()),
                )
            }
        }
    } catch (_: IOException) {
        null
    } catch (_: RuntimeException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }

    private fun encode(bitmap: Bitmap?): ByteArray? {
        bitmap ?: return null
        val output = ByteArrayOutputStream()
        val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, COMPRESSION_QUALITY, output)
        bitmap.recycle()
        return if (ok) output.toByteArray() else null
    }
}
