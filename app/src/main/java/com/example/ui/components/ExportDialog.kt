package com.winsmediatama.lyricmakerpro.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winsmediatama.lyricmakerpro.model.CanvasRatio
import com.winsmediatama.lyricmakerpro.model.ExportQuality
import com.winsmediatama.lyricmakerpro.model.VideoProject
import com.winsmediatama.lyricmakerpro.ui.theme.*
import java.io.File

@Composable
fun ExportDialog(
    project: VideoProject,
    isExporting: Boolean,
    exportProgress: Float,
    exportStatus: String,
    lastExportedFile: File?,
    onStartExport: (ExportQuality, Int) -> Unit,
    onWatchAdAndExport: (ExportQuality, Int) -> Unit,
    isPermanentWatermarkRemoved: Boolean,
    permanentWatermarkPrice: String?,
    onBuyPermanentWatermark: () -> Unit,
    onRestorePurchases: () -> Unit,
    onExportCover: () -> Unit,
    onShare: () -> Unit,
    onExportAgain: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedQuality by remember { mutableStateOf(ExportQuality.FULL_HD_1080P) }
    var selectedDurationSec by remember { mutableIntStateOf(15) }

    AlertDialog(
        onDismissRequest = { if (!isExporting) onDismiss() },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null,
                    tint = NeonCyan,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "Export & Share Clip",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = TextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Video specs overview
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = StudioSurfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = project.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
                            Text(text = "${project.canvasRatio.label} • ${project.canvasRatio.iconDescription}", fontSize = 11.sp, color = NeonCyan)
                        }
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = NeonMagenta.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, NeonMagenta)
                        ) {
                            Text(
                                text = "MP4 Video",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeonMagenta,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (!isExporting && lastExportedFile == null) {
                    // Quality Picker
                    Text(text = "Video Resolution", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        ExportQuality.entries.forEach { quality ->
                            val isSelected = quality == selectedQuality
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedQuality = quality },
                                label = { Text(quality.label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Clip Duration Picker
                    Text(text = "Clip Duration", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = TextSecondary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(10 to "10s Preview", 15 to "15s Reel", 30 to "30s Full").forEach { (sec, label) ->
                            val isSelected = sec == selectedDurationSec
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedDurationSec = sec },
                                label = { Text(label, fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    // Export the complete song instead of a short social-media clip.
                    FilterChip(
                        selected = selectedDurationSec == 0,
                        onClick = { selectedDurationSec = 0 },
                        label = {
                            Text(
                                "Full Song • ${formatExportDuration(project.durationMs)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("export_full_song_duration")
                    )
                }

                // Live Export Progress
                if (isExporting) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        LinearProgressIndicator(
                            progress = { exportProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp),
                            color = NeonCyan,
                            trackColor = StudioSurfaceVariant
                        )
                        Text(
                            text = exportStatus,
                            fontSize = 12.sp,
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Completed state: ready to share
                AnimatedVisibility(visible = lastExportedFile != null && !isExporting) {
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant),
                        border = androidx.compose.foundation.BorderStroke(1.dp, NeonCyan),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("Ready to Share!", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                                Text("High-quality lyric clip rendered successfully.", fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (lastExportedFile != null && !isExporting) {
                    Button(
                        onClick = onShare,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        modifier = Modifier.fillMaxWidth().testTag("share_video_button")
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text("Share to Social Media", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                    Button(
                        onClick = onExportAgain,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        modifier = Modifier.fillMaxWidth().testTag("export_again_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export Video Again", fontWeight = FontWeight.Bold)
                    }
                } else if (!isExporting) {
                    if (isPermanentWatermarkRemoved) {
                        Surface(color = NeonCyan.copy(alpha = 0.12f), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Verified, contentDescription = null, tint = NeonCyan)
                                Spacer(Modifier.width(8.dp))
                                Text("Permanent watermark removal active", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            }
                        }
                    } else {
                        Button(
                            onClick = onBuyPermanentWatermark,
                            colors = ButtonDefaults.buttonColors(containerColor = CyberYellow),
                            modifier = Modifier.fillMaxWidth().testTag("buy_permanent_remove_watermark_button")
                        ) {
                            Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = Color.Black)
                            Spacer(Modifier.width(6.dp))
                            Text("Remove Watermark Permanently • ${permanentWatermarkPrice ?: "US$5.00"}", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onRestorePurchases,
                            modifier = Modifier.fillMaxWidth().testTag("restore_remove_watermark_button")
                        ) { Text("Restore Purchase") }
                    }
                    Button(
                        onClick = { onWatchAdAndExport(selectedQuality, selectedDurationSec) },
                        enabled = project.audioTrack.beatType == com.winsmediatama.lyricmakerpro.model.AudioBeatType.CUSTOM_FILE && project.durationMs > 0L,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonCyan),
                        modifier = Modifier.fillMaxWidth().testTag("watch_ad_export_button")
                    ) {
                        Icon(Icons.Default.PlayCircle, contentDescription = null, modifier = Modifier.size(16.dp), tint = Color.Black)
                        Spacer(Modifier.width(6.dp))
                        Text("Watch Ad • Export Without Watermark", fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                    Button(
                        onClick = { onStartExport(selectedQuality, selectedDurationSec) },
                        enabled = project.audioTrack.beatType == com.winsmediatama.lyricmakerpro.model.AudioBeatType.CUSTOM_FILE && project.durationMs > 0L,
                        colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                        modifier = Modifier.fillMaxWidth().testTag("start_export_button")
                    ) {
                        Icon(Icons.Default.MovieCreation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Export With Watermark", fontWeight = FontWeight.Bold)
                    }
                }

                if (!isExporting) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onExportCover,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberYellow)
                        ) {
                            Text("Save Poster", fontWeight = FontWeight.SemiBold)
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
                        ) {
                            Text("Close", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        },
        dismissButton = {},
        containerColor = StudioSurface
    )
}

private fun formatExportDuration(durationMs: Long): String {
    val totalSeconds = (durationMs.coerceAtLeast(0L) + 999L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
    }
}
