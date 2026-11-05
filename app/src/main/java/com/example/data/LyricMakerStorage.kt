package com.winsmediatama.lyricmakerpro.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
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
        require(projectName.isNotBlank()) { "Project name cannot be empty" }

        // Copy imported media into the app's Documents asset folders first, then
        // serialize the copied content URIs so a reopened project is self-contained.
        val projectWithLocalAssets = copyProjectAssets(context, project)
            .copy(title = projectName.trim())

        // Keep a stable filename for this project ID so subsequent saves replace the
        // same project document rather than creating timestamped duplicates.
        val preferences = context.getSharedPreferences("lyricmakerpro_project_files", Context.MODE_PRIVATE)
        val key = "project_file_${project.id}"
        val name = preferences.getString(key, null)
            ?: findPreviouslySavedProjectName(context, project.id)
            ?: "${safeName(projectName)}_${project.id.take(8)}.lyricmaker.json"
        val savedUri = saveText(
            context, name, "application/json", "$ROOT/Project",
            ProjectFileCodec.encode(projectWithLocalAssets)
        )
        preferences.edit().putString(key, name).apply()
        return savedUri
    }

    private fun copyProjectAssets(context: Context, project: VideoProject): VideoProject {
        val audioTrack = project.audioTrack.let { track ->
            if (track.beatType == com.winsmediatama.lyricmakerpro.model.AudioBeatType.CUSTOM_FILE) {
                track.copy(customUri = track.customUri?.let {
                    copyAssetToDocuments(context, Uri.parse(it), "Audio", project.id, track.id)
                })
            } else track
        }

        val mediaClips = project.mediaClips.map { clip ->
            val folder = when (clip.type) {
                com.winsmediatama.lyricmakerpro.model.MediaClipType.IMAGE -> "Image"
                com.winsmediatama.lyricmakerpro.model.MediaClipType.VIDEO -> "Video"
            }
            clip.copy(
                uri = copyAssetToDocuments(context, Uri.parse(clip.uri), folder, project.id, clip.id)
            )
        }

        val background = project.background.copy(
            imageUri = project.background.imageUri?.let {
                copyAssetToDocuments(context, Uri.parse(it), "Image", project.id, "background")
            }
        )
        return project.copy(audioTrack = audioTrack, mediaClips = mediaClips, background = background)
    }

    /**
     * Copies a selected content:// or file:// asset to Documents/LyricMakerPro/Asset.
     * MediaStore content URIs are stored in the project JSON and remain usable after
     * the original picker grant is lost. Re-saving a project updates the same copy.
     */
    private fun copyAssetToDocuments(
        context: Context,
        sourceUri: Uri,
        assetFolder: String,
        projectId: String,
        assetId: String
    ): String {
        require(assetFolder in setOf("Audio", "Image", "Video")) { "Unsupported asset folder" }

        val relativePath = "${Environment.DIRECTORY_DOCUMENTS}/$ROOT/Asset/$assetFolder/"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sourceUri.scheme == "content") {
            val alreadyInTarget = runCatching {
                context.contentResolver.query(
                    sourceUri,
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                    null, null, null
                )?.use { cursor ->
                    cursor.moveToFirst() &&
                        cursor.getString(0)?.equals(relativePath, ignoreCase = true) == true
                } ?: false
            }.getOrDefault(false)
            if (alreadyInTarget) return sourceUri.toString()
        }
        if (sourceUri.scheme == "file" &&
            sourceUri.path?.contains("/$ROOT/Asset/$assetFolder/") == true
        ) return sourceUri.toString()

        val resolver = context.contentResolver
        val originalName = runCatching {
            resolver.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: sourceUri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "asset"

        val cleanOriginalName = safeName(originalName)
        val extension = cleanOriginalName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
        val baseName = cleanOriginalName.substringBeforeLast('.', cleanOriginalName)
        val destinationName = "${safeName(projectId.take(8))}_${safeName(assetId.take(12))}_$baseName" +
            (extension?.let { ".$it" } ?: "")
        val mimeType = resolver.getType(sourceUri)
            ?: when (assetFolder) {
                "Audio" -> "audio/*"
                "Image" -> "image/*"
                else -> "video/*"
            }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Files.getContentUri("external")
            val existingUri = resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(destinationName, relativePath),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Uri.withAppendedPath(collection, cursor.getLong(0).toString())
                } else null
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, destinationName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val destinationUri = existingUri ?: requireNotNull(resolver.insert(collection, values)) {
                "Cannot create asset file: $destinationName"
            }
            try {
                if (existingUri != null) resolver.update(destinationUri, values, null, null)
                val input = resolver.openInputStream(sourceUri)
                    ?: error("Cannot read selected asset: $originalName")
                val output = resolver.openOutputStream(destinationUri, "wt")
                    ?: error("Cannot write copied asset: $destinationName")
                input.use { source -> output.use { target -> source.copyTo(target) } }
                val published = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                check(resolver.update(destinationUri, published, null, null) > 0) {
                    "Cannot publish copied asset: $destinationName"
                }
                return destinationUri.toString()
            } catch (error: Exception) {
                if (existingUri == null) resolver.delete(destinationUri, null, null)
                throw error
            }
        }

        // Older Android versions use the app's Documents directory without broad storage permission.
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, Environment.DIRECTORY_DOCUMENTS)
        val directory = File(base, "$ROOT/Asset/$assetFolder").apply {
            if (!exists() && !mkdirs()) error("Cannot create asset folder: $absolutePath")
        }
        val destination = File(directory, destinationName)
        resolver.openInputStream(sourceUri)?.use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot read selected asset: $originalName")
        return Uri.fromFile(destination).toString()
    }

    /**
     * Migrate projects saved by older app versions, whose filenames contained a
     * timestamp, by locating the document with the same embedded project ID.
     */
    private fun findPreviouslySavedProjectName(context: Context, projectId: String): String? {
        val relativeDirectory = "${Environment.DIRECTORY_DOCUMENTS}/$ROOT/Project/"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = MediaStore.Files.getContentUri("external")
            return context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(relativeDirectory),
                null
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                var matchedName: String? = null
                while (cursor.moveToNext() && matchedName == null) {
                    val uri = Uri.withAppendedPath(collection, cursor.getLong(idColumn).toString())
                    val json = runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    }.getOrNull()
                    if (!json.isNullOrBlank() && runCatching { ProjectFileCodec.decode(json).id == projectId }.getOrDefault(false)) {
                        matchedName = cursor.getString(nameColumn)
                    }
                }
                matchedName
            }
        }

        val baseDirectory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, Environment.DIRECTORY_DOCUMENTS)
        return File(baseDirectory, "$ROOT/Project").listFiles()
            ?.firstOrNull { file ->
                file.isFile && runCatching {
                    ProjectFileCodec.decode(file.readText(Charsets.UTF_8)).id == projectId
                }.getOrDefault(false)
            }?.name
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
