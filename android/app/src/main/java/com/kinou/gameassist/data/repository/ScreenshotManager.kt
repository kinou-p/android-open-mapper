package com.kinou.gameassist.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object ScreenshotManager {

    fun getScreenshotsDir(context: Context): File {
        val dir = File(context.filesDir, "screenshots")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Valide de manière stricte et canonique qu'un chemin de fichier cible
     * se trouve obligatoirement à l'intérieur du répertoire sécurisé screenshots/ de l'application.
     * Prévient les attaques par traversée de répertoire (Path Traversal) et l'accès arbitraire aux fichiers internes.
     */
    fun isPathInScreenshotsDir(context: Context, path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        return try {
            val allowedDir = getScreenshotsDir(context).canonicalPath + File.separator
            val candidate = File(path).canonicalPath
            candidate.startsWith(allowedDir)
        } catch (_: Exception) {
            false
        }
    }

    fun saveScreenshotFromUri(context: Context, profileId: String, uri: Uri): String? {
        return try {
            val dir = getScreenshotsDir(context)
            val targetFile = File(dir, "${profileId}_hud.jpg")

            // 1. Decode bounds without full allocation
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            // Calculate sample size if very large (e.g. > 3840px)
            var sampleSize = 1
            val maxDim = 2560
            while ((options.outWidth / sampleSize) > maxDim || (options.outHeight / sampleSize) > maxDim) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }

            // 2. Decode sampled bitmap directly from stream
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            FileOutputStream(targetFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.flush()
            }
            bitmap.recycle()
            targetFile.absolutePath
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    suspend fun loadScreenshotBitmapAsync(context: Context, path: String?, maxWidth: Int = 2560, maxHeight: Int = 1440): Bitmap? = withContext(Dispatchers.IO) {
        loadScreenshotBitmap(context, path, maxWidth, maxHeight)
    }

    fun loadScreenshotBitmap(context: Context, path: String?, maxWidth: Int = 2560, maxHeight: Int = 1440): Bitmap? {
        if (!isPathInScreenshotsDir(context, path)) return null
        val file = File(path!!)
        if (!file.exists()) return null

        return try {
            val boundsOptions = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

            var sampleSize = 1
            while ((boundsOptions.outWidth / sampleSize) > maxWidth || (boundsOptions.outHeight / sampleSize) > maxHeight) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }

    fun deleteScreenshot(context: Context, path: String?): Boolean {
        if (!isPathInScreenshotsDir(context, path)) return false
        val file = File(path!!)
        return if (file.exists()) file.delete() else false
    }

    fun duplicateScreenshot(context: Context, newProfileId: String, sourcePath: String?): String? {
        if (!isPathInScreenshotsDir(context, sourcePath)) return null
        val sourceFile = File(sourcePath!!)
        if (!sourceFile.exists()) return null

        return try {
            val dir = getScreenshotsDir(context)
            val targetFile = File(dir, "${newProfileId}_hud.jpg")
            sourceFile.copyTo(targetFile, overwrite = true)
            targetFile.absolutePath
        } catch (t: Throwable) {
            t.printStackTrace()
            null
        }
    }
}
