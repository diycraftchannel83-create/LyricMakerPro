package com.example.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.model.LyricLine
import com.example.model.VideoProject
import com.example.ui.theme.*

@Composable
fun MultiTrackTimeline(
    project: VideoProject,
    playheadMs: Long,
    selectedLineId: String?,
    onSelectLine: (String?) -> Unit,
    onSeek: (Long) -> Unit,
    onNudge: (Long) -> Unit,
    onMoveLine: (Long) -> Unit,
    onAdjustDuration: (Long) -> Unit,
    onTextChange: (String, String) -> Unit,
    onAddLine: () -> Unit,
    onDeleteLine: () -> Unit,
    onOpenTapToSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Zoom factor: pixels per millisecond
    var pxPerMs by remember { mutableFloatStateOf(0.065f) } // ~65px per second
    val totalTimelineWidthDp = ((project.durationMs * pxPerMs)).dp.coerceAtLeast(360.dp)

    val scrollState = rememberScrollState()

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
                    text = "Multi-Track Timeline",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
            }

            // Quick Nudge & Zoom Controls
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Zoom Out
                IconButton(
                    onClick = { pxPerMs = (pxPerMs * 0.8f).coerceAtLeast(0.03f) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomOut,
                        contentDescription = "Zoom Out",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Zoom In
                IconButton(
                    onClick = { pxPerMs = (pxPerMs * 1.25f).coerceAtMost(0.18f) },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ZoomIn,
                        contentDescription = "Zoom In",
                        tint = TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Tap to sync shortcut pill
                Button(
                    onClick = onOpenTapToSync,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier
                        .height(30.dp)
                        .testTag("tap_to_sync_quick_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.TouchApp,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Tap to Sync", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }

        // Horizontal Scrollable Multi-Track Canvas
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .padding(vertical = 4.dp)
                .horizontalScroll(scrollState)
        ) {
            Box(
                modifier = Modifier
                    .width(totalTimelineWidthDp)
                    .fillMaxHeight()
                    .pointerInput(project.durationMs) {
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
                            .height(24.dp)
                    )

                    Spacer(Modifier.height(4.dp))

                    // Track 1: Audio Waveform Track
                    AudioTrackRow(
                        project = project,
                        pxPerMs = pxPerMs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp)
                    )

                    Spacer(Modifier.height(4.dp))

                    // Track 2: Lyrics Blocks Track
                    LyricsTrackRow(
                        lyrics = project.lyrics,
                        playheadMs = playheadMs,
                        selectedLineId = selectedLineId,
                        pxPerMs = pxPerMs,
                        onSelectLine = onSelectLine,
                        onMoveLine = onMoveLine,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    )

                    Spacer(Modifier.height(4.dp))

                    // Track 3: Visualizer & Background Effects Track
                    EffectsTrackRow(
                        project = project,
                        pxPerMs = pxPerMs,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(32.dp)
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

        // Selected Lyric Action Ribbon
        if (selectedLineId != null) {
            val selectedLine = project.lyrics.find { it.id == selectedLineId }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(StudioSurfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    OutlinedTextField(
                        value = selectedLine?.text.orEmpty(),
                        onValueChange = { text -> selectedLine?.let { onTextChange(it.id, text) } },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 42.dp),
                        textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary),
                        placeholder = { Text("Edit lyric text", fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NeonCyan,
                            unfocusedBorderColor = StudioCardBorder,
                            cursorColor = NeonCyan
                        )
                    )
                    Text(
                        text = "${formatMs(selectedLine?.startMs ?: 0L)} → ${formatMs(selectedLine?.endMs ?: 0L)} (${selectedLine?.durationMs ?: 0L}ms)",
                        fontSize = 11.sp,
                        color = NeonCyan
                    )
                }

                // Nudge and Edit Buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    FilledTonalButton(
                        onClick = { onNudge(-100L) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("nudge_backward_button")
                    ) {
                        Text("-100ms", fontSize = 10.sp, color = TextPrimary)
                    }

                    FilledTonalButton(
                        onClick = { onNudge(100L) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier
                            .height(28.dp)
                            .testTag("nudge_forward_button")
                    ) {
                        Text("+100ms", fontSize = 10.sp, color = TextPrimary)
                    }

                    FilledTonalButton(
                        onClick = { onAdjustDuration(500L) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(28.dp)
                    ) {
                        Text("+0.5s", fontSize = 10.sp, color = TextPrimary)
                    }

                    IconButton(
                        onClick = onDeleteLine,
                        modifier = Modifier
                            .size(28.dp)
                            .testTag("delete_lyric_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Delete Line",
                            tint = HotCoral,
                            modifier = Modifier.size(16.dp)
                        )
                    }
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
    Canvas(modifier = modifier) {
        val totalSec = (durationMs / 1000).toInt()
        val secStepPx = 1000f * pxPerMs

        for (sec in 0..totalSec) {
            val x = sec * secStepPx
            val isMajor = sec % 5 == 0
            val lineH = if (isMajor) size.height * 0.7f else size.height * 0.35f
            val lineColor = if (isMajor) TextSecondary else TextMuted.copy(alpha = 0.5f)

            drawLine(
                color = lineColor,
                start = Offset(x, size.height - lineH),
                end = Offset(x, size.height),
                strokeWidth = if (isMajor) 1.5f else 1f
            )
        }
    }
}

@Composable
private fun AudioTrackRow(
    project: VideoProject,
    pxPerMs: Float,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF161926))
            .border(1.dp, StudioCardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Simulated Waveform Bars
        Canvas(modifier = Modifier.fillMaxSize()) {
            val peaks = project.audioTrack.waveformPeaks
            if (peaks.isNotEmpty()) {
                val step = size.width / peaks.size
                val barW = (step * 0.7f).coerceAtLeast(1.5f)
                val cy = size.height / 2f

                for (i in peaks.indices) {
                    val peak = peaks[i]
                    val x = i * step
                    val barH = (peak * (size.height * 0.8f)).coerceAtLeast(4f)
                    drawLine(
                        color = WaveformBlue.copy(alpha = 0.6f),
                        start = Offset(x, cy - barH / 2f),
                        end = Offset(x, cy + barH / 2f),
                        strokeWidth = barW
                    )
                }
            }
        }

        // Track Tag Overlay
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = WaveformBlue,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "${project.audioTrack.title} (${project.audioTrack.bpm} BPM)",
                fontSize = 10.sp,
                color = TextPrimary,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun LyricsTrackRow(
    lyrics: List<LyricLine>,
    playheadMs: Long,
    selectedLineId: String?,
    pxPerMs: Float,
    onSelectLine: (String) -> Unit,
    onMoveLine: (Long) -> Unit,
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
                    .clickable { onSelectLine(line.id) }
                    .pointerInput(line.id, pxPerMs) {
                        var dragRemainderPx = 0f
                        detectDragGestures(
                            onDragStart = { onSelectLine(line.id) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragRemainderPx += dragAmount.x
                                val deltaMs = (dragRemainderPx / pxPerMs).toLong()
                                if (deltaMs != 0L) {
                                    onMoveLine(deltaMs)
                                    dragRemainderPx -= deltaMs * pxPerMs
                                }
                            }
                        )
                    }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    text = line.text,
                    fontSize = 11.sp,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun EffectsTrackRow(
    project: VideoProject,
    pxPerMs: Float,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF10121C))
            .border(1.dp, StudioCardBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Visualizer Tag
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Equalizer,
                contentDescription = null,
                tint = NeonCyan,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "FX: ${project.visualizer.label}",
                fontSize = 10.sp,
                color = TextSecondary
            )
        }

        // Motion Transition Tag
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Animation,
                contentDescription = null,
                tint = NeonMagenta,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = "Motion: ${project.transitionEffect.label}",
                fontSize = 10.sp,
                color = TextSecondary
            )
        }
    }
}

private fun formatMs(ms: Long): String {
    val sec = ms / 1000
    val tenth = (ms % 1000) / 100
    return "${sec}.${tenth}s"
}
