package com.winsmediatama.lyricmakerpro.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winsmediatama.lyricmakerpro.model.LyricLine
import com.winsmediatama.lyricmakerpro.model.MediaClip
import com.winsmediatama.lyricmakerpro.model.MediaClipType
import com.winsmediatama.lyricmakerpro.model.VideoProject
import com.winsmediatama.lyricmakerpro.ui.theme.*

@Composable
fun MultiTrackTimeline(
    project: VideoProject,
    playheadMs: Long,
    selectedLineId: String?,
    onSelectLine: (String?) -> Unit,
    onSeek: (Long) -> Unit,
    onNudge: (Long) -> Unit,
    onMoveLine: (Long) -> Unit,
    onAdjustStart: (Long) -> Unit,
    onAdjustDuration: (Long) -> Unit,
    onTextChange: (String, String) -> Unit,
    onAddLine: () -> Unit,
    onDeleteLine: () -> Unit,
    onOpenTapToSync: () -> Unit,
    onMoveMediaClip: (String, Long) -> Unit = { _, _ -> },
    onResizeMediaClip: (String, Long) -> Unit = { _, _ -> },
    onTrimMediaClipStart: (String, Long) -> Unit = { _, _ -> },
    videoAudioMuted: Boolean = false,
    onToggleVideoMute: () -> Unit = {},
    onRemoveMediaClip: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Zoom factor: pixels per millisecond
    var pxPerMs by remember { mutableFloatStateOf(0.065f) } // pixels per millisecond
    // Every timeline layer must use the same duration-to-pixel scale. Do not add a
    // trailing width allowance: it stretches the waveform beyond the song end.
    val totalTimelineWidthDp = (project.durationMs.coerceAtLeast(1L) * pxPerMs).dp.coerceAtLeast(1.dp)
    val minZoom = 0.0005f
    val maxZoom = 0.18f

    val scrollState = rememberScrollState()
    var editingLineId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioSurface)
            .border(1.dp, StudioCardBorder, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .padding(top = 10.dp, bottom = 4.dp)
    ) {
        // Timeline Header & Quick Editing Toolbar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ViewTimeline,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = com.winsmediatama.lyricmakerpro.AppLanguage.text("Timeline"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = TextPrimary
                )
            }

            // Quick Nudge & Zoom Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Global mute/unmute for every video clip in the project.
                IconButton(
                    onClick = onToggleVideoMute,
                    modifier = Modifier.size(28.dp).testTag("timeline_video_mute")
                ) {
                    Icon(
                        imageVector = if (videoAudioMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp,
                        contentDescription = if (videoAudioMuted) "Unmute all videos" else "Mute all videos",
                        tint = if (videoAudioMuted) NeonMagenta else NeonCyan,
                        modifier = Modifier.size(17.dp)
                    )
                }

                IconButton(
                    onClick = { pxPerMs = (pxPerMs * 0.65f).coerceAtLeast(minZoom) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ZoomOut, contentDescription = "Zoom Out", tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
                IconButton(
                    onClick = { pxPerMs = (pxPerMs * 1.35f).coerceAtMost(maxZoom) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(Icons.Default.ZoomIn, contentDescription = "Zoom In", tint = TextSecondary, modifier = Modifier.size(16.dp))
                }
                TextButton(
                    onClick = { pxPerMs = (360f / project.durationMs.coerceAtLeast(1L)).coerceIn(minZoom, maxZoom) },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.height(28.dp).testTag("timeline_fit_button")
                ) {
                    Text(com.winsmediatama.lyricmakerpro.AppLanguage.text("Fit"), color = NeonCyan, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }

                // Tap to sync shortcut pill
                Button(
                    onClick = onOpenTapToSync,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                    contentPadding = PaddingValues(horizontal = 7.dp, vertical = 3.dp),
                    modifier = Modifier
                        .height(28.dp)
                        .testTag("tap_to_sync_quick_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(com.winsmediatama.lyricmakerpro.AppLanguage.text("Sync"), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // Horizontal Scrollable Multi-Track Canvas. Keep the playhead in view
        // during playback/seeking so the ruler can be followed all the way to song end.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(166.dp)
                .padding(vertical = 2.dp)
        ) {
            val density = LocalDensity.current
            val viewportWidthPx = constraints.maxWidth
            LaunchedEffect(playheadMs, pxPerMs, viewportWidthPx, project.durationMs, scrollState.maxValue) {
                val playheadPx = with(density) { (playheadMs * pxPerMs).dp.roundToPx() }
                val visibleStart = scrollState.value
                val visibleEnd = visibleStart + viewportWidthPx
                if (playheadPx < visibleStart || playheadPx > visibleEnd) {
                    val targetScroll = if (playheadMs >= project.durationMs) {
                        scrollState.maxValue
                    } else {
                        (playheadPx - (viewportWidthPx * 0.65f).toInt())
                            .coerceIn(0, scrollState.maxValue)
                    }
                    scrollState.animateScrollTo(targetScroll)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .horizontalScroll(scrollState)
            ) {
            Box(
                modifier = Modifier
                    .width(totalTimelineWidthDp)
                    .fillMaxHeight()
                     .pointerInput(project.durationMs, pxPerMs) {
                        detectTapGestures { offset ->
                            val clickedMs = (offset.x / pxPerMs).toLong().coerceIn(0L, project.durationMs)
                            onSeek(clickedMs)
                        }
                    }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Time Ruler
                    TimeRuler(
                        durationMs = project.durationMs,
                        pxPerMs = pxPerMs,
                        modifier = Modifier
                            .fillMaxWidth()
                             .height(28.dp)
                    )

                    Spacer(Modifier.height(4.dp))

                    // Track 1: Audio Waveform Track
                    AudioTrackRow(
                        project = project,
                        pxPerMs = pxPerMs,
                        onSeek = onSeek,
                        modifier = Modifier
                            .fillMaxWidth()
                             .height(28.dp)
                    )

                    Spacer(Modifier.height(2.dp))

                    // Track 2: Imported photo/video clips, placed above lyrics.
                    MediaClipsTrackRow(
                        clips = project.mediaClips,
                        pxPerMs = pxPerMs,
                        onSeek = onSeek,
                        onMoveClip = onMoveMediaClip,
                        onResizeClip = onResizeMediaClip,
                        onTrimLeftClip = onTrimMediaClipStart,
                        onRemoveClip = onRemoveMediaClip,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp)
                    )

                    Spacer(Modifier.height(2.dp))

                    // Track 3: Lyrics Blocks Track
                    LyricsTrackRow(
                        lyrics = project.lyrics,
                        playheadMs = playheadMs,
                        onSeek = onSeek,
                        selectedLineId = selectedLineId,
                        pxPerMs = pxPerMs,
                        onSelectLine = onSelectLine,
                        onEditLine = { id ->
                            onSelectLine(id)
                            editingLineId = id
                        },
                        onAdjustStart = onAdjustStart,
                        onAdjustDuration = onAdjustDuration,
                        modifier = Modifier
                            .fillMaxWidth()
                             .height(42.dp)
                    )


                }

                // Vertical Playhead Scrub Needle
                val playheadXDp = (playheadMs * pxPerMs).dp
                Box(
                    modifier = Modifier
                        .offset(x = playheadXDp - 1.dp)
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(TimelinePlayhead)
                )

                // Playhead Drag Knob at Top
                Box(
                    modifier = Modifier
                        .offset(x = playheadXDp - 6.dp, y = 0.dp)
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(TimelinePlayhead)
                        .border(1.dp, Color.White, CircleShape)
                )
            }
            }
        }


    }

    // Floating lyric editor dialog: stays separate from the bottom navigation area.
    val editingLine = project.lyrics.find { it.id == editingLineId }
    if (editingLine != null) {
        AlertDialog(
            onDismissRequest = { editingLineId = null },
            title = {
                Text(
                    text = "Edit Lyric",
                    color = TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                OutlinedTextField(
                    value = editingLine.text,
                    onValueChange = { text -> onTextChange(editingLine.id, text) },
                    modifier = Modifier.fillMaxWidth().testTag("lyric_popup_editor"),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextPrimary
                    ),
                    placeholder = { Text("Correct lyric text") },
                    singleLine = false,
                    minLines = 2,
                    maxLines = 5,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NeonCyan,
                        unfocusedBorderColor = StudioCardBorder,
                        cursorColor = NeonCyan
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = { editingLineId = null }) {
                    Text("Done", color = NeonCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingLineId = null }) {
                    Text("Close", color = TextSecondary)
                }
            },
            containerColor = StudioSurface,
            titleContentColor = TextPrimary,
            textContentColor = TextPrimary
        )
    }
}


@Composable
private fun MediaClipsTrackRow(
    clips: List<MediaClip>,
    pxPerMs: Float,
    onSeek: (Long) -> Unit,
    onMoveClip: (String, Long) -> Unit,
    onResizeClip: (String, Long) -> Unit,
    onTrimLeftClip: (String, Long) -> Unit,
    onRemoveClip: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedClipId by remember { mutableStateOf<String?>(null) }
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF101722))
                .border(1.dp, StudioCardBorder, RoundedCornerShape(8.dp))
        ) {
            clips.sortedBy { it.startMs }.forEach { clip ->
                val left = (clip.startMs * pxPerMs).dp
                val clipWidth = (clip.durationMs * pxPerMs).dp.coerceAtLeast(28.dp)
                val tint = if (clip.type == MediaClipType.VIDEO) Color(0xFF6D45B5) else Color(0xFF147B76)
                val selected = selectedClipId == clip.id
                Box(
                    modifier = Modifier
                        .offset(x = left, y = 2.dp)
                        .width(clipWidth)
                        .fillMaxHeight()
                        .padding(end = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(tint)
                        .border(if (selected) 2.dp else 1.dp, if (selected) NeonCyan else if (clip.type == MediaClipType.VIDEO) NeonMagenta else NeonCyan, RoundedCornerShape(6.dp))
                        .pointerInput(clip.id, pxPerMs) {
                            // Accumulate fractional milliseconds so short finger movements
                            // are not discarded by Long rounding at the current zoom level.
                            var dragRemainderMs = 0f
                            detectDragGestures(
                                onDragStart = {
                                    selectedClipId = clip.id
                                    dragRemainderMs = 0f
                                },
                                onDragEnd = { dragRemainderMs = 0f },
                                onDragCancel = { dragRemainderMs = 0f },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    dragRemainderMs += dragAmount.x / pxPerMs
                                    val deltaMs = dragRemainderMs.toLong()
                                    if (deltaMs != 0L) {
                                        onMoveClip(clip.id, deltaMs)
                                        dragRemainderMs -= deltaMs
                                    }
                                }
                            )
                        }
                        .clickable { selectedClipId = clip.id; onSeek(clip.startMs) }
                        .padding(start = 6.dp, end = 12.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (clip.type == MediaClipType.VIDEO) Icons.Default.Videocam else Icons.Default.Image,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            if (clipWidth >= 54.dp) {
                                Text(
                                    clip.displayName,
                                    color = Color.White,
                                    fontSize = if (clipWidth < 84.dp) 7.sp else 9.sp,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    String.format(java.util.Locale.US, "%.1fs", clip.durationMs / 1000f),
                                    color = Color.White.copy(alpha = 0.75f),
                                    fontSize = 7.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .width(12.dp)
                            .fillMaxHeight()
                            .background(Color.White.copy(alpha = 0.28f))
                            .pointerInput(clip.id, pxPerMs) {
                                var remainder = 0f
                                detectDragGestures(
                                    onDragStart = { selectedClipId = clip.id; remainder = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        remainder += dragAmount.x / pxPerMs
                                        val delta = remainder.toLong()
                                        if (delta != 0L) {
                                            onResizeClip(clip.id, delta)
                                            remainder -= delta
                                        }
                                    }
                                )
                            }
                    )
                    // Left trim handle. It changes the source in-point and keeps the right edge fixed.
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .width(12.dp)
                            .fillMaxHeight()
                            .background(NeonCyan.copy(alpha = 0.35f))
                            .pointerInput(clip.id, pxPerMs) {
                                var remainder = 0f
                                detectDragGestures(
                                    onDragStart = { selectedClipId = clip.id; remainder = 0f },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        remainder += dragAmount.x / pxPerMs
                                        val delta = remainder.toLong()
                                        if (delta != 0L) {
                                            onTrimLeftClip(clip.id, delta)
                                            remainder -= delta
                                        }
                                    }
                                )
                            }
                    )
                }
            }
            if (clips.isEmpty()) {
                Text(
                    text = "Import photos or videos",
                    color = TextMuted,
                    fontSize = 9.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            if (selectedClipId != null && clips.any { it.id == selectedClipId }) {
                IconButton(
                    onClick = { selectedClipId?.let(onRemoveClip); selectedClipId = null },
                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove selected media", tint = Color(0xFFFF6B78), modifier = Modifier.size(13.dp))
                }
            }
        }
    }
}

@Composable
private fun TimeRuler(
    durationMs: Long,
    pxPerMs: Float,
    modifier: Modifier = Modifier
) {
    val totalSec = (durationMs / 1000L).toInt()
    val majorStep = when {
        pxPerMs < 0.001f -> 120
        pxPerMs < 0.002f -> 60
        pxPerMs < 0.004f -> 30
        pxPerMs < 0.012f -> 10
        else -> 5
    }
    val density = LocalDensity.current
    Box(modifier = modifier) {
        Canvas(Modifier.fillMaxSize()) {
            // Canvas coordinates are physical pixels, while pxPerMs is dp/ms.
            // Convert once so ruler ticks stay aligned with the dp-positioned tracks
            // at every device density and continue through the full song duration.
            val pixelsPerMs = pxPerMs * density.density
            val secStepPx = 1000f * pixelsPerMs
            for (sec in 0..totalSec) {
                val x = sec * secStepPx
                if (x > size.width) break
                val isMajor = sec % majorStep == 0
                val lineH = if (isMajor) size.height * 0.48f else size.height * 0.22f
                val lineColor = if (isMajor) TextSecondary else TextMuted.copy(alpha = 0.45f)
                drawLine(
                    color = lineColor,
                    start = Offset(x, size.height - lineH),
                    end = Offset(x, size.height),
                    strokeWidth = if (isMajor) 1.4f else 1f
                )
            }
            // Dedicated end tick at the exact audio duration, including fractional seconds.
            val endX = durationMs * pixelsPerMs
            if (endX <= size.width) {
                drawLine(
                    color = NeonCyan,
                    start = Offset(endX, size.height * 0.28f),
                    end = Offset(endX, size.height),
                    strokeWidth = 2f
                )
            }
        }
        for (sec in 0..totalSec step majorStep) {
            Text(
                text = formatTimelineTime(sec * 1000L),
                color = TextSecondary,
                fontSize = 7.sp,
                maxLines = 1,
                modifier = Modifier.offset(x = (sec * 1000f * pxPerMs).dp, y = 0.dp)
            )
        }
        Text(
            text = formatTimelineTime(durationMs),
            color = NeonCyan,
            fontSize = 7.sp,
            maxLines = 1,
            modifier = Modifier.offset(x = (durationMs * pxPerMs).dp + 4.dp, y = 0.dp)
        )
    }
}

@Composable
private fun AudioTrackRow(
    project: VideoProject,
    pxPerMs: Float,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val horizontalInsetPx = with(density) { 5.dp.toPx() }
    Box(
        modifier = modifier
            .pointerInput(project.durationMs, pxPerMs) {
                detectTapGestures { tap ->
                    val timelineX = (tap.x - horizontalInsetPx).coerceAtLeast(0f)
                    onSeek((timelineX / with(density) { pxPerMs.dp.toPx() }).toLong()
                        .coerceIn(0L, project.durationMs))
                }
            }
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF101722))
            .border(1.dp, StudioCardBorder, RoundedCornerShape(8.dp))
            .padding(vertical = 2.dp)
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val peaks = project.audioTrack.waveformPeaks
            val centerY = size.height / 2f
            drawLine(
                color = WaveformBlue.copy(alpha = 0.28f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 1f
            )
            if (peaks.isNotEmpty()) {
                val step = size.width / peaks.size
                val barWidth = (step * 0.62f).coerceIn(1f, 4f)
                peaks.forEachIndexed { index, rawPeak ->
                    val amplitude = rawPeak.coerceIn(0.035f, 1f)
                    val halfHeight = (amplitude * size.height * 0.46f).coerceAtLeast(1.5f)
                    val x = index * step + step / 2f
                    drawLine(
                        color = if (index % 5 == 0) NeonCyan else WaveformBlue,
                        start = Offset(x, centerY - halfHeight),
                        end = Offset(x, centerY + halfHeight),
                        strokeWidth = barWidth
                    )
                }
            } else {
                // Keep a clear loading/empty state until decoded PCM peaks are ready.
                for (index in 0 until 96) {
                    val x = index * size.width / 96f
                    drawLine(
                        color = WaveformBlue.copy(alpha = 0.25f),
                        start = Offset(x, centerY - 2f),
                        end = Offset(x, centerY + 2f),
                        strokeWidth = 1.5f
                    )
                }
            }
        }

        Text(
            text = project.audioTrack.title,
            fontSize = 7.sp,
            color = Color.White.copy(alpha = 0.82f),
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Color(0xB8000000), RoundedCornerShape(bottomEnd = 4.dp))
                .padding(horizontal = 4.dp, vertical = 1.dp)
                .widthIn(max = 180.dp)
        )
    }
}

