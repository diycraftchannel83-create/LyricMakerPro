package com.example

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.model.BackgroundConfig
import com.example.model.BackgroundType
import com.example.ui.components.*
import com.example.ui.theme.*
import com.example.viewmodel.LyricMakerViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: LyricMakerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val project by viewModel.project.collectAsStateWithLifecycle()
                val isPlaying by viewModel.audioEngine.isPlaying.collectAsStateWithLifecycle()
                val playheadMs by viewModel.audioEngine.playheadMs.collectAsStateWithLifecycle()
                val spectrumBands by viewModel.audioEngine.spectrumBands.collectAsStateWithLifecycle()
                val bassEnergy by viewModel.audioEngine.bassEnergy.collectAsStateWithLifecycle()

                val selectedLineId by viewModel.selectedLineId.collectAsStateWithLifecycle()
                val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()

                val isTapToSyncActive by viewModel.isTapToSyncActive.collectAsStateWithLifecycle()
                val syncLineIndex by viewModel.syncLineIndex.collectAsStateWithLifecycle()

                val showExportDialog by viewModel.showExportDialog.collectAsStateWithLifecycle()
                val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
                val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
                val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
                val lastExportedFile by viewModel.lastExportedFile.collectAsStateWithLifecycle()

                val showTemplatesSheet by viewModel.showTemplatesSheet.collectAsStateWithLifecycle()

                // File Pickers
                val audioPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    if (uri != null) {
                        var fileName = "Imported Audio"
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && nameIndex != -1) {
                                fileName = cursor.getString(nameIndex)
                            }
                        }
                        viewModel.importCustomAudio(uri.toString(), fileName)
                    }
                }

                val imagePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia()
                ) { uri: Uri? ->
                    if (uri != null) {
                        viewModel.updateBackground(
                            BackgroundConfig(
                                type = BackgroundType.IMAGE,
                                imageUri = uri.toString(),
                                darkOverlayOpacity = 0.5f
                            )
                        )
                    }
                }

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(StudioBackground),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        StudioTopAppBar(
                            projectTitle = project.title,
                            onOpenTemplates = { viewModel.setShowTemplatesSheet(true) },
                            onPickAudio = { audioPicker.launch("audio/*") },
                            onPickImage = {
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onOpenExport = { viewModel.setShowExportDialog(true) }
                        )
                    }
                ) { innerPadding ->
                    BoxWithConstraints(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(StudioBackground)
                    ) {
                        val isWideScreen = maxWidth > 650.dp

                        if (isWideScreen) {
                            // Side-by-side tablet layout
                            Row(modifier = Modifier.fillMaxSize()) {
                                VideoPreviewPlayer(
                                    project = project,
                                    playheadMs = playheadMs,
                                    isPlaying = isPlaying,
                                    spectrumBands = spectrumBands,
                                    bassEnergy = bassEnergy,
                                    onTogglePlay = { viewModel.audioEngine.togglePlayPause() },
                                    onSeek = { viewModel.audioEngine.seekTo(it) },
                                    onTextPositionChange = { dx, dy -> viewModel.updateTypographyProperties(positionXPercent = project.typography.positionXPercent + dx, positionYPercent = project.typography.positionYPercent + dy) },
                                    onTextScaleChange = { factor -> viewModel.updateTypographyProperties(fontSizeSp = (project.typography.fontSizeSp * factor).coerceIn(18f, 96f)) },\n                                    onTypographyChange = { viewModel.setTypographyPreset(it) },
                                    onRatioChange = { viewModel.setCanvasRatio(it) },
                                    modifier = Modifier
                                        .weight(0.5f)
                                        .fillMaxHeight()
                                )

                                Column(
                                    modifier = Modifier
                                        .weight(0.5f)
                                        .fillMaxHeight()
                                ) {
                                    MultiTrackTimeline(
                                        project = project,
                                        playheadMs = playheadMs,
                                        selectedLineId = selectedLineId,
                                        onSelectLine = { viewModel.selectLyricLine(it) },
                                        onSeek = { viewModel.audioEngine.seekTo(it) },
                                        onNudge = { viewModel.nudgeSelectedLyric(it) },
                                        onMoveLine = { viewModel.moveSelectedLyric(it) },
                                        onAdjustStart = { viewModel.updateSelectedLyricStart(it) },
                                        onAdjustDuration = { viewModel.updateSelectedLyricDuration(it) },
                                        onTextChange = { id, text -> viewModel.updateLyricText(id, text) },
                                        onAddLine = { viewModel.addLyricLine() },
                                        onDeleteLine = { viewModel.deleteSelectedLyric() },
                                        onOpenTapToSync = { viewModel.startTapToSync() }
                                    )

                                    StudioTabs(
                                        viewModel = viewModel,
                                        project = project,
                                        currentTab = currentTab,
                                        onTabSelected = { viewModel.selectTab(it) },
                                        onOpenTapToSync = { viewModel.startTapToSync() },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        } else {
                            // Mobile vertical studio layout
                            Column(modifier = Modifier.fillMaxSize()) {
                                VideoPreviewPlayer(
                                    project = project,
                                    playheadMs = playheadMs,
                                    isPlaying = isPlaying,
                                    spectrumBands = spectrumBands,
                                    bassEnergy = bassEnergy,
                                    onTogglePlay = { viewModel.audioEngine.togglePlayPause() },
                                    onSeek = { viewModel.audioEngine.seekTo(it) },
                                    onTextPositionChange = { dx, dy -> viewModel.updateTypographyProperties(positionXPercent = project.typography.positionXPercent + dx, positionYPercent = project.typography.positionYPercent + dy) },
                                    onTextScaleChange = { factor -> viewModel.updateTypographyProperties(fontSizeSp = (project.typography.fontSizeSp * factor).coerceIn(18f, 96f)) },
                                    onRatioChange = { viewModel.setCanvasRatio(it) }
                                )

                                MultiTrackTimeline(
                                    project = project,
                                    playheadMs = playheadMs,
                                    selectedLineId = selectedLineId,
                                    onSelectLine = { viewModel.selectLyricLine(it) },
                                    onSeek = { viewModel.audioEngine.seekTo(it) },
                                    onNudge = { viewModel.nudgeSelectedLyric(it) },
                                    onMoveLine = { viewModel.moveSelectedLyric(it) },
                                    onAdjustStart = { viewModel.updateSelectedLyricStart(it) },
                                    onAdjustDuration = { viewModel.updateSelectedLyricDuration(it) },
                                        onTextChange = { id, text -> viewModel.updateLyricText(id, text) },
                                    onAddLine = { viewModel.addLyricLine() },
                                    onDeleteLine = { viewModel.deleteSelectedLyric() },
                                    onOpenTapToSync = { viewModel.startTapToSync() }
                                )

                                StudioTabs(
                                    viewModel = viewModel,
                                    project = project,
                                    currentTab = currentTab,
                                    onTabSelected = { viewModel.selectTab(it) },
                                    onOpenTapToSync = { viewModel.startTapToSync() },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                )
                            }
                        }
                    }

                    // Dialogs & Sheets
                    if (isTapToSyncActive) {
                        TapToSyncModal(
                            project = project,
                            currentLineIndex = syncLineIndex,
                            playheadMs = playheadMs,
                            isPlaying = isPlaying,
                            onTapNext = { viewModel.tapSyncNext() },
                            onFinish = { viewModel.stopTapToSync() }
                        )
                    }

                    if (showExportDialog) {
                        ExportDialog(
                            project = project,
                            isExporting = isExporting,
                            exportProgress = exportProgress,
                            exportStatus = exportStatus,
                            lastExportedFile = lastExportedFile,
                            onStartExport = { quality, durationSec ->
                                viewModel.startExport(quality, durationSec)
                            },
                            onExportCover = { viewModel.exportCoverImage() },
                            onShare = { viewModel.shareLastExported() },
                            onDismiss = { viewModel.setShowExportDialog(false) }
                        )
                    }

                    if (showTemplatesSheet) {
                        TemplatesSheet(
                            onSelectProject = { viewModel.loadProject(it) },
                            onDismiss = { viewModel.setShowTemplatesSheet(false) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioTopAppBar(
    projectTitle: String,
    onOpenTemplates: () -> Unit,
    onPickAudio: () -> Unit,
    onPickImage: () -> Unit,
    onOpenExport: () -> Unit
) {
    Surface(
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        color = StudioSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // App Branding & Project Title
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(ElectricPurple)
                        .border(1.dp, NeonCyan, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.MusicVideo,
                        contentDescription = "LyricMaker",
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Column {
                    Text(
                        text = "LyricMaker",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                        color = TextPrimary
                    )
                    Text(
                        text = projectTitle,
                        fontSize = 11.sp,
                        color = NeonCyan,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            // Action Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Templates button
                IconButton(
                    onClick = onOpenTemplates,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("templates_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.LibraryMusic,
                        contentDescription = "Templates",
                        tint = CyberYellow,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Import Custom Audio button
                IconButton(
                    onClick = onPickAudio,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("import_audio_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.AudioFile,
                        contentDescription = "Import Audio",
                        tint = NeonCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Import Custom Photo Background
                IconButton(
                    onClick = onPickImage,
                    modifier = Modifier
                        .size(36.dp)
                        .testTag("import_image_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Custom Background",
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Primary Export Button
                Button(
                    onClick = onOpenExport,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    modifier = Modifier
                        .height(36.dp)
                        .testTag("export_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.FileUpload,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "Export",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}
