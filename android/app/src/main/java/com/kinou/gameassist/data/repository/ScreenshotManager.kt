package com.kinou.gameassist.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ScreenshotManager {

    private fun getScreenshotsDir(context: Context): File {
        val dir = File(context.filesDir, "screenshots")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun saveScreenshotFromUri(context: Context, profileId: String, uri: Uri): String? {
        return try {
            val dir = getScreenshotsDir(context)
            val targetFile = File(dir, "${profileId}_hud.jpg")

            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // First decode bounds
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                val bytes = inputStream.readBytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)

                // Calculate sample size if very large (e.g. > 3840px)
                var sampleSize = 1
                val maxDim = 3840
                val largerDim = maxOf(options.outWidth, options.outHeight)
                while (largerDim / sampleSize > maxDim) {
                    sampleSize *= 2
                }

                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                    ?: return null

                FileOutputStream(targetFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
                    out.flush()
                }
                bitmap.recycle()
                targetFile.absolutePath
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun loadScreenshotBitmap(path: String?, maxWidth: Int = 2560, maxHeight: Int = 1440): Bitmap? {
        if (path == null) return null
        val file = File(path)
        if (!file.exists()) return null

        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

            var sampleSize = 1
            if (boundsOptions.outWidth > maxWidth || boundsOptions.outHeight > maxHeight) {
                val halfWidth = boundsOptions.outWidth / 2
                val halfHeight = boundsOptions.outHeight / 2
                while ((halfWidth / sampleSize) >= maxWidth && (halfHeight / sampleSize) >= maxHeight) {
                    sampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteScreenshot(path: String?): Boolean {
        if (path == null) return false
        val file = File(path)
        return if (file.exists()) file.delete() else false
    }

    fun duplicateScreenshot(context: Context, newProfileId: String, sourcePath: String?): String? {
        if (sourcePath == null) return null
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) return null

        return try {
            val dir = getScreenshotsDir(context)
            val targetFile = File(dir, "${newProfileId}_hud.jpg")
            sourceFile.copyTo(targetFile, overwrite = true)
            targetFile.absolutePath
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