@Composable
private fun LyricsTrackRow(
    lyrics: List<LyricLine>,
    playheadMs: Long,
    onSeek: (Long) -> Unit,
    selectedLineId: String?,
    pxPerMs: Float,
    onSelectLine: (String) -> Unit,
    onEditLine: (String) -> Unit,
    onAdjustStart: (Long) -> Unit,
    onAdjustDuration: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF141220))
            .border(1.dp, StudioCardBorder, RoundedCornerShape(8.dp))
    ) {
        lyrics.forEach { line ->
            val startXDp = (line.startMs * pxPerMs).dp
            val widthDp = (line.durationMs * pxPerMs).dp.coerceAtLeast(28.dp)
            val isSelected = line.id == selectedLineId
            val isActive = playheadMs in line.startMs..line.endMs

            Box(
                modifier = Modifier
                    .offset(x = startXDp)
                    .width(widthDp)
                    .fillMaxHeight()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        when {
                            isSelected -> LyricBlockSelected
                            isActive -> ElectricPurple
                            else -> LyricBlockColor.copy(alpha = 0.75f)
                        }
                    )
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) NeonCyan else Color.White.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .pointerInput(line.id) {
                        detectTapGestures(
                            onTap = {
                                onSelectLine(line.id)
                                onSeek(line.startMs)
                            },
                            onDoubleTap = { onEditLine(line.id) }
                        )
                    }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                if (widthDp >= 56.dp) {
                    Text(
                        text = line.text,
                        fontSize = when {
                            widthDp < 76.dp -> 7.sp
                            widthDp < 110.dp -> 9.sp
                            else -> 11.sp
                        },
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = "Lyric clip",
                        tint = Color.White.copy(alpha = 0.85f),
                        modifier = Modifier.size(10.dp)
                    )
                }

                // Left trim handle: adjusts the lyric start while keeping its end fixed.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .width(16.dp)
                        .fillMaxHeight()
                        .background(NeonCyan.copy(alpha = 0.9f), RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp))
                        .pointerInput(line.id, pxPerMs) {
                            var remainder = 0f
                            detectDragGestures(
                                onDragStart = { onSelectLine(line.id) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    remainder += amount.x
                                    val delta = (remainder / pxPerMs).toLong()
                                    if (delta != 0L) {
                                        onAdjustStart(delta)
                                        remainder -= delta * pxPerMs
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.width(2.dp).fillMaxHeight(0.55f).background(Color.White, CircleShape))
                }

                // Right trim handle: adjusts the lyric end while keeping its start fixed.
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .width(16.dp)
                        .fillMaxHeight()
                        .background(NeonCyan.copy(alpha = 0.9f), RoundedCornerShape(topEnd = 6.dp, bottomEnd = 6.dp))
                        .pointerInput(line.id, pxPerMs) {
                            var remainder = 0f
                            detectDragGestures(
                                onDragStart = { onSelectLine(line.id) },
                                onDrag = { change, amount ->
                                    change.consume()
                                    remainder += amount.x
                                    val delta = (remainder / pxPerMs).toLong()
                                    if (delta != 0L) {
                                        onAdjustDuration(delta)
                                        remainder -= delta * pxPerMs
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(Modifier.width(2.dp).fillMaxHeight(0.55f).background(Color.White, CircleShape))
                }
            }
        }
    }
}


private fun formatTimelineTime(ms: Long): String {
    val totalSeconds = ms / 1000L
    return String.format(java.util.Locale.US, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

private fun formatMs(ms: Long): String {
    val sec = ms / 1000
    val tenth = (ms % 1000) / 100
    return "${sec}.${tenth}s"
}
