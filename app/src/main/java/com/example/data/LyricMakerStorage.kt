package com.winsmediatama.lyricmakerpro.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.winsmediatama.lyricmakerpro.model.VideoProject
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

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())

    fun saveProject(context: Context, project: VideoProject, projectName: String = project.title): Uri {
        // Keep a stable filename for this project ID so subsequent saves replace the
        // same document, even if the user changes the visible project title.
        val preferences = context.getSharedPreferences("lyricmakerpro_project_files", Context.MODE_PRIVATE)
        val key = "project_file_${project.id}"
        val name = preferences.getString(key, null) ?: "${safeName(projectName)}_${project.id.take(8)}.lyricmaker.json"
        val savedUri = saveText(
            context, name, "application/json", "$ROOT/Project",
            ProjectFileCodec.encode(project.copy(title = projectName.trim()))
        )
        preferences.edit().putString(key, name).apply()
        return savedUri
    }

    fun saveLrc(context: Context, project: VideoProject): Uri {
        val songTitle = project.audioTrack.title.takeIf { it.isNotBlank() && it != "No audio" } ?: project.title
        val name = "${safeName(songTitle)}.lrc"
        val content = project.lyrics.sortedBy { it.startMs }.joinToString("\n") { line ->
            "[${formatLrcTime(line.startMs)}]${line.text}"
        }
        // Use an LRC MIME type and an explicit .lrc display name; never append .txt.
        return saveText(context, name, "application/x-lrc", "$ROOT/LrcLyric", content + "\n")
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
                // RELATIVE_PATH must begin with a standard public collection directory.
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOCUMENTS}/$relativePath/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Files.getContentUri("external")
            val relativeDirectory = "${Environment.DIRECTORY_DOCUMENTS}/$relativePath/"
            val existingUri = context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(name, relativeDirectory),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                } else null
            }
            val uri = existingUri ?: requireNotNull(context.contentResolver.insert(collection, values)) {
                "Cannot create $name"
            }
            try {
                if (existingUri != null) {
                    context.contentResolver.update(uri, values, null, null)
                }
                val output = context.contentResolver.openOutputStream(uri, "wt")
                    ?: error("Cannot open output stream for $name")
                output.bufferedWriter(Charsets.UTF_8).use { writer ->
                    writer.write(content)
                    writer.flush()
                }
                val publish = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                val updated = context.contentResolver.update(uri, publish, null, null)
                if (updated <= 0) error("Could not publish saved file $name")
                return uri
            } catch (error: Exception) {
                if (existingUri == null) context.contentResolver.delete(uri, null, null)
                throw error
            }
        }

        // Android 9 and earlier: use the app-specific Documents directory.
        // This avoids requesting broad legacy storage permissions.
        val baseDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, Environment.DIRECTORY_DOCUMENTS)
        val directory = File(baseDirectory, relativePath).apply {
            if (!exists() && !mkdirs()) error("Cannot create project folder: $absolutePath")
            if (!isDirectory || !canWrite()) error("Project folder is not writable: $absolutePath")
        }
        val file = File(directory, name)
        file.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(content)
            writer.flush()
        }
        if (!file.exists() || file.length() <= 0L) error("Project file was not written")
        return Uri.fromFile(file)
    }

    fun copyExportedVideo(context: Context, source: File): Uri {
        val name = "LyricMaker_${timestamp()}.mp4"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ROOT/Video/")
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
