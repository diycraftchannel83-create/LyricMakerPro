package com.winsmediatama.lyricmakerpro.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.winsmediatama.lyricmakerpro.model.VideoProject
import com.winsmediatama.lyricmakerpro.ui.theme.*

@Composable
fun TapToSyncModal(
    project: VideoProject,
    currentLineIndex: Int,
    playheadMs: Long,
    isPlaying: Boolean,
    onTapNext: () -> Unit,
    onFinish: () -> Unit
) {
    val currentLine = project.lyrics.getOrNull(currentLineIndex)
    val nextLine = project.lyrics.getOrNull(currentLineIndex + 1)

    // Pulsing animation for tap button
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Dialog(
        onDismissRequest = onFinish,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(StudioBackground),
            color = StudioBackground
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header with Exit Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = null,
                            tint = NeonMagenta,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = "Live Tap-to-Sync",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    IconButton(
                        onClick = onFinish,
                        modifier = Modifier
                            .size(36.dp)
                            .testTag("close_tap_to_sync_button")
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                // Center Cue Prompter
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) {
                    Text(
                        text = "Line ${(currentLineIndex + 1).coerceAtMost(project.lyrics.size)} of ${project.lyrics.size}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NeonCyan
                    )

                    // Current Cue Card
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant),
                        border = androidx.compose.foundation.BorderStroke(2.dp, NeonCyan),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "CURRENT LINE CUE",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonCyan
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = currentLine?.text ?: "All lines synchronized!",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    // Next Upcoming Line Preview
                    if (nextLine != null) {
                        Text(
                            text = "Next: \"${nextLine.text}\"",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }

                    // Audio Timecode
                    Text(
                        text = "${formatMs(playheadMs)} / ${formatMs(project.durationMs)}",
                        fontSize = 14.sp,
                        color = TextMuted,
                        fontWeight = FontWeight.Medium
                    )
                }

                // Giant Beat Tap Button
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(NeonMagenta)
                        .border(4.dp, Color.White, CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = true, radius = 80.dp),
                            onClick = onTapNext
                        )
                        .testTag("tap_sync_beat_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.TouchApp,
                            contentDescription = "Tap",
                            tint = Color.White,
                            modifier = Modifier.size(44.dp)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "TAP ON BEAT",
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp,
                            color = Color.White
                        )
                    }
                }

                // Bottom Save & Complete Button
                Button(
                    onClick = onFinish,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("finish_sync_button")
                ) {
                    Text(
                        text = "Finish & Apply Timestamps",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.Black
                    )
                }
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val sec = ms / 1000
    val tenth = (ms % 1000) / 100
    return "${sec}.${tenth}s"
}
