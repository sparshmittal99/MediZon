package `in`.medizon.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.max

/**
 * Phone photos are often 3–10 MB, over the site's 5 MB upload limit (the server answered 413 and the page broke).
 * Photos picked or taken for an upload are resized to at most [MAX_SIDE] px and saved as JPEG, usually 300–800 KB:
 * still sharp for the pharmacist and the AI reader. PDFs and small images are passed through untouched.
 */
object ImageShrink {
    private const val TAG = "MediZonShrink"
    private const val MAX_SIDE = 2000
    private const val QUALITY = 85
    /** Images at or below this size and resolution are left alone. */
    private const val SMALL_BYTES = 1_500_000L

    /** Same list, with big photos replaced by shrunk copies. Never throws; on any problem the original is kept. */
    fun shrinkAll(c: Context, uris: Array<Uri>): Array<Uri> = uris.map { shrink(c, it) }.toTypedArray()

    private fun shrink(c: Context, uri: Uri): Uri {
        return try {
            val type = c.contentResolver.getType(uri) ?: guessType(uri)
            if (type == null || !type.startsWith("image/") || type == "image/gif") return uri
            val size = sizeOf(c, uri)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            val longSide = max(bounds.outWidth, bounds.outHeight)
            if (longSide <= 0) return uri
            if (size in 1..SMALL_BYTES && longSide <= MAX_SIDE) return uri

            var sample = 1
            while (longSide / (sample * 2) >= MAX_SIDE) sample *= 2
            val decoded = c.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return uri
            val rotated = rotateForExif(c, uri, decoded)
            val scale = MAX_SIDE.toFloat() / max(rotated.width, rotated.height)
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true)
            } else rotated

            val dir = File(c.cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("upload_", ".jpg", dir)
            file.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
            if (out !== rotated) out.recycle()
            if (rotated !== decoded) rotated.recycle()
            decoded.recycle()
            Log.i(TAG, "shrunk ${size / 1024} KB -> ${file.length() / 1024} KB")
            FileProvider.getUriForFile(c, c.packageName + ".fileprovider", file)
        } catch (e: Throwable) {
            Log.w(TAG, "couldn't shrink $uri, sending the original", e)
            uri
        }
    }

    /** Camera JPEGs are often stored sideways with an EXIF rotation tag; bake the rotation into the pixels. */
    private fun rotateForExif(c: Context, uri: Uri, bmp: Bitmap): Bitmap {
        val orientation = try {
            c.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (e: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees) }, true)
    }

    private fun sizeOf(c: Context, uri: Uri): Long = try {
        c.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst() && !cur.isNull(0)) cur.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }

    private fun guessType(uri: Uri): String? {
        val name = uri.lastPathSegment?.lowercase() ?: return null
        return when {
            name.endsWith(".jpg") || name.endsWith(".jpeg") -> "image/jpeg"
            name.endsWith(".png") -> "image/png"
            name.endsWith(".webp") -> "image/webp"
            else -> null
        }
    }
}
