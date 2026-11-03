package com.winsmediatama.lyricmakerpro.export

import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.winsmediatama.lyricmakerpro.model.*
import com.winsmediatama.lyricmakerpro.data.LyricMakerStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import kotlin.math.*

class VideoExportEngine(private val context: Context) {

    private data class ExportMediaAsset(
        val clip: MediaClip,
        val image: Bitmap? = null,
        val retriever: MediaMetadataRetriever? = null
    )

    private fun prepareMediaAssets(clips: List<MediaClip>): List<ExportMediaAsset> = clips.mapNotNull { clip ->
        runCatching {
            when (clip.type) {
                MediaClipType.IMAGE -> {
                    val bitmap = context.contentResolver.openInputStream(Uri.parse(clip.uri))?.use { BitmapFactory.decodeStream(it) }
                    bitmap?.let { ExportMediaAsset(clip = clip, image = it) }
                }
                MediaClipType.VIDEO -> {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, Uri.parse(clip.uri))
                        ExportMediaAsset(clip = clip, retriever = retriever)
                    } catch (error: Exception) {
                        retriever.release()
                        throw error
                    }
                }
            }
        }.getOrNull()
    }

    private fun releaseMediaAssets(assets: List<ExportMediaAsset>) {
        assets.forEach { asset ->
            runCatching { asset.image?.recycle() }
            runCatching { asset.retriever?.release() }
        }
    }

    data class ExportProgress(
        val currentFrame: Int,
        val totalFrames: Int,
        val percentage: Float,
        val statusMessage: String
    )

    @androidx.media3.common.util.UnstableApi
    suspend fun exportVideo(
        project: VideoProject,
        quality: ExportQuality,
        durationSeconds: Int = 15,
        onProgress: (ExportProgress) -> Unit
    ): File = withContext(Dispatchers.Main.immediate) {
        val audioUri = project.audioTrack.customUri
            ?: error("Import a song before exporting an MP4 with audio")
        val totalDurationMs = if (durationSeconds <= 0) {
            project.durationMs.coerceAtLeast(1L)
        } else {
            min(project.durationMs, durationSeconds * 1000L).coerceAtLeast(1L)
        }
        val exportDir = File(context.cacheDir, "clips").apply { mkdirs() }
        val outputFile = File(exportDir, "LyricVideo_${System.currentTimeMillis()}.mp4")
        val backgroundFile = File(exportDir, "LyricBackground_${System.currentTimeMillis()}.png")
        val width = (project.canvasRatio.exportWidth * quality.scaleFactor).toInt().let { if (it % 2 != 0) it + 1 else it }
        val height = (project.canvasRatio.exportHeight * quality.scaleFactor).toInt().let { if (it % 2 != 0) it + 1 else it }
        var mediaAssets: List<ExportMediaAsset> = emptyList()

        try {
            // Prepare the actual timeline media once. Each encoded frame below will
            // select the clip active at that presentation timestamp.
            mediaAssets = prepareMediaAssets(project.mediaClips)
            val watermarkBitmap = loadWatermarkBitmap()
            val overlay = ProjectLyricOverlay(
                this@VideoExportEngine, width, height, project, watermarkBitmap, mediaAssets
            )
            val effects = listOf<androidx.media3.common.Effect>(
                androidx.media3.effect.Presentation.createForWidthAndHeight(
                    width, height, androidx.media3.effect.Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                ),
                androidx.media3.effect.OverlayEffect(listOf(overlay))
            )
            val backgroundItem = androidx.media3.common.MediaItem.Builder()
                .setUri(Uri.fromFile(backgroundFile))
                .setImageDurationMs(totalDurationMs)
                .build()
            val videoItem = androidx.media3.transformer.EditedMediaItem.Builder(backgroundItem)
                .setFrameRate(30)
                .setRemoveAudio(true)
                .setEffects(androidx.media3.transformer.Effects(emptyList(), effects))
                .build()
            val clippedAudio = androidx.media3.common.MediaItem.Builder()
                .setUri(Uri.parse(audioUri))
                .setClippingConfiguration(
                    androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(0L)
                        .setEndPositionMs(totalDurationMs)
                        .build()
                )
                .build()
            val audioItem = androidx.media3.transformer.EditedMediaItem.Builder(clippedAudio)
                .setRemoveVideo(true)
                .build()
            val videoSequence = androidx.media3.transformer.EditedMediaItemSequence.withVideoFrom(listOf(videoItem))
            val audioSequence = androidx.media3.transformer.EditedMediaItemSequence.withAudioFrom(listOf(audioItem))
            val composition = androidx.media3.transformer.Composition.Builder(videoSequence, audioSequence).build()

            kotlinx.coroutines.suspendCancellableCoroutine<File> { continuation ->
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val progressHolder = androidx.media3.transformer.ProgressHolder()
                val active = java.util.concurrent.atomic.AtomicBoolean(true)
                lateinit var transformer: androidx.media3.transformer.Transformer
                transformer = androidx.media3.transformer.Transformer.Builder(context.applicationContext)
                    .setVideoMimeType(androidx.media3.common.MimeTypes.VIDEO_H264)
                    .setAudioMimeType(androidx.media3.common.MimeTypes.AUDIO_AAC)
                    .setEnsureFileStartsOnVideoFrameEnabled(true)
                    .addListener(object : androidx.media3.transformer.Transformer.Listener {
                        override fun onCompleted(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: androidx.media3.transformer.ExportResult
                        ) {
                            active.set(false)
                            try {
                                validateMp4HasAudioAndVideo(outputFile)
                                LyricMakerStorage.copyExportedVideo(context, outputFile)
                                if (continuation.isActive) continuation.resumeWith(Result.success(outputFile))
                            } catch (failure: Throwable) {
                                outputFile.delete()
                                if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                            }
                        }

                        override fun onError(
                            composition: androidx.media3.transformer.Composition,
                            exportResult: androidx.media3.transformer.ExportResult,
                            exportException: androidx.media3.transformer.ExportException
                        ) {
                            active.set(false)
                            outputFile.delete()
                            if (continuation.isActive) continuation.resumeWith(
                                Result.failure(IllegalStateException("Media3 export failed: ${exportException.message}", exportException))
                            )
                        }
                    }).build()

                val progressTask = object : Runnable {
                    override fun run() {
                        if (!active.get()) return
                        try {
                            if (transformer.getProgress(progressHolder) == androidx.media3.transformer.Transformer.PROGRESS_STATE_AVAILABLE) {
                                val pct = progressHolder.progress.coerceIn(0, 100)
                                onProgress(ExportProgress(pct, 100, pct / 100f, "Rendering synchronized video: $pct%"))
                            }
                            if (active.get()) handler.postDelayed(this, 250L)
                        } catch (_: Throwable) {
                            active.set(false)
                        }
                    }
                }
                continuation.invokeOnCancellation {
                    active.set(false)
                    handler.removeCallbacks(progressTask)
                    transformer.cancel()
                    outputFile.delete()
                }
                try {
                    transformer.start(composition, outputFile.absolutePath)
                    handler.post(progressTask)
                } catch (failure: Throwable) {
                    active.set(false)
                    outputFile.delete()
                    if (continuation.isActive) continuation.resumeWith(Result.failure(failure))
                }
            }
        } finally {
            releaseMediaAssets(mediaAssets)
            backgroundFile.delete()
        }
    }

    @androidx.media3.common.util.UnstableApi
    private class ProjectLyricOverlay(
        private val owner: VideoExportEngine,
        private val width: Int,
        private val height: Int,
        private val project: VideoProject,
        private val watermarkBitmap: Bitmap,
        private val mediaAssets: List<ExportMediaAsset>
    ) : androidx.media3.effect.BitmapOverlay() {
        private var cached: Bitmap? = null

        override fun getBitmap(presentationTimeUs: Long): Bitmap {
            val bitmap = cached?.takeIf { it.width == width && it.height == height }
                ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { cached = it }
            bitmap.eraseColor(Color.TRANSPARENT)
            owner.renderFrameInternal(
                Canvas(bitmap), width, height, project,
                (presentationTimeUs / 1000L).coerceAtLeast(0L),
                mediaAssets, watermarkBitmap
            )
            return bitmap
        }

        override fun release() {
            cached?.recycle()
            cached = null
            if (!watermarkBitmap.isRecycled) watermarkBitmap.recycle()
            super.release()
        }
    }

    private fun muxVideoAndAudio(videoFile: File, audioFile: File, outputFile: File, durationMs: Long) {
        val videoExtractor = android.media.MediaExtractor()
        val audioExtractor = android.media.MediaExtractor()
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)
            var videoSourceTrack = -1
            var audioSourceTrack = -1
            for (i in 0 until videoExtractor.trackCount) {
                if (videoExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    videoSourceTrack = i
                    videoExtractor.selectTrack(i)
                    break
                }
            }
            for (i in 0 until audioExtractor.trackCount) {
                if (audioExtractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioSourceTrack = i
                    audioExtractor.selectTrack(i)
                    break
                }
            }
            check(videoSourceTrack >= 0) { "Encoded video track is missing" }
            check(audioSourceTrack >= 0) { "Encoded AAC audio track is missing" }
            val videoOutTrack = muxer.addTrack(videoExtractor.getTrackFormat(videoSourceTrack))
            val audioOutTrack = muxer.addTrack(audioExtractor.getTrackFormat(audioSourceTrack))
            muxer.start()
            started = true
            copySamples(videoExtractor, muxer, videoOutTrack, durationMs)
            copySamples(audioExtractor, muxer, audioOutTrack, durationMs)
        } finally {
            runCatching { if (started) muxer.stop() }
            runCatching { muxer.release() }
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
        }
    }

    private fun copySamples(
        extractor: android.media.MediaExtractor,
        muxer: MediaMuxer,
        outputTrack: Int,
        durationMs: Long
    ) {
        val buffer = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            val pts = extractor.sampleTime
            if (pts < 0L || pts >= durationMs * 1000L) break
            info.set(0, size, pts, extractor.sampleFlags)
            muxer.writeSampleData(outputTrack, buffer, info)
            if (!extractor.advance()) break
        }
    }

    private fun validateMp4HasAudioAndVideo(file: File) {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var hasAudio = false
            var hasVideo = false
            for (i in 0 until extractor.trackCount) {
                when (extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.substringBefore('/')) {
                    "audio" -> hasAudio = true
                    "video" -> hasVideo = true
                }
            }
            check(hasAudio && hasVideo) { "Export validation failed: MP4 must contain audio and video tracks" }
        } finally {
            extractor.release()
        }
    }

    private fun exportStillFrameFallback(project: VideoProject, outputFile: File, mediaAssets: List<ExportMediaAsset>): File {
        val width = 1080
        val height = (width / project.canvasRatio.aspectRatio).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val activeLine = project.lyrics.firstOrNull()
        val timeMs = activeLine?.startMs ?: 1500L
        renderFrameInternal(canvas, width, height, project, timeMs, mediaAssets)

        val pngFile = File(outputFile.parentFile, "LyricPoster_${System.currentTimeMillis()}.png")
        FileOutputStream(pngFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return pngFile
    }

    fun exportPosterImage(project: VideoProject, timeMs: Long): File {
        val exportDir = File(context.cacheDir, "clips").apply { mkdirs() }
        val pngFile = File(exportDir, "LyricCover_${System.currentTimeMillis()}.png")

        val width = 1080
        val height = (width / project.canvasRatio.aspectRatio).toInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val mediaAssets = prepareMediaAssets(project.mediaClips)
        try {
            renderFrameInternal(canvas, width, height, project, timeMs, mediaAssets)
        } finally {
            releaseMediaAssets(mediaAssets)
        }

        FileOutputStream(pngFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return pngFile
    }

    fun shareFile(file: File, isVideo: Boolean) {
        val authority = "${context.packageName}.fileprovider"
        val contentUri: Uri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (isVideo) "video/mp4" else "image/png"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            putExtra(Intent.EXTRA_SUBJECT, "Created with LyricMaker")
            putExtra(Intent.EXTRA_TEXT, "Check out my lyric video made with LyricMaker! #LyricMaker #LyricVideo")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, "Share Lyric Video to Social Media")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun renderFrame(canvas: Canvas, width: Int, height: Int, project: VideoProject, timeMs: Long) {
        renderFrameInternal(canvas, width, height, project, timeMs, emptyList())
    }

    private fun renderFrameInternal(
        canvas: Canvas,
        width: Int,
        height: Int,
        project: VideoProject,
        timeMs: Long,
        mediaAssets: List<ExportMediaAsset>,
        watermarkBitmap: Bitmap? = null
    ) {
        val activeAsset = mediaAssets
            .filter { asset -> timeMs >= asset.clip.startMs && timeMs < asset.clip.endMs }
            .maxByOrNull { it.clip.startMs }
        val frameBitmap = when {
            activeAsset?.image != null -> activeAsset.image
            activeAsset?.retriever != null -> runCatching {
                activeAsset.retriever.getFrameAtTime(
                    (activeAsset.clip.sourceOffsetMs +
                        (timeMs - activeAsset.clip.startMs).coerceAtLeast(0L)) * 1_000L,
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
            }.getOrNull()
            else -> null
        }

        if (frameBitmap != null) {
            drawMediaFrame(canvas, width, height, frameBitmap)
            if (frameBitmap !== activeAsset?.image) frameBitmap.recycle()
        } else {
            drawBackground(canvas, width, height, project.background, timeMs)
        }

        // 2. Visualizer Overlay
        drawVisualizer(canvas, width, height, project.visualizer, project.visualizerColorHex, timeMs, project.audioTrack.bpm)

        // 3. Lyrics with Typography & Transitions
        drawLyrics(canvas, width, height, project, timeMs)

        // 4. Watermark / Creator Tag
        if (project.showWatermark) {
            if (watermarkBitmap != null && !watermarkBitmap.isRecycled) {
                drawWatermark(canvas, width, height, watermarkBitmap)
            } else {
                val watermark = loadWatermarkBitmap()
                try { drawWatermark(canvas, width, height, watermark) } finally { watermark.recycle() }
            }
        }
    }

    private fun drawMediaFrame(canvas: Canvas, width: Int, height: Int, bitmap: Bitmap) {
        val sourceWidth = bitmap.width.toFloat()
        val sourceHeight = bitmap.height.toFloat()
        if (sourceWidth <= 0f || sourceHeight <= 0f) return
        val scale = max(width / sourceWidth, height / sourceHeight)
        val cropWidth = width / scale
        val cropHeight = height / scale
        val left = (sourceWidth - cropWidth) / 2f
        val top = (sourceHeight - cropHeight) / 2f
        val source = Rect(
            left.toInt().coerceAtLeast(0),
            top.toInt().coerceAtLeast(0),
            (left + cropWidth).toInt().coerceAtMost(bitmap.width),
            (top + cropHeight).toInt().coerceAtMost(bitmap.height)
        )
        canvas.drawBitmap(bitmap, source, Rect(0, 0, width, height), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
    }

    private fun drawBackground(canvas: Canvas, width: Int, height: Int, bg: BackgroundConfig, timeMs: Long) {
        when (bg.type) {
            BackgroundType.SOLID -> {
                canvas.drawColor(parseColor(bg.primaryColorHex, Color.BLACK))
            }
            BackgroundType.GRADIENT -> {
                val c1 = parseColor(bg.primaryColorHex, Color.parseColor("#090A14"))
                val c2 = parseColor(bg.secondaryColorHex, Color.parseColor("#1B112C"))
                val c3 = parseColor(bg.accentColorHex, Color.parseColor("#FF007F"))

                val shader = LinearGradient(
                    0f, 0f, width.toFloat(), height.toFloat(),
                    intArrayOf(c1, c2, c3),
                    floatArrayOf(0f, 0.65f, 1f),
                    Shader.TileMode.CLAMP
                )
                val paint = Paint().apply { this.shader = shader }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

                // Dark vignette overlay
                val darkOverlay = Paint().apply {
                    color = Color.argb((bg.darkOverlayOpacity * 255).toInt(), 0, 0, 0)
                }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), darkOverlay)
            }
            BackgroundType.CYBER_GRID -> {
                canvas.drawColor(parseColor(bg.primaryColorHex, Color.parseColor("#08090F")))

                val gridPaint = Paint().apply {
                    color = parseColor(bg.accentColorHex, Color.parseColor("#00F5D4"))
                    alpha = 40
                    strokeWidth = 2f
                    style = Paint.Style.STROKE
                }

                val gridSize = (width / 12f).coerceAtLeast(30f)
                val scrollOffset = ((timeMs * 0.05) % gridSize).toFloat()

                var x = 0f
                while (x <= width) {
                    canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
                    x += gridSize
                }

                var y = scrollOffset
                while (y <= height) {
                    canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                    y += gridSize
                }
            }
            BackgroundType.MESH -> {
                val c1 = parseColor(bg.primaryColorHex, Color.parseColor("#12141A"))
                val c2 = parseColor(bg.secondaryColorHex, Color.parseColor("#281630"))
                val shader = RadialGradient(
                    width * 0.5f, height * 0.4f, width * 0.8f,
                    intArrayOf(c2, c1),
                    null,
                    Shader.TileMode.CLAMP
                )
                val paint = Paint().apply { this.shader = shader }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
            BackgroundType.IMAGE -> {
                canvas.drawColor(Color.BLACK)
                // If user selected image, fall back to aesthetic gradient if unresolvable
                val c1 = parseColor(bg.primaryColorHex, Color.DKGRAY)
                val c2 = parseColor(bg.secondaryColorHex, Color.BLACK)
                val shader = LinearGradient(0f, 0f, 0f, height.toFloat(), c1, c2, Shader.TileMode.CLAMP)
                val paint = Paint().apply { this.shader = shader }
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            }
        }
    }

    private fun drawVisualizer(
        canvas: Canvas,
        width: Int,
        height: Int,
        type: VisualizerType,
        colorHex: String,
        timeMs: Long,
        bpm: Int
    ) {
        if (type == VisualizerType.NONE) return

        val primaryColor = parseColor(colorHex, Color.parseColor("#00F5D4"))
        val beatIntervalMs = 60_000.0 / bpm.coerceAtLeast(60)
        val beatPhase = (timeMs % beatIntervalMs) / beatIntervalMs
        val kickEnergy = (1f - (beatPhase * 2.8f).toFloat()).coerceIn(0f, 1f)

        when (type) {
            VisualizerType.BARS_SPECTRUM -> {
                val barCount = 18
                val spacing = width * 0.012f
                val totalWidth = width * 0.88f
                val barWidth = (totalWidth - (spacing * (barCount - 1))) / barCount
                val startX = (width - totalWidth) / 2f
                val baseY = height * 0.82f
                val maxBarHeight = height * 0.16f

                val barPaint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.FILL
                }

                for (i in 0 until barCount) {
                    val freq = sin(timeMs * 0.006 + i * 0.6).toFloat()
                    val peak = if (i < 4) kickEnergy * 0.85f + 0.15f else (abs(freq) * 0.7f + 0.2f)
                    val barH = (peak * maxBarHeight).coerceAtLeast(10f)

                    val x = startX + i * (barWidth + spacing)
                    val rect = RectF(x, baseY - barH, x + barWidth, baseY)

                    val shader = LinearGradient(
                        x, baseY - barH, x, baseY,
                        intArrayOf(primaryColor, Color.parseColor("#FF007F")),
                        null,
                        Shader.TileMode.CLAMP
                    )
                    barPaint.shader = shader
                    canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, barPaint)
                }
            }
            VisualizerType.CIRCULAR_WAVE -> {
                val centerX = width / 2f
                val centerY = height * 0.35f
                val baseRadius = min(width, height) * 0.18f
                val radius = baseRadius * (1f + kickEnergy * 0.12f)

                val ringPaint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                    color = primaryColor
                    alpha = (180 + kickEnergy * 75).toInt()
                }

                canvas.drawCircle(centerX, centerY, radius, ringPaint)

                // Outer faint shockwave
                val outerPaint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                    color = Color.parseColor("#FF007F")
                    alpha = (kickEnergy * 140).toInt()
                }
                canvas.drawCircle(centerX, centerY, radius * 1.3f, outerPaint)
            }
            VisualizerType.OSCILLOSCOPE -> {
                val wavePaint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeWidth = 5f
                    color = primaryColor
                }
                val path = Path()
                val centerY = height * 0.80f
                val step = width / 60f

                path.moveTo(0f, centerY)
                for (i in 0..60) {
                    val x = i * step
                    val phase = (x / width) * 4 * Math.PI + (timeMs * 0.008)
                    val amp = (height * 0.04f) * (1f + kickEnergy * 0.5f)
                    val y = centerY + sin(phase).toFloat() * amp
                    path.lineTo(x, y)
                }
                canvas.drawPath(path, wavePaint)
            }
            VisualizerType.FLOATING_PARTICLES -> {
                val particlePaint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.FILL
                    color = primaryColor
                }
                val count = 24
                for (i in 0 until count) {
                    val speed = (i % 5 + 2) * 0.04f
                    val y = (height - ((timeMs * speed + i * 150) % height)).toFloat()
                    val x = ((sin(timeMs * 0.001 + i) * 0.35 + 0.5) * width).toFloat()
                    val radius = (i % 4 + 3) * 2f
                    particlePaint.alpha = (sin(timeMs * 0.004 + i) * 60 + 120).toInt().coerceIn(40, 200)
                    canvas.drawCircle(x, y, radius, particlePaint)
                }
            }
            VisualizerType.VINYL_SPINNER -> {
                val centerX = width / 2f
                val centerY = height * 0.32f
                val radius = min(width, height) * 0.22f
                val angle = (timeMs * 0.08f) % 360f

                canvas.save()
                canvas.rotate(angle, centerX, centerY)

                val vinylPaint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.FILL
                    color = Color.parseColor("#15161C")
                }
                canvas.drawCircle(centerX, centerY, radius, vinylPaint)

                // Groove rings
                val groovePaint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeWidth = 1.5f
                    color = Color.parseColor("#2E3142")
                }
                for (r in 1..4) {
                    canvas.drawCircle(centerX, centerY, radius * (0.35f + r * 0.12f), groovePaint)
                }

                // Center label
                val labelPaint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.FILL
                    color = primaryColor
                }
                canvas.drawCircle(centerX, centerY, radius * 0.32f, labelPaint)

                // Spindle hole
                canvas.drawCircle(centerX, centerY, radius * 0.08f, Paint().apply { color = Color.BLACK })

                canvas.restore()
            }
            VisualizerType.BASS_PULSE -> {
                val centerX = width / 2f
                val centerY = height * 0.40f
                val maxRadius = width * 0.45f

                val pulsePaint = Paint().apply {
                    isAntiAlias = true
                    style = Paint.Style.STROKE
                    strokeWidth = 4f
                }

                for (ring in 0..2) {
                    val ringProgress = ((beatPhase + ring * 0.33) % 1.0).toFloat()
                    val r = ringProgress * maxRadius
                    val alpha = ((1f - ringProgress) * 200).toInt().coerceIn(0, 255)
                    pulsePaint.color = primaryColor
                    pulsePaint.alpha = alpha
                    canvas.drawCircle(centerX, centerY, r, pulsePaint)
                }
            }
            VisualizerType.NONE -> {}
        }
    }

    private fun drawLyrics(canvas: Canvas, width: Int, height: Int, project: VideoProject, timeMs: Long) {
        val lyrics = project.lyrics
        if (lyrics.isEmpty()) return

        val typography = project.typography
        val activeLineIndex = lyrics.indexOfFirst { timeMs in it.startMs..it.endMs }

        val lineToDraw = if (activeLineIndex != -1) {
            lyrics[activeLineIndex]
        } else {
            // Find next upcoming line or last passed line
            lyrics.findLast { it.endMs < timeMs } ?: lyrics.firstOrNull()
        } ?: return

        val isCurrentlyActive = timeMs in lineToDraw.startMs..lineToDraw.endMs
        val progressInLine = if (isCurrentlyActive) {
            ((timeMs - lineToDraw.startMs).toFloat() / lineToDraw.durationMs).coerceIn(0f, 1f)
        } else {
            if (timeMs < lineToDraw.startMs) 0f else 1f
        }

        val baseFontSize = (typography.fontSizeSp * (width / 360f)).coerceAtLeast(2f)

        // Typeface
        val baseTypeface = when (typography.fontFamilyName.lowercase()) {
            "monospace" -> Typeface.MONOSPACE
            "serif" -> Typeface.SERIF
            "casual" -> Typeface.create("casual", Typeface.NORMAL)
            "cursive" -> Typeface.create("cursive", Typeface.NORMAL)
            else -> Typeface.SANS_SERIF
        }
        val style = when {
            typography.isBold && typography.isItalic -> Typeface.BOLD_ITALIC
            typography.isBold -> Typeface.BOLD
            typography.isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val finalTypeface = Typeface.create(baseTypeface, style)

        // Paint setup
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = finalTypeface
            textSize = baseFontSize
            letterSpacing = typography.letterSpacingSp * 0.05f
            textAlign = Paint.Align.CENTER
        }

        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = finalTypeface
            textSize = baseFontSize
            letterSpacing = typography.letterSpacingSp * 0.05f
            textAlign = Paint.Align.CENTER
            this.style = Paint.Style.STROKE
            strokeWidth = typography.strokeWidthDp * (width / 360f)
            color = parseColor(typography.strokeColorHex, Color.BLACK)
        }

        val centerX = width * (typography.positionXPercent / 100f)
        val baseCenterY = height * (typography.positionYPercent / 100f)
        val centerY = if (typography.lyricLayout == LyricLayoutMode.KARAOKE_TWO_LINE) baseCenterY - baseFontSize * 0.72f else baseCenterY

        // Apply dynamic transition animation transforms
        canvas.save()

        when (project.transitionEffect) {
            TextTransitionEffect.KINETIC_BOUNCE -> {
                if (isCurrentlyActive && progressInLine < 0.25f) {
                    val popPhase = progressInLine / 0.25f
                    val scale = 0.7f + sin(popPhase * Math.PI).toFloat() * 0.45f
                    canvas.scale(scale, scale, centerX, centerY)
                }
            }
            TextTransitionEffect.SLIDE_FADE_UP -> {
                if (isCurrentlyActive && progressInLine < 0.25f) {
                    val slide = (1f - (progressInLine / 0.25f)) * 40f
                    canvas.translate(0f, slide)
                }
            }
            TextTransitionEffect.WAVE_RIPPLE -> {
                val waveOffset = sin(timeMs * 0.008).toFloat() * 12f
                canvas.translate(0f, waveOffset)
            }
            TextTransitionEffect.BURST_ZOOM -> {
                if (isCurrentlyActive && progressInLine < 0.15f) {
                    val burstScale = 1.35f - (progressInLine / 0.15f) * 0.35f
                    canvas.scale(burstScale, burstScale, centerX, centerY)
                }
            }
            else -> {}
        }

        // Draw Line / Words
        if (project.transitionEffect == TextTransitionEffect.TYPEWRITER_GLITCH && isCurrentlyActive) {
            val charCount = (lineToDraw.text.length * (progressInLine * 1.3f).coerceIn(0f, 1f)).toInt()
            val visibleText = lineToDraw.text.take(charCount) + if (charCount < lineToDraw.text.length) "_" else ""
            drawStyledText(canvas, visibleText, centerX, centerY, textPaint, strokePaint, typography, parseColor(typography.primaryColorHex, Color.WHITE))
        } else if (project.transitionEffect == TextTransitionEffect.KARAOKE_SWEEP && isCurrentlyActive && lineToDraw.words.isNotEmpty()) {
            drawKaraokeWords(canvas, centerX, centerY, lineToDraw, timeMs, textPaint, strokePaint, typography, width)
        } else {
            val color = if (isCurrentlyActive) {
                parseColor(typography.highlightColorHex, Color.WHITE)
            } else {
                parseColor(typography.primaryColorHex, Color.LTGRAY)
            }
            drawStyledText(canvas, lineToDraw.text, centerX, centerY, textPaint, strokePaint, typography, color)
        }

        canvas.restore()

        // Upcoming lyric preview: compact single-line mode or full two-line karaoke mode.
        if (activeLineIndex != -1 && activeLineIndex + 1 < lyrics.size) {
            val nextLine = lyrics[activeLineIndex + 1]
            val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = finalTypeface
                textSize = if (typography.lyricLayout == LyricLayoutMode.KARAOKE_TWO_LINE) baseFontSize else baseFontSize * 0.65f
                color = if (typography.lyricLayout == LyricLayoutMode.KARAOKE_TWO_LINE) parseColor(typography.primaryColorHex, Color.WHITE) else Color.parseColor("#7A809E")
                alpha = if (typography.lyricLayout == LyricLayoutMode.KARAOKE_TWO_LINE) 220 else 130
                textAlign = Paint.Align.CENTER
            }
            val nextY = if (typography.lyricLayout == LyricLayoutMode.KARAOKE_TWO_LINE) baseCenterY + baseFontSize * 0.72f else centerY + baseFontSize * 1.6f
            canvas.drawText(nextLine.text, centerX, nextY, subPaint)
        }
    }

    private fun drawKaraokeWords(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        line: LyricLine,
        timeMs: Long,
        textPaint: Paint,
        strokePaint: Paint,
        typography: TypographyPreset,
        canvasWidth: Int
    ) {
        val totalText = line.text
        val totalWidth = textPaint.measureText(totalText)
        var cursorX = centerX - (totalWidth / 2f)

        val baseColor = parseColor(typography.primaryColorHex, Color.WHITE)
        val activeColor = parseColor(typography.highlightColorHex, Color.parseColor("#00F5D4"))

        for (word in line.words) {
            val wordString = word.text + " "
            val wordW = textPaint.measureText(wordString)
            val isWordActive = timeMs in word.startMs..word.endMs
            val hasWordPassed = timeMs > word.endMs

            val wordColor = when {
                isWordActive -> activeColor
                hasWordPassed -> activeColor
                else -> baseColor
            }

            textPaint.color = wordColor
            val wordCenterX = cursorX + (wordW / 2f)

            // Neon glow for active word
            if (isWordActive && typography.glowRadiusDp > 0f) {
                textPaint.setShadowLayer(
                    typography.glowRadiusDp * 2f,
                    0f, 0f,
                    parseColor(typography.highlightColorHex, Color.CYAN)
                )
            } else {
                textPaint.clearShadowLayer()
            }

            // Stroke
            if (typography.strokeWidthDp > 0f) {
                canvas.drawText(wordString, wordCenterX, centerY, strokePaint)
            }
            canvas.drawText(wordString, wordCenterX, centerY, textPaint)

            cursorX += wordW
        }
    }

    private fun drawStyledText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        textPaint: Paint,
        strokePaint: Paint,
        typography: TypographyPreset,
        fillColor: Int
    ) {
        // Selectable visual font finish.
        when (typography.visualEffect) {
            FontVisualEffect.NEON_GLOW -> textPaint.setShadowLayer(typography.glowRadiusDp * 2.5f, 0f, 0f, parseColor(typography.highlightColorHex, Color.CYAN))
            FontVisualEffect.SOFT_SHADOW -> textPaint.setShadowLayer(typography.glowRadiusDp * 1.5f, 2f, 3f, Color.BLACK)
            else -> textPaint.clearShadowLayer()
        }

        if (typography.visualEffect == FontVisualEffect.DOUBLE_OUTLINE) {
            val outer = Paint(strokePaint).apply {
                strokeWidth = typography.strokeWidthDp * 2.4f * (canvas.width / 360f)
                color = Color.parseColor("#FF00C8")
            }
            canvas.drawText(text, x, y, outer)
        }
        if (typography.strokeWidthDp > 0f) {
            canvas.drawText(text, x, y, strokePaint)
        }

        // Draw Fill
        textPaint.color = fillColor
        canvas.drawText(text, x, y, textPaint)
    }

    private fun loadWatermarkBitmap(): Bitmap {
        val encoded = context.assets.open("lyricmakerpro_watermark.png.b64")
            .bufferedReader().use { it.readText() }
        val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) {
            "Unable to decode LyricMakerPro watermark asset"
        }
    }

    private fun drawWatermark(canvas: Canvas, width: Int, height: Int, bitmap: Bitmap) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return
        val targetWidth = width * 0.10f
        val targetHeight = targetWidth * bitmap.height / bitmap.width
        val right = width * 0.97f
        val bottom = height * 0.965f
        val destination = RectF(right - targetWidth, bottom - targetHeight, right, bottom)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { alpha = 179 }
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    private fun parseColor(hex: String, default: Int): Int {
        return try {
            Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
        } catch (_: Exception) {
            default
        }
    }
}
