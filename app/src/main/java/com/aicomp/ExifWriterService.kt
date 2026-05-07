package com.aicomp

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.IOException

/**
 * EXIF 写入服务
 * 将 AI 构图信息写入照片元数据，并保存到系统相册
 * 关键修复：正确处理照片旋转（竖拍照片不横着保存）
 */
object ExifWriterService {

    private const val TAG = "ExifWriterService"

    data class CompositionExifData(
        val sceneId: String,
        val sceneLabel: String,
        val matchedRuleIds: List<String>,
        val matchedRuleLabels: List<String>,
        val isAutoShutter: Boolean
    )

    /**
     * 将照片保存到系统相册（自动处理旋转）
     */
    fun savePhotoToGallery(
        outputFile: File,
        exifData: CompositionExifData,
        contentResolver: android.content.ContentResolver
    ): Uri? {
        return try {
            // 1. 先读取 EXIF 旋转角度
            val rotationDegrees = readExifRotation(outputFile)
            Log.d(TAG, "Photo rotation: $rotationDegrees°, file: ${outputFile.name}")

            // 2. 解码 Bitmap 并应用旋转
            val originalBitmap = BitmapFactory.decodeFile(outputFile.absolutePath)
            if (originalBitmap == null) {
                Log.e(TAG, "Failed to decode bitmap from ${outputFile.absolutePath}")
                return null
            }

            val rotatedBitmap = if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(
                    originalBitmap, 0, 0,
                    originalBitmap.width, originalBitmap.height,
                    matrix, true
                )
                originalBitmap.recycle()
                Log.d(TAG, "Rotated bitmap: ${rotated.width}x${rotated.height}")
                rotated
            } else {
                originalBitmap
            }

            // 3. 创建 MediaStore 记录
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, outputFile.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
                put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())

                // 宽高元数据（旋转后的实际尺寸）
                put(MediaStore.Images.Media.WIDTH, rotatedBitmap.width)
                put(MediaStore.Images.Media.HEIGHT, rotatedBitmap.height)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(
                        MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/AIComposition"
                    )
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val uri = contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return null

            // 4. 写入旋转后的图片数据
            contentResolver.openOutputStream(uri)?.use { out ->
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            rotatedBitmap.recycle()

            // 5. 写入 EXIF 构图信息（写到原始文件上）
            writeCompositionInfo(outputFile, exifData)

            // 6. 清除 IS_PENDING
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val updateValues = ContentValues().apply {
                    put(MediaStore.Images.Media.IS_PENDING, 0)
                }
                contentResolver.update(uri, updateValues, null, null)
            }

            Log.d(TAG, "Photo saved to gallery: $uri")
            uri
        } catch (e: IOException) {
            Log.e(TAG, "Failed to save photo", e)
            null
        }
    }

    /**
     * 从 EXIF 读取旋转角度
     * @return 旋转角度 (0, 90, 180, 270)
     */
    private fun readExifRotation(file: File): Int {
        return try {
            val exif = ExifInterface(file.absolutePath)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                ExifInterface.ORIENTATION_TRANSVERSE -> 270
                ExifInterface.ORIENTATION_TRANSPOSE -> 90
                else -> 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read EXIF rotation", e)
            0
        }
    }

    /**
     * 写入构图信息到照片 EXIF 元数据
     */
    fun writeCompositionInfo(
        imageFile: File,
        data: CompositionExifData
    ): Boolean {
        return try {
            val exif = ExifInterface(imageFile.absolutePath)
            val comment = buildExifComment(data)
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, comment)
            exif.setAttribute(ExifInterface.TAG_USER_COMMENT, comment)
            exif.saveAttributes()
            true
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write EXIF", e)
            false
        }
    }

    private fun buildExifComment(data: CompositionExifData): String {
        return buildString {
            appendLine("AI Composition Assistant")
            appendLine("Scene: ${data.sceneLabel}")
            appendLine("Rules: ${data.matchedRuleLabels.joinToString(", ")}")
            appendLine("Auto Shutter: ${if (data.isAutoShutter) "Yes" else "No"}")
        }.trimEnd()
    }
}
