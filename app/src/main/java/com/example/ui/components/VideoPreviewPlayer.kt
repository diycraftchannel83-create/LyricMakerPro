package com.example.ui.components

import android.net.Uri
import android.widget.VideoView
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint as AndroidPaint
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.*
import com.example.ui.theme.*
import kotlin.math.*

@Composable
fun VideoPreviewPlayer(
    project: VideoProject,
    playheadMs: Long,
    isPlaying: Boolean,
    spectrumBands: FloatArray,
    bassEnergy: Float,
    onTogglePlay: () -> Unit,
    onSeek: (Long) -> Unit,
    onTextPositionChange: (Float, Float) -> Unit,
    onTextScaleChange: (Float) -> Unit,
    onTypographyChange: (TypographyPreset) -> Unit,
    onRatioChange: (CanvasRatio) -> Unit,
    modifier: Modifier = Modifier
) {
    var showRatioMenu by remember { mutableStateOf(false) }
    var showStyleDialog by remember { mutableStateOf(false) }
    val latestPositionX by rememberUpdatedState(project.typography.positionXPercent)
    val latestPositionY by rememberUpdatedState(project.typography.positionYPercent)
    val latestOnPositionChange by rememberUpdatedState(onTextPositionChange)
    val latestOnScaleChange by rememberUpdatedState(onTextScaleChange)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioBackground),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top Toolbar: Aspect Ratio Selector & Timecode
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Aspect Ratio Dropdown Pill
            Box {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = StudioSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder),
                    modifier = Modifier
                        .clickable { showRatioMenu = true }
                        .testTag("ratio_selector_button")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AspectRatio,
                            contentDescription = "Canvas Ratio",
                            tint = NeonCyan,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = project.canvasRatio.label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = showRatioMenu,
                    onDismissRequest = { showRatioMenu = false },
                    modifier = Modifier.background(StudioSurface)
                ) {
                    CanvasRatio.entries.forEach { ratio ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        text = "${ratio.label}  •  ${ratio.iconDescription}",
                                        fontWeight = if (ratio == project.canvasRatio) FontWeight.Bold else FontWeight.Normal,
                                        color = if (ratio == project.canvasRatio) NeonCyan else TextPrimary,
                                        fontSize = 13.sp
                                    )
                                }
                            },
                            onClick = {
                                onRatioChange(ratio)
                                showRatioMenu = false
                            }
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = StudioSurfaceVariant,
                border = androidx.compose.foundation.BorderStroke(1.dp, NeonMagenta.copy(alpha = 0.7f)),
                modifier = Modifier.clickable { showStyleDialog = true }.testTag("preview_style_button")
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.TextFields, contentDescription = null, tint = NeonMagenta, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Style", color = TextPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Real-time timecode badge
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = StudioSurfaceVariant.copy(alpha = 0.8f),
                border = androidx.compose.foundation.BorderStroke(0.5.dp, StudioCardBorder)
            ) {
                Text(
                    text = "${formatTime(playheadMs)} / ${formatTime(project.durationMs)}",
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        if (showStyleDialog) {
            AlertDialog(
                onDismissRequest = { showStyleDialog = false },
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("TEXT STUDIO", color = NeonCyan, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp)
                        Text("Lyric Typography", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("Atur tampilan teks untuk seluruh baris lirik", color = TextSecondary, fontSize = 12.sp)
                    }
                },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            color = StudioSurfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("UKURAN HURUF", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                    Text("${project.typography.fontSizeSp.toInt()} sp", color = NeonCyan, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                }
                                Slider(
                                    value = project.typography.fontSizeSp.coerceIn(2f, 96f),
                                    onValueChange = { onTypographyChange(project.typography.copy(fontSizeSp = it)) },
                                    valueRange = 2f..96f
                                )
                                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text("2 sp • Sangat kecil", color = TextSecondary, fontSize = 10.sp)
                                    Text("96 sp • Besar", color = TextSecondary, fontSize = 10.sp)
                                }
                            }
                        }

                        Surface(color = StudioSurfaceVariant, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("JENIS HURUF", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                listOf("SansSerif", "Serif", "Monospace", "Casual", "Cursive").chunked(3).forEach { familyRow ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        familyRow.forEach { family ->
                                            FilterChip(
                                                selected = project.typography.fontFamilyName == family,
                                                onClick = { onTypographyChange(project.typography.copy(fontFamilyName = family)) },
                                                label = { Text(family, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                                androidx.compose.material3.HorizontalDivider(color = StudioCardBorder)
                                Text("GAYA HURUF", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = project.typography.isBold, onClick = { onTypographyChange(project.typography.copy(isBold = !project.typography.isBold)) }, label = { Text("B  Bold") })
                                    FilterChip(selected = project.typography.isItalic, onClick = { onTypographyChange(project.typography.copy(isItalic = !project.typography.isItalic)) }, label = { Text("I  Italic") })
                                }
                            }
                        }

                        Surface(color = StudioSurfaceVariant, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("EFEK VISUAL", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                FontVisualEffect.entries.chunked(2).forEach { effectRow ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        effectRow.forEach { effect ->
                                            FilterChip(
                                                selected = project.typography.visualEffect == effect,
                                                onClick = { onTypographyChange(project.typography.copy(visualEffect = effect)) },
                                                label = { Text(effect.label, fontSize = 11.sp) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Surface(color = StudioSurfaceVariant, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                Text("TATA LETAK KARAOKE", color = TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                                LyricLayoutMode.entries.forEach { mode ->
                                    FilterChip(
                                        selected = project.typography.lyricLayout == mode,
                                        onClick = { onTypographyChange(project.typography.copy(lyricLayout = mode)) },
                                        label = { Text(mode.label, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showStyleDialog = false }) {
                        Text("Selesai", color = NeonCyan, fontWeight = FontWeight.Bold)
                    }
                },
                containerColor = StudioSurface,
                shape = RoundedCornerShape(24.dp)
            )
        }

        // The Viewport Frame
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(min = 220.dp, max = 340.dp)
                .padding(horizontal = 16.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(project.canvasRatio.aspectRatio)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.5.dp, StudioCardBorder, RoundedCornerShape(14.dp))
                    .shadow(12.dp, RoundedCornerShape(14.dp))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (size.width > 0 && size.height > 0) {
                                val currentX = latestPositionX
                                val currentY = latestPositionY
                                val proposedX = (currentX + pan.x / size.width * 100f).coerceIn(5f, 95f)
                                val proposedY = (currentY + pan.y / size.height * 100f).coerceIn(5f, 95f)
                                val snapX = if (kotlin.math.abs(proposedX - 50f) <= 2.5f) 50f else proposedX
                                val snapY = if (kotlin.math.abs(proposedY - 50f) <= 2.5f) 50f else proposedY
                                latestOnPositionChange(snapX - currentX, snapY - currentY)
                                if (zoom != 1f) latestOnScaleChange(zoom)
                            }
                        }
                    }
                    .testTag("preview_canvas_container"),
                contentAlignment = Alignment.Center
            ) {
                val activeMedia = project.mediaClips.lastOrNull {
                    playheadMs >= it.startMs && playheadMs < it.endMs
                }

                // Imported media is rendered behind the lyric/effect canvas.
                when (activeMedia?.type) {
                    MediaClipType.IMAGE -> {
                        AsyncImage(
                            model = Uri.parse(activeMedia.uri),
                            contentDescription = activeMedia.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    MediaClipType.VIDEO -> {
                        key(activeMedia.id) {
                            AndroidView(
                                factory = { context ->
                                    VideoView(context).apply {
                                        setVideoURI(Uri.parse(activeMedia.uri))
                                        setOnPreparedListener { player ->
                                            player.isLooping = true
                                            seekTo((playheadMs - activeMedia.startMs).toInt().coerceAtLeast(0))
                                            if (isPlaying) start()
                                        }
                                    }
                                },
                                update = { player ->
                                    val target = (playheadMs - activeMedia.startMs).toInt().coerceAtLeast(0)
                                    if (kotlin.math.abs(player.currentPosition - target) > 700) {
                                        player.seekTo(target)
                                    }
                                    if (isPlaying && !player.isPlaying) player.start()
                                    if (!isPlaying && player.isPlaying) player.pause()
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    null -> Unit
                }

                // Real-time Canvas Rendering
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val canvasWidth = size.width.toInt()
                    val canvasHeight = size.height.toInt()
                    if (canvasWidth <= 0 || canvasHeight <= 0) return@Canvas

                    drawInto(
                        drawScope = this,
                        project = project,
                        timeMs = playheadMs,
                        width = canvasWidth,
                        height = canvasHeight,
                        spectrumBands = spectrumBands,
                        bassEnergy = bassEnergy,
                        drawBackground = activeMedia == null
                    )
                }

                // Center alignment guides appear while lyric position is snapped to canvas center.
                val verticalAligned = kotlin.math.abs(project.typography.positionXPercent - 50f) < 0.01f
                val horizontalAligned = kotlin.math.abs(project.typography.positionYPercent - 50f) < 0.01f
                if (verticalAligned || horizontalAligned) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val guidePaintColor = NeonCyan
                        if (verticalAligned) {
                            drawLine(
                                color = guidePaintColor,
                                start = androidx.compose.ui.geometry.Offset(size.width / 2f, 0f),
                                end = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }
                        if (horizontalAligned) {
                            drawLine(
                                color = guidePaintColor,
                                start = androidx.compose.ui.geometry.Offset(0f, size.height / 2f),
                                end = androidx.compose.ui.geometry.Offset(size.width, size.height / 2f),
                                strokeWidth = 1.5.dp.toPx()
                            )
                        }
                    }
                }

            }
        }

        // Scrub Slider & Mini Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            IconButton(
                onClick = onTogglePlay,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(StudioSurfaceVariant)
                    .testTag("play_pause_button")
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = NeonCyan,
                    modifier = Modifier.size(22.dp)
                )
            }

            // Scrub slider
            Slider(
                value = playheadMs.toFloat().coerceIn(0f, project.durationMs.toFloat()),
                onValueChange = { onSeek(it.toLong()) },
                valueRange = 0f..project.durationMs.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = NeonMagenta,
                    activeTrackColor = NeonCyan,
                    inactiveTrackColor = StudioSurfaceVariant
                ),
                modifier = Modifier
                    .weight(1f)
                    .testTag("timeline_scrub_slider")
            )
        }
    }
}

private fun drawInto(
    drawScope: androidx.compose.ui.graphics.drawscope.DrawScope,
    project: VideoProject,
    timeMs: Long,
    width: Int,
    height: Int,
    spectrumBands: FloatArray,
    bassEnergy: Float,
    drawBackground: Boolean = true
) {
    val native = drawScope.drawContext.canvas.nativeCanvas

    // 1. Background
    val bg = project.background
    val bgPaint = AndroidPaint()
    if (drawBackground) when (bg.type) {
        BackgroundType.SOLID -> {
            native.drawColor(AndroidColor.parseColor(bg.primaryColorHex))
        }
        BackgroundType.CYBER_GRID -> {
            native.drawColor(AndroidColor.parseColor(bg.primaryColorHex))
            val gridPaint = AndroidPaint().apply {
                color = AndroidColor.parseColor(bg.accentColorHex)
                alpha = 45
                strokeWidth = 1.5f
                style = AndroidPaint.Style.STROKE
            }
            val step = width / 10f
            var x = 0f
            while (x <= width) {
                native.drawLine(x, 0f, x, height.toFloat(), gridPaint)
                x += step
            }
            val scrollY = ((timeMs * 0.05f) % step)
            var y = scrollY
            while (y <= height) {
                native.drawLine(0f, y, width.toFloat(), y, gridPaint)
                y += step
            }
        }
        else -> {
            val c1 = try { AndroidColor.parseColor(bg.primaryColorHex) } catch (_: Exception) { AndroidColor.BLACK }
            val c2 = try { AndroidColor.parseColor(bg.secondaryColorHex) } catch (_: Exception) { AndroidColor.DKGRAY }
            val c3 = try { AndroidColor.parseColor(bg.accentColorHex) } catch (_: Exception) { AndroidColor.MAGENTA }

            bgPaint.shader = LinearGradient(
                0f, 0f, width.toFloat(), height.toFloat(),
                intArrayOf(c1, c2, c3),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
            native.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

            val darkOverlay = AndroidPaint().apply {
                color = AndroidColor.argb((bg.darkOverlayOpacity * 255).toInt(), 0, 0, 0)
            }
            native.drawRect(0f, 0f, width.toFloat(), height.toFloat(), darkOverlay)
        }
    }

    // 2. Visualizer Overlay
    val visColor = try { AndroidColor.parseColor(project.visualizerColorHex) } catch (_: Exception) { AndroidColor.CYAN }
    when (project.visualizer) {
        VisualizerType.BARS_SPECTRUM -> {
            val barCount = 16
            val spacing = width * 0.012f
            val totalW = width * 0.88f
            val barW = (totalW - (spacing * (barCount - 1))) / barCount
            val startX = (width - totalW) / 2f
            val baseY = height * 0.82f
            val maxH = height * 0.16f

            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply { style = AndroidPaint.Style.FILL }

            for (i in 0 until barCount) {
                val bandLevel = spectrumBands.getOrElse(i) { 0.2f }
                val barH = (bandLevel * maxH).coerceAtLeast(6f)
                val x = startX + i * (barW + spacing)
                paint.shader = LinearGradient(x, baseY - barH, x, baseY, intArrayOf(visColor, AndroidColor.MAGENTA), null, Shader.TileMode.CLAMP)
                native.drawRoundRect(x, baseY - barH, x + barW, baseY, barW / 2f, barW / 2f, paint)
            }
        }
        VisualizerType.CIRCULAR_WAVE -> {
            val cx = width / 2f
            val cy = height * 0.35f
            val baseR = min(width, height) * 0.18f
            val r = baseR * (1f + (bassEnergy - 0.15f).coerceAtLeast(0f) * 0.15f)

            val paint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.STROKE
                strokeWidth = 4f
                color = visColor
                alpha = 220
            }
            native.drawCircle(cx, cy, r, paint)

            val outerPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.STROKE
                strokeWidth = 2f
                color = AndroidColor.parseColor("#FF007F")
                alpha = (bassEnergy * 150).toInt().coerceIn(30, 255)
            }
            native.drawCircle(cx, cy, r * 1.25f, outerPaint)
        }
        VisualizerType.FLOATING_PARTICLES -> {
            val pPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                color = visColor
            }
            for (i in 0 until 18) {
                val speed = (i % 4 + 2) * 0.04f
                val y = (height - ((timeMs * speed + i * 140) % height)).toFloat()
                val x = ((sin(timeMs * 0.001 + i) * 0.38 + 0.5) * width).toFloat()
                val r = (i % 3 + 2) * 1.5f
                pPaint.alpha = (sin(timeMs * 0.004 + i) * 60 + 130).toInt().coerceIn(40, 220)
                native.drawCircle(x, y, r, pPaint)
            }
        }
        VisualizerType.OSCILLOSCOPE -> {
            val wavePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.STROKE
                strokeWidth = 4f
                color = visColor
            }
            val path = android.graphics.Path()
            val centerY = height * 0.78f
            val step = width / 40f
            path.moveTo(0f, centerY)
            for (i in 0..40) {
                val x = i * step
                val phase = (x / width) * 4 * Math.PI + (timeMs * 0.008)
                val amp = (height * 0.035f) * (1f + bassEnergy * 0.6f)
                val y = centerY + sin(phase).toFloat() * amp
                path.lineTo(x, y)
            }
            native.drawPath(path, wavePaint)
        }
        VisualizerType.BASS_PULSE -> {
            val cx = width / 2f
            val cy = height * 0.40f
            val maxR = width * 0.4f
            val pulsePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.STROKE
                strokeWidth = 3f
                color = visColor
            }
            val beatPhase = (timeMs % 500) / 500f
            val r = beatPhase * maxR
            pulsePaint.alpha = ((1f - beatPhase) * 220).toInt().coerceIn(0, 255)
            native.drawCircle(cx, cy, r, pulsePaint)
        }
        VisualizerType.VINYL_SPINNER -> {
            val cx = width / 2f
            val cy = height * 0.32f
            val r = min(width, height) * 0.22f
            val angle = (timeMs * 0.08f) % 360f

            native.save()
            native.rotate(angle, cx, cy)
            val vPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                color = AndroidColor.parseColor("#15161C")
            }
            native.drawCircle(cx, cy, r, vPaint)
            val gPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.STROKE
                strokeWidth = 1f
                color = AndroidColor.parseColor("#2E3142")
            }
            native.drawCircle(cx, cy, r * 0.6f, gPaint)
            native.drawCircle(cx, cy, r * 0.8f, gPaint)

            val lPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                style = AndroidPaint.Style.FILL
                color = visColor
            }
            native.drawCircle(cx, cy, r * 0.32f, lPaint)
            native.restore()
        }
        VisualizerType.NONE -> {}
    }

    // 3. Lyrics
    val lyrics = project.lyrics
    if (lyrics.isNotEmpty()) {
        val typography = project.typography
        val activeLineIndex = lyrics.indexOfFirst { timeMs in it.startMs..it.endMs }
        val lineToDraw = if (activeLineIndex != -1) {
            lyrics[activeLineIndex]
        } else {
            lyrics.findLast { it.endMs < timeMs } ?: lyrics.firstOrNull()
        }

        if (lineToDraw != null) {
            val isCurrentlyActive = timeMs in lineToDraw.startMs..lineToDraw.endMs
            val progressInLine = if (isCurrentlyActive) {
                ((timeMs - lineToDraw.startMs).toFloat() / lineToDraw.durationMs).coerceIn(0f, 1f)
            } else {
                if (timeMs < lineToDraw.startMs) 0f else 1f
            }

            val fontSize = (typography.fontSizeSp * (width / 360f)).coerceAtLeast(24f)
            val tf = when (typography.fontFamilyName.lowercase()) {
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

            val textPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(tf, style)
                textSize = fontSize
                textAlign = AndroidPaint.Align.CENTER
                when (typography.visualEffect) {
                    FontVisualEffect.NEON_GLOW -> setShadowLayer(typography.glowRadiusDp * (width / 360f), 0f, 0f, AndroidColor.parseColor("#FF00C8"))
                    FontVisualEffect.SOFT_SHADOW -> setShadowLayer(typography.glowRadiusDp * (width / 360f), 2f, 3f, AndroidColor.BLACK)
                    else -> {}
                }
            }
            val strokePaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.create(tf, style)
                textSize = fontSize
                textAlign = AndroidPaint.Align.CENTER
                this.style = AndroidPaint.Style.STROKE
                strokeWidth = typography.strokeWidthDp * (width / 360f)
                color = try { AndroidColor.parseColor(typography.strokeColorHex) } catch (_: Exception) { AndroidColor.BLACK }
            }

            val cx = width * (typography.positionXPercent / 100f)
            val baseCy = height * (typography.positionYPercent / 100f)
            val cy = if (typography.lyricLayout == LyricLayoutMode.KARAOKE_TWO_LINE) baseCy - fontSize * 0.72f else baseCy

            native.save()

            // Dynamic Motion Transitions
            when (project.transitionEffect) {
                TextTransitionEffect.KINETIC_BOUNCE -> {
                    if (isCurrentlyActive && progressInLine < 0.25f) {
                        val s = 0.75f + sin((progressInLine / 0.25f) * Math.PI).toFloat() * 0.4f
                        native.scale(s, s, cx, cy)
                    }
                }
                TextTransitionEffect.SLIDE_FADE_UP -> {
                    if (isCurrentlyActive && progressInLine < 0.25f) {
                        val slide = (1f - (progressInLine / 0.25f)) * 30f
                        native.translate(0f, slide)
                    }
                }
                TextTransitionEffect.WAVE_RIPPLE -> {
                    val wave = sin(timeMs * 0.008).toFloat() * 10f
                    native.translate(0f, wave)
                }
                TextTransitionEffect.BURST_ZOOM -> {
                    if (isCurrentlyActive && progressInLine < 0.15f) {
                        val z = 1.3f - (progressInLine / 0.15f) * 0.3f
                        native.scale(z, z, cx, cy)
                    }
                }
                else -> {}
            }

            val primaryC = try { AndroidColor.parseColor(typography.primaryColorHex) } catch (_: Exception) { AndroidColor.WHITE }
            val highlightC = try { AndroidColor.parseColor(typography.highlightColorHex) } catch (_: Exception) { AndroidColor.CYAN }
            if (typography.visualEffect == FontVisualEffect.DOUBLE_OUTLINE) {
                val outer = AndroidPaint(strokePaint).apply {
                    strokeWidth = typography.strokeWidthDp * (width / 360f) * 2.4f
                    color = AndroidColor.parseColor("#FF00C8")
                }
                native.drawText(lineToDraw.text, cx, cy, outer)
            }

            if (project.transitionEffect == TextTransitionEffect.TYPEWRITER_GLITCH && isCurrentlyActive) {
                val count = (lineToDraw.text.length * (progressInLine * 1.3f).coerceIn(0f, 1f)).toInt()
                val txt = lineToDraw.text.take(count) + if (count < lineToDraw.text.length) "_" else ""
                textPaint.color = primaryC
                if (typography.strokeWidthDp > 0f) native.drawText(txt, cx, cy, strokePaint)
                native.drawText(txt, cx, cy, textPaint)
            } else if (project.transitionEffect == TextTransitionEffect.KARAOKE_SWEEP && isCurrentlyActive && lineToDraw.words.isNotEmpty()) {
                val totalW = textPaint.measureText(lineToDraw.text)
                var curX = cx - (totalW / 2f)
                for (w in lineToDraw.words) {
                    val wStr = w.text + " "
                    val wW = textPaint.measureText(wStr)
                    val active = timeMs in w.startMs..w.endMs
                    val passed = timeMs > w.endMs
                    textPaint.color = if (active || passed) highlightC else primaryC
                    val wCx = curX + (wW / 2f)
                    if (typography.strokeWidthDp > 0f) native.drawText(wStr, wCx, cy, strokePaint)
                    native.drawText(wStr, wCx, cy, textPaint)
                    curX += wW
                }
            } else {
                textPaint.color = if (isCurrentlyActive) highlightC else primaryC
                if (typography.strokeWidthDp > 0f) native.drawText(lineToDraw.text, cx, cy, strokePaint)
                native.drawText(lineToDraw.text, cx, cy, textPaint)
            }

            if (typography.lyricLayout == LyricLayoutMode.KARAOKE_TWO_LINE) {
                val nextLine = lyrics.firstOrNull { it.startMs > lineToDraw.endMs }
                if (nextLine != null) {
                    textPaint.color = primaryC
                    textPaint.alpha = 210
                    strokePaint.alpha = 150
                    val secondY = baseCy + fontSize * 0.72f
                    if (typography.strokeWidthDp > 0f) native.drawText(nextLine.text, cx, secondY, strokePaint)
                    native.drawText(nextLine.text, cx, secondY, textPaint)
                    textPaint.alpha = 255
                    strokePaint.alpha = 255
                }
            }
            native.restore()
        }
    }

    // 4. Watermark
    if (project.showWatermark && project.watermarkText.isNotBlank()) {
        val wmPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
            textSize = (width * 0.035f).coerceAtLeast(16f)
            color = AndroidColor.WHITE
            alpha = 100
            textAlign = AndroidPaint.Align.RIGHT
        }
        native.drawText(project.watermarkText, width * 0.94f, height * 0.95f, wmPaint)
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    val tenth = (ms % 1000) / 100
    return String.format("%02d:%02d.%01d", min, sec, tenth)
}
