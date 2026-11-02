package com.example.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.model.VideoProject
import java.io.File
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Saves user-facing artifacts in LyricMakerPro folders on shared internal storage.
 * Android 10+ uses MediaStore RELATIVE_PATH so scoped storage is respected.
 */
object LyricMakerStorage {
    private const val ROOT = "LyricMakerPro"

    private fun safeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('_').ifBlank { "Untitled" }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun saveProject(context: Context, project: VideoProject): Uri {
        val name = "${safeName(project.title)}_${timestamp()}.lyricmaker.json"
        return saveText(context, name, "application/json", "$ROOT/Project", ProjectFileCodec.encode(project))
    }

    fun saveLrc(context: Context, project: VideoProject): Uri {
        val name = "${safeName(project.title)}_${timestamp()}.lrc"
        val content = project.lyrics.sortedBy { it.startMs }.joinToString("\n") { line ->
            "[${formatLrcTime(line.startMs)}]${line.text}"
        }
        return saveText(context, name, "application/x-subrip", "$ROOT/LrcLyric", content + "\n")
    }

    private fun formatLrcTime(ms: Long): String {
        val safe = ms.coerceAtLeast(0L)
        val minutes = safe / 60_000L
        val seconds = (safe % 60_000L) / 1_000L
        val hundredths = (safe % 1_000L) / 10L
        return String.format(Locale.US, "%02d:%02d.%02d", minutes, seconds, hundredths)
    }

    private fun saveText(context: Context, name: String, mime: String, relativePath: String, content: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri("external")
            val uri = requireNotNull(context.contentResolver.insert(collection, values)) { "Cannot create $name" }
            try {
                context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use { it.write(content) }
                    ?: error("Cannot write $name")
                val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, publish, null, null)
                return uri
            } catch (error: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        }

        val directory = File(context.getExternalFilesDir(null), relativePath).apply { mkdirs() }
        val file = File(directory, name)
        file.writeText(content)
        return Uri.fromFile(file)
    }

    fun copyExportedVideo(context: Context, source: File): Uri {
        val name = "LyricMaker_${timestamp()}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$ROOT/Video")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = requireNotNull(context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)) {
                "Cannot create exported video"
            }
            try {
                context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                    FileInputStream(source).use { input -> input.copyTo(output) }
                } ?: error("Cannot write exported video")
                val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, publish, null, null)
                return uri
            } catch (error: Exception) {
                context.contentResolver.delete(uri, null, null)
                throw error
            }
        }

        val directory = File(context.getExternalFilesDir(null), "$ROOT/Video").apply { mkdirs() }
        val destination = File(directory, name)
        source.copyTo(destination, overwrite = true)
        return Uri.fromFile(destination)
    }
}
