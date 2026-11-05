package com.winsmediatama.lyricmakerpro

import android.net.Uri
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.winsmediatama.lyricmakerpro.ads.RewardedAdManager
import com.winsmediatama.lyricmakerpro.ads.FreeAppOpenAdManager
import com.winsmediatama.lyricmakerpro.ads.FreeInterstitialAdManager
import com.winsmediatama.lyricmakerpro.ads.FreeNativeAd
import com.winsmediatama.lyricmakerpro.billing.PermanentWatermarkBilling
import com.winsmediatama.lyricmakerpro.model.BackgroundConfig
import com.winsmediatama.lyricmakerpro.model.BackgroundType
import com.winsmediatama.lyricmakerpro.data.ProjectFileCodec
import com.winsmediatama.lyricmakerpro.data.LyricMakerStorage
import com.winsmediatama.lyricmakerpro.ui.components.*
import com.winsmediatama.lyricmakerpro.ui.theme.*
import com.winsmediatama.lyricmakerpro.viewmodel.LyricMakerViewModel

class MainActivity : ComponentActivity() {

    private lateinit var rewardedAdManager: RewardedAdManager
    private lateinit var appOpenAdManager: FreeAppOpenAdManager
    private lateinit var interstitialAdManager: FreeInterstitialAdManager
    private lateinit var watermarkBilling: PermanentWatermarkBilling
    private val viewModel: LyricMakerViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        if (::watermarkBilling.isInitialized) watermarkBilling.refreshEntitlement()
        if (::appOpenAdManager.isInitialized) appOpenAdManager.onForeground()
    }

    override fun onStop() {
        if (::appOpenAdManager.isInitialized) appOpenAdManager.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        if (::watermarkBilling.isInitialized) watermarkBilling.close()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        rewardedAdManager = RewardedAdManager(this)
        appOpenAdManager = FreeAppOpenAdManager(this)
        interstitialAdManager = FreeInterstitialAdManager(this)
        watermarkBilling = PermanentWatermarkBilling(this)
        watermarkBilling.connect()

        setContent {
            MyApplicationTheme {
                val project by viewModel.project.collectAsStateWithLifecycle()
                val isPlaying by viewModel.audioEngine.isPlaying.collectAsStateWithLifecycle()
                val playheadMs by viewModel.audioEngine.playheadMs.collectAsStateWithLifecycle()
                val spectrumBands by viewModel.audioEngine.spectrumBands.collectAsStateWithLifecycle()
                val bassEnergy by viewModel.audioEngine.bassEnergy.collectAsStateWithLifecycle()

                val selectedLineId by viewModel.selectedLineId.collectAsStateWithLifecycle()
                val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
                var studioSheetExpanded by rememberSaveable { mutableStateOf(false) }
                val studioSheetHeight by animateDpAsState(
                    targetValue = if (studioSheetExpanded) 300.dp else 64.dp,
                    label = "studio-sheet-height"
                )

                val isTapToSyncActive by viewModel.isTapToSyncActive.collectAsStateWithLifecycle()
                val syncLineIndex by viewModel.syncLineIndex.collectAsStateWithLifecycle()

                val showExportDialog by viewModel.showExportDialog.collectAsStateWithLifecycle()
                val isExporting by viewModel.isExporting.collectAsStateWithLifecycle()
                val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
                val exportStatus by viewModel.exportStatus.collectAsStateWithLifecycle()
                val lastExportedFile by viewModel.lastExportedFile.collectAsStateWithLifecycle()
                DisposableEffect(isExporting) {
                    if (isExporting) {
                        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                val permanentWatermarkRemoved by watermarkBilling.isOwned.collectAsStateWithLifecycle()
                val watermarkEntitlementResolved by watermarkBilling.isEntitlementResolved.collectAsStateWithLifecycle()
                // Cached Play purchase state is not trusted until the billing query resolves.
                val hasConfirmedPermanentWatermarkRemoval = watermarkEntitlementResolved && permanentWatermarkRemoved
                var wasExporting by remember { mutableStateOf(false) }
                LaunchedEffect(isExporting, lastExportedFile, hasConfirmedPermanentWatermarkRemoval) {
                    // Show an interstitial only after a successful export and only for Free users.
                    if (wasExporting && !isExporting && lastExportedFile != null &&
                        !hasConfirmedPermanentWatermarkRemoval
                    ) {
                        interstitialAdManager.showAfterExport()
                    }
                    wasExporting = isExporting
                }
                LaunchedEffect(watermarkEntitlementResolved, permanentWatermarkRemoved) {
                    // Keep every ad format in sync with the verified Play Billing entitlement.
                    if (watermarkEntitlementResolved) {
                        val isPremium = permanentWatermarkRemoved
                        appOpenAdManager.setPremiumStatus(isPremium)
                        interstitialAdManager.setPremiumStatus(isPremium)
                        rewardedAdManager.setPremiumStatus(isPremium)
                    }
                }
                val permanentWatermarkPrice by watermarkBilling.price.collectAsStateWithLifecycle()
                val billingMessage by watermarkBilling.message.collectAsStateWithLifecycle()
                LaunchedEffect(billingMessage) {
                    billingMessage?.let {
                        Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                        watermarkBilling.clearMessage()
                    }
                }

                val showTemplatesSheet by viewModel.showTemplatesSheet.collectAsStateWithLifecycle()
                var showSaveProjectDialog by rememberSaveable { mutableStateOf(false) }
                var showNewProjectDialog by rememberSaveable { mutableStateOf(false) }
                var saveProjectName by rememberSaveable { mutableStateOf(project.title) }
                var isSavingProject by remember { mutableStateOf(false) }
                val saveProjectScope = rememberCoroutineScope()

                val lyricsPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent()
                ) { uri: Uri? ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        }.onSuccess { text ->
                            if (!text.isNullOrBlank()) viewModel.importLyricsText(text)
                        }
                    }
                }

                // File Pickers
                val audioPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    if (uri != null) {
                        runCatching {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        var fileName = "Imported Audio"
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && nameIndex != -1) {
                                fileName = cursor.getString(nameIndex)
                            }
                        }
                        var songTitle = ""
                        val durationMs = runCatching {
                            MediaMetadataRetriever().let { retriever ->
                                try {
                                    retriever.setDataSource(this@MainActivity, uri)
                                    songTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
                                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                        ?.toLongOrNull() ?: 45_000L
                                } finally {
                                    retriever.release()
                                }
                            }
                        }.getOrDefault(45_000L).coerceAtLeast(500L)
                        viewModel.importCustomAudio(
                            uri.toString(),
                            songTitle.ifBlank { fileName },
                            durationMs
                        )
                    }
                }

                val imagePicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia()
                ) { uri: Uri? ->
                    if (uri != null) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        var fileName = "Imported Image"
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && nameIndex != -1) fileName = cursor.getString(nameIndex)
                        }
                        viewModel.addMediaClip(uri.toString(), fileName, com.winsmediatama.lyricmakerpro.model.MediaClipType.IMAGE)
                    }
                }

                val videoPicker = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia()
                ) { uri: Uri? ->
                    if (uri != null) {
                        runCatching { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                        var fileName = "Imported Video"
                        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (cursor.moveToFirst() && nameIndex != -1) fileName = cursor.getString(nameIndex)
                        }
                        val durationMs = runCatching {
                            MediaMetadataRetriever().let { retriever ->
                                try {
                                    retriever.setDataSource(this@MainActivity, uri)
                                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 10_000L
                                } finally {
                                    retriever.release()
                                }
                            }
                        }.getOrDefault(10_000L).coerceAtLeast(500L)
                        viewModel.addMediaClip(uri.toString(), fileName, com.winsmediatama.lyricmakerpro.model.MediaClipType.VIDEO, durationMs)
                    }
                }

                val openProjectLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result: androidx.activity.result.ActivityResult ->
                    val uri = result.data?.data
                    if (result.resultCode == android.app.Activity.RESULT_OK && uri != null) {
                        runCatching {
                            // Some document providers do not grant persistable access.
                            // Permission persistence is best-effort; opening the selected URI
                            // must still proceed while the temporary grant is valid.
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            }
                            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                                ?: error("Unable to read project file")
                        }.mapCatching { json -> ProjectFileCodec.decode(json) }
                            .onSuccess { loaded ->
                                val audioUri = loaded.audioTrack.customUri?.let(Uri::parse)
                                val audioCanBeReopened = if (
                                    loaded.audioTrack.beatType == com.winsmediatama.lyricmakerpro.model.AudioBeatType.CUSTOM_FILE &&
                                    audioUri != null
                                ) {
                                    runCatching {
                                        contentResolver.openFileDescriptor(audioUri, "r")?.use { }
                                            ?: error("Audio file is unavailable")
                                    }.isSuccess
                                } else {
                                    loaded.audioTrack.beatType != com.winsmediatama.lyricmakerpro.model.AudioBeatType.CUSTOM_FILE
                                }

                                // Restore lyrics, word-level sync and timeline even if the
                                // original audio permission/file is no longer available.
                                viewModel.loadProject(loaded)
                                val message = if (audioCanBeReopened) {
                                    "Project opened"
                                } else {
                                    "Project opened. Audio access expired: re-import the original audio file to play it."
                                }
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            }.onFailure {
                                Toast.makeText(this@MainActivity, "Open failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show()
                            }
                    }
                }

                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(StudioBackground),
                    contentWindowInsets = WindowInsets.safeDrawing,
                    topBar = {
                        Column {
                        StudioTopAppBar(
                            projectTitle = project.title,
                            onOpenTemplates = { viewModel.setShowTemplatesSheet(true) },
                            onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                            onPickImage = {
                                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onPickVideo = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                            onOpenExport = { viewModel.setShowExportDialog(true) },
                            onPickLyrics = { lyricsPicker.launch("text/*") },
                            onOpenProject = {
                                val projectFolderUri = android.provider.DocumentsContract.buildDocumentUri(
                                    "com.android.externalstorage.documents",
                                    "primary:Documents/LyricMakerPro/Project"
                                )
                                val intent = android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                                    addCategory(android.content.Intent.CATEGORY_OPENABLE)
                                    type = "*/*"
                                    putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain"))
                                    putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, projectFolderUri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                openProjectLauncher.launch(intent)
                            },
                            onSaveProject = {
                                saveProjectName = project.title
                                showSaveProjectDialog = true
                            },
                            onSaveLrc = {
                                runCatching { LyricMakerStorage.saveLrc(this@MainActivity, project) }
                                    .onSuccess { Toast.makeText(this@MainActivity, "LRC saved successfully", Toast.LENGTH_LONG).show() }
                                    .onFailure { Toast.makeText(this@MainActivity, "LRC save failed: ${it.localizedMessage}", Toast.LENGTH_LONG).show() }
                            },
                            onNewProject = { showNewProjectDialog = true },
                            onExit = {
                                viewModel.audioEngine.stop()
                                finishAffinity()
                            }
                        )
                        if (watermarkEntitlementResolved && !hasConfirmedPermanentWatermarkRemoval) {
                            FreeNativeAd(isPremium = false)
                        }
                        }
                    },
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
                                    project = project.copy(showWatermark = !hasConfirmedPermanentWatermarkRemoval),
                                    playheadMs = playheadMs,
                                    isPlaying = isPlaying,
                                    spectrumBands = spectrumBands,
                                    bassEnergy = bassEnergy,
                                    onTogglePlay = { viewModel.audioEngine.togglePlayPause() },
                                    onSeek = { viewModel.audioEngine.seekTo(it) },
                                    onTextPositionChange = { dx, dy -> viewModel.updateTypographyProperties(positionXPercent = project.typography.positionXPercent + dx, positionYPercent = project.typography.positionYPercent + dy) },
                                    onTextScaleChange = { factor -> viewModel.updateTypographyProperties(fontSizeSp = (project.typography.fontSizeSp * factor).coerceIn(2f, 96f)) },
                                    videoAudioMuted = project.videoAudioMuted,
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
                                        onSeek = { positionMs ->
                                            viewModel.audioEngine.seekTo(positionMs)
                                            val ordered = project.lyrics.sortedBy { it.startMs }
                                            val active = ordered.lastOrNull { positionMs >= it.startMs && positionMs < it.endMs }
                                            val target = active ?: ordered.lastOrNull { it.endMs <= positionMs } ?: ordered.firstOrNull()
                                            target?.let { viewModel.highlightLyricLineAtPlayhead(it.id) }
                                        },
                                        onNudge = { viewModel.nudgeSelectedLyric(it) },
                                        onMoveLine = { viewModel.moveSelectedLyric(it) },
                                        onAdjustStart = { viewModel.updateSelectedLyricStart(it) },
                                        onAdjustDuration = { viewModel.updateSelectedLyricDuration(it) },
                                        onTextChange = { id, text -> viewModel.updateLyricText(id, text) },
                                        onAddLine = { viewModel.addLyricLine() },
                                        onDeleteLine = { viewModel.deleteSelectedLyric() },
                                        onOpenTapToSync = { viewModel.startTapToSync() },
                                        onMoveMediaClip = { id, delta -> viewModel.moveMediaClip(id, delta) },
                                        onResizeMediaClip = { id, delta -> viewModel.resizeMediaClip(id, delta) },
                                        onTrimMediaClipStart = { id, delta -> viewModel.trimMediaClipStart(id, delta) },
                                        videoAudioMuted = project.videoAudioMuted,
                                        onToggleVideoMute = { viewModel.setVideoAudioMuted(!project.videoAudioMuted) },
                                        onRemoveMediaClip = { id -> viewModel.removeMediaClip(id) }
                                    )

                                    StudioTabs(
                                        viewModel = viewModel,
                                        project = project,
                                        selectedLineId = selectedLineId,
                                        playheadMs = playheadMs,
                                        currentTab = currentTab,
                                        onTabSelected = { viewModel.selectTab(it) },
                                        onOpenTapToSync = { viewModel.startTapToSync() },
                                        onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                                        onPickVideo = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                                        onPickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        } else {
                            // Mobile vertical studio layout with a draggable bottom editor sheet.
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Keep preview + timeline in a stable layout. The editor sheet
                                // overlays the lower preview area; expanding it must not remeasure
                                // the timeline or move its tracks upward.
                                Column(modifier = Modifier.fillMaxSize()) {
                                    VideoPreviewPlayer(
                                        project = project.copy(showWatermark = !hasConfirmedPermanentWatermarkRemoval),
                                        playheadMs = playheadMs,
                                        isPlaying = isPlaying,
                                        spectrumBands = spectrumBands,
                                        bassEnergy = bassEnergy,
                                        onTogglePlay = { viewModel.audioEngine.togglePlayPause() },
                                        onSeek = { viewModel.audioEngine.seekTo(it) },
                                        onTextPositionChange = { dx, dy -> viewModel.updateTypographyProperties(positionXPercent = project.typography.positionXPercent + dx, positionYPercent = project.typography.positionYPercent + dy) },
                                        onTextScaleChange = { factor -> viewModel.updateTypographyProperties(fontSizeSp = (project.typography.fontSizeSp * factor).coerceIn(2f, 96f)) },
                                        videoAudioMuted = project.videoAudioMuted
                                    )

                                    MultiTrackTimeline(
                                        project = project,
                                        playheadMs = playheadMs,
                                        selectedLineId = selectedLineId,
                                        onSelectLine = { viewModel.selectLyricLine(it) },
                                        onSeek = { positionMs ->
                                            viewModel.audioEngine.seekTo(positionMs)
                                            val ordered = project.lyrics.sortedBy { it.startMs }
                                            val active = ordered.lastOrNull { positionMs >= it.startMs && positionMs < it.endMs }
                                            val target = active ?: ordered.lastOrNull { it.endMs <= positionMs } ?: ordered.firstOrNull()
                                            target?.let { viewModel.highlightLyricLineAtPlayhead(it.id) }
                                        },
                                        onNudge = { viewModel.nudgeSelectedLyric(it) },
                                        onMoveLine = { viewModel.moveSelectedLyric(it) },
                                        onAdjustStart = { viewModel.updateSelectedLyricStart(it) },
                                        onAdjustDuration = { viewModel.updateSelectedLyricDuration(it) },
                                        onTextChange = { id, text -> viewModel.updateLyricText(id, text) },
                                        onAddLine = { viewModel.addLyricLine() },
                                        onDeleteLine = { viewModel.deleteSelectedLyric() },
                                        onOpenTapToSync = { viewModel.startTapToSync() },
                                        onMoveMediaClip = { id, delta -> viewModel.moveMediaClip(id, delta) },
                                        onResizeMediaClip = { id, delta -> viewModel.resizeMediaClip(id, delta) },
                                        onTrimMediaClipStart = { id, delta -> viewModel.trimMediaClipStart(id, delta) },
                                        videoAudioMuted = project.videoAudioMuted,
                                        onToggleVideoMute = { viewModel.setVideoAudioMuted(!project.videoAudioMuted) },
                                        onRemoveMediaClip = { id -> viewModel.removeMediaClip(id) }
                                    )
                                }

                                StudioTabs(
                                    viewModel = viewModel,
                                    project = project,
                                    selectedLineId = selectedLineId,
                                    playheadMs = playheadMs,
                                    currentTab = currentTab,
                                    onTabSelected = { viewModel.selectTab(it) },
                                    onOpenTapToSync = { viewModel.startTapToSync() },
                                    onPickAudio = { audioPicker.launch(arrayOf("audio/*")) },
                                    onPickVideo = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) },
                                    onPickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    isExpanded = studioSheetExpanded,
                                    onToggleExpanded = { studioSheetExpanded = it },
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .fillMaxWidth()
                                        .height(studioSheetHeight)
                                        .shadow(18.dp, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                                        .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                                        .border(1.dp, StudioCardBorder, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                                )
                            }
                        }
                    }

                    // Dialogs & Sheets

                    if (showSaveProjectDialog) {
                        AlertDialog(
                            onDismissRequest = { showSaveProjectDialog = false },
                            containerColor = StudioSurface,
                            title = { Text("Save Project", color = TextPrimary, fontWeight = FontWeight.Bold) },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Enter a name for this project", color = TextSecondary, fontSize = 12.sp)
                                    OutlinedTextField(
                                        value = saveProjectName,
                                        onValueChange = { saveProjectName = it },
                                        singleLine = true,
                                        label = { Text("Project name") },
                                        placeholder = { Text("My Lyric Project") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            },
                            confirmButton = {
                                TextButton(
                                    enabled = saveProjectName.isNotBlank() && !isSavingProject,
                                    onClick = {
                                        val name = saveProjectName.trim()
                                        saveProjectScope.launch {
                                            isSavingProject = true
                                            try {
                                                withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    LyricMakerStorage.saveProject(this@MainActivity, project, name)
                                                }
                                                showSaveProjectDialog = false
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Project saved as $name. Audio, image and video assets copied to Documents/LyricMakerPro/Asset.",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } catch (error: Exception) {
                                                Toast.makeText(
                                                    this@MainActivity,
                                                    "Save failed: ${error.localizedMessage}",
                                                    Toast.LENGTH_LONG
                                                ).show()
                                            } finally {
                                                isSavingProject = false
                                            }
                                        }
                                    }
                                ) {
                                    if (isSavingProject) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = NeonCyan)
                                    } else {
                                        Text("Save", color = NeonCyan)
                                    }
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showSaveProjectDialog = false }) {
                                    Text("Cancel", color = TextSecondary)
                                }
                            }
                        )
                    }

                    if (showNewProjectDialog) {
                        AlertDialog(
                            onDismissRequest = { showNewProjectDialog = false },
                            containerColor = StudioSurface,
                            title = { Text("Create New Project", color = TextPrimary, fontWeight = FontWeight.Bold) },
                            text = {
                                Text(
                                    "This will clear the current audio, lyrics, and all timeline clips. Previously saved projects will remain available.",
                                    color = TextSecondary
                                )
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    viewModel.createNewProject()
                                    showNewProjectDialog = false
                                    saveProjectName = "Untitled Project"
                                    Toast.makeText(this@MainActivity, "New project created", Toast.LENGTH_SHORT).show()
                                }) { Text("New Project", color = NeonCyan) }
                            },
                            dismissButton = {
                                TextButton(onClick = { showNewProjectDialog = false }) {
                                    Text("Cancel", color = TextSecondary)
                                }
                            }
                        )
                    }

                    if (isTapToSyncActive) {
                        TapToSyncModal(
                            project = project,
                            currentLineIndex = syncLineIndex,
                            playheadMs = playheadMs,
                            isPlaying = isPlaying,
                            onTapNext = { viewModel.tapSyncNext() },
                            onResetSync = { viewModel.resetTapToSync() },
                            onTogglePlayback = { viewModel.audioEngine.togglePlayPause() },
                            onSeek = { positionMs -> viewModel.seekTapSyncTo(positionMs) },
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
                            isPermanentWatermarkRemoved = hasConfirmedPermanentWatermarkRemoval,
                            permanentWatermarkPrice = permanentWatermarkPrice,
                            onBuyPermanentWatermark = { watermarkBilling.launchPurchase(this@MainActivity) },
                            onRestorePurchases = { watermarkBilling.restorePurchases() },
                            onStartExport = { quality, durationSec ->
                                viewModel.startExport(quality, durationSec, removeWatermark = hasConfirmedPermanentWatermarkRemoval)
                            },
                            onWatchAdAndExport = { quality, durationSec ->
                                rewardedAdManager.show(
                                    onRewardEarned = {
                                        viewModel.startExport(quality, durationSec, removeWatermark = true)
                                    },
                                    onUnavailable = {
                                        Toast.makeText(this@MainActivity, "Rewarded ad belum tersedia. Coba beberapa saat lagi atau ekspor dengan watermark.", Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            onExportCover = { viewModel.exportCoverImage() },
                            onShare = { viewModel.shareLastExported() },
                            onExportAgain = { viewModel.prepareAnotherExport() },
                            onCancelExport = { viewModel.cancelExport() },
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
    onPickVideo: () -> Unit,
    onOpenExport: () -> Unit,
    onPickLyrics: () -> Unit,
    onOpenProject: () -> Unit,
    onSaveProject: () -> Unit,
    onSaveLrc: () -> Unit,
    onNewProject: () -> Unit,
    onExit: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var languageDialog by remember { mutableStateOf(false) }
    var privacyDialog by remember { mutableStateOf(false) }
    var aboutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("lyricmakerpro_settings", android.content.Context.MODE_PRIVATE) }
    var selectedLanguage by remember { mutableStateOf(preferences.getString("interface_language", "English") ?: "English") }
    SideEffect { AppLanguage.current = selectedLanguage }
    fun selectLanguage(language: String) {
        selectedLanguage = language
        preferences.edit().putString("interface_language", language).apply()
        AppLanguage.current = language
    }
    fun tr(value: String) = AppLanguage.text(value)

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
                        .size(40.dp)
                        .clickable { menuExpanded = true }
                        .clip(RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.lyricmakepro_ico),
                        contentDescription = "Open application menu",
                        modifier = Modifier.fillMaxSize()
                    )
                    var projectSubmenuExpanded by remember { mutableStateOf(false) }
                    var mediaSubmenuExpanded by remember { mutableStateOf(false) }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false; projectSubmenuExpanded = false; mediaSubmenuExpanded = false },
                        modifier = Modifier.widthIn(min = 230.dp).background(StudioSurface)
                    ) {
                        DropdownMenuItem(
                            text = { Text(tr("Project"), fontWeight = FontWeight.SemiBold) },
                            leadingIcon = { Icon(Icons.Default.FolderOpen, null, tint = NeonCyan) },
                            trailingIcon = { Icon(Icons.Default.ChevronRight, null, tint = TextSecondary) },
                            onClick = { projectSubmenuExpanded = !projectSubmenuExpanded; mediaSubmenuExpanded = false }
                        )
                        Box {
                            DropdownMenu(
                                expanded = projectSubmenuExpanded,
                                onDismissRequest = { projectSubmenuExpanded = false },
                                modifier = Modifier.widthIn(min = 200.dp).background(StudioSurface)
                            ) {
                                DropdownMenuItem(text = { Text(tr("New")) }, leadingIcon = { Icon(Icons.Default.NoteAdd, null) }, onClick = { menuExpanded = false; projectSubmenuExpanded = false; onNewProject() })
                                DropdownMenuItem(text = { Text(tr("Open")) }, leadingIcon = { Icon(Icons.Default.FolderOpen, null) }, onClick = { menuExpanded = false; projectSubmenuExpanded = false; onOpenProject() })
                                DropdownMenuItem(text = { Text(tr("Save")) }, leadingIcon = { Icon(Icons.Default.Save, null) }, onClick = { menuExpanded = false; projectSubmenuExpanded = false; onSaveProject() })
                            }
                        }
                        DropdownMenuItem(
                            text = { Text(tr("Insert Media"), fontWeight = FontWeight.SemiBold) },
                            leadingIcon = { Icon(Icons.Default.Add, null, tint = NeonCyan) },
                            trailingIcon = { Icon(Icons.Default.ChevronRight, null, tint = TextSecondary) },
                            onClick = { mediaSubmenuExpanded = !mediaSubmenuExpanded; projectSubmenuExpanded = false }
                        )
                        Box {
                            DropdownMenu(
                                expanded = mediaSubmenuExpanded,
                                onDismissRequest = { mediaSubmenuExpanded = false },
                                modifier = Modifier.widthIn(min = 200.dp).background(StudioSurface)
                            ) {
                                DropdownMenuItem(text = { Text(tr("Audio")) }, leadingIcon = { Icon(Icons.Default.AudioFile, null, tint = NeonCyan) }, onClick = { menuExpanded = false; mediaSubmenuExpanded = false; onPickAudio() })
                                DropdownMenuItem(text = { Text(tr("Image")) }, leadingIcon = { Icon(Icons.Default.Image, null, tint = NeonCyan) }, onClick = { menuExpanded = false; mediaSubmenuExpanded = false; onPickImage() })
                                DropdownMenuItem(text = { Text(tr("Video")) }, leadingIcon = { Icon(Icons.Default.VideoFile, null, tint = NeonMagenta) }, onClick = { menuExpanded = false; mediaSubmenuExpanded = false; onPickVideo() })
                            }
                        }
                        DropdownMenuItem(
                            text = { Text(tr("Import Lyrics (.TXT / .LRC)")) },
                            leadingIcon = { Icon(Icons.Default.Subtitles, null, tint = NeonCyan) },
                            onClick = { menuExpanded = false; onPickLyrics() }
                        )
                        HorizontalDivider(color = StudioCardBorder)
                        DropdownMenuItem(
                            text = { Text(tr("Save Lyrics (.LRC)")) },
                            leadingIcon = { Icon(Icons.Default.Subtitles, null, tint = NeonCyan) },
                            onClick = { menuExpanded = false; onSaveLrc() }
                        )
                        HorizontalDivider(color = StudioCardBorder)
                        DropdownMenuItem(
                            text = { Text(tr("Privacy Policy")) },
                            leadingIcon = { Icon(Icons.Default.PrivacyTip, null) },
                            onClick = { menuExpanded = false; privacyDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text("${tr("Language")}  ·  $selectedLanguage") },
                            leadingIcon = { Icon(Icons.Default.Language, null, tint = NeonCyan) },
                            trailingIcon = { Icon(Icons.Default.ChevronRight, null) },
                            onClick = { menuExpanded = false; languageDialog = true }
                        )
                        DropdownMenuItem(
                            text = { Text(tr("About")) },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                            onClick = { menuExpanded = false; aboutDialog = true }
                        )
                        HorizontalDivider(color = StudioCardBorder)
                        DropdownMenuItem(
                            text = { Text(tr("Exit"), color = Color(0xFFFF5370)) },
                            leadingIcon = { Icon(Icons.Default.ExitToApp, null, tint = Color(0xFFFF5370)) },
                            onClick = {
                                menuExpanded = false
                                onExit()
                            }
                        )
                    }
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
                        text = tr("Export"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
    
    if (languageDialog) {
        AlertDialog(
            onDismissRequest = { languageDialog = false },
            containerColor = StudioSurface,
            title = { Text(tr("Language"), color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    AppLanguage.supported.forEach { language ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectLanguage(language)
                                    languageDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedLanguage == language,
                                onClick = {
                                    selectLanguage(language)
                                    languageDialog = false
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = NeonCyan)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(language, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { languageDialog = false }) {
                    Text(tr("Close"), color = NeonCyan)
                }
            }
        )
    }

    if (privacyDialog) {
        AlertDialog(
            onDismissRequest = { privacyDialog = false },
            containerColor = StudioSurface,
            title = { Text(tr("Privacy Policy"), color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text(text = """LyricMakerPro Privacy Policy

Last updated: November 2, 2026

1. About this app
LyricMakerPro is an Android lyric-video editor. It lets you import audio, images, videos and lyric files, synchronize lyrics, save projects, preview compositions and export videos.

2. Files and information you choose
The app accesses audio, images, videos, lyrics and project documents only when you select them through Android system pickers. Selected files are processed to provide editing, preview and export features. Project data and exported files are stored on your device. Core editing features do not require an account.

3. Advertising
The app uses Google Mobile Ads, including optional rewarded ads for export features. Google and its advertising partners may process device identifiers, IP address, app interaction and advertising-related data to deliver, measure and protect advertising. Processing is subject to Google's policies and your device/account settings. See https://policies.google.com/privacy.

4. Network and third-party services
The app uses internet access for advertising and configured Google services. Those providers process information under their own privacy policies. LyricMakerPro does not sell personal information.

5. Storage, retention and deletion
Imported media, project files and exported videos are stored locally on your device in app-managed or user-selected locations. You can remove those files using the app or your device's file manager. Data collected by Google services is governed by Google's retention and deletion controls.

6. Permissions and security
Access to selected media is granted through Android system pickers. Internet access is used for online services and advertising. Keep your device updated and avoid sharing project files that contain private material.

7. Children
LyricMakerPro is not specifically directed to children. If you believe a child has submitted personal information to a third-party service, contact that provider and the developer through the app's Google Play listing.

8. Policy changes and contact
This policy may change as app features or legal requirements evolve. For privacy questions, contact Wins Mediatama using the developer contact information on the LyricMakerPro Google Play Store listing.

By using LyricMakerPro, you acknowledge this policy.""", color = TextPrimary, fontSize = 12.sp, lineHeight = 18.sp)
                }
            },
            confirmButton = { TextButton(onClick = { privacyDialog = false }) { Text(tr("Close"), color = NeonCyan) } }
        )
    }
    if (aboutDialog) {
        AlertDialog(
            onDismissRequest = { aboutDialog = false },
            containerColor = StudioSurface,
            title = { Text("About LyricMakerPro", color = TextPrimary, fontWeight = FontWeight.Bold) },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("VERSION INFORMATION", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("Installed version: ${com.winsmediatama.lyricmakerpro.BuildConfig.VERSION_NAME}", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Version code: ${com.winsmediatama.lyricmakerpro.BuildConfig.VERSION_CODE}", color = TextSecondary, fontSize = 12.sp)
                    Text(
                        "Version note: This release includes the latest available LyricMakerPro features and improvements. Check the installed version above when reporting an issue or requesting support.",
                        color = TextPrimary,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )

                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.25f))
                    Text("HOW TO USE LYRICMAKERPRO", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)

                    Text("1. Create or open a project", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Start a new project, or open a previously saved project to continue editing.")

                    Text("2. Import your audio", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Choose an audio track from your device. The audio waveform and timeline help you navigate through the song.")

                    Text("3. Add your lyrics", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Enter lyrics line by line in the lyric editor, or load an LRC lyric file when you already have timed lyrics. Add or edit lines whenever needed.")

                    Text("4. Synchronize lyrics with the music", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Use Tap to Sync while the song is playing and tap for each lyric line as it is sung. You can also select a position on the audio timeline to move playback and adjust lyric timing.")

                    Text("5. Add a background", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Import a photo or video to use as the visual background for your lyric video. Arrange your media on the timeline and preview the result.")

                    Text("6. Customize the look", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Choose a visualizer or template, then adjust lyric typography, colors, size and position to match your style.")

                    Text("7. Preview and export", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Play the preview and check that the lyrics are synchronized. Open Export, select your preferred video quality and duration, then start exporting. Follow any on-screen ad or purchase instructions if they apply to your account.")

                    Text("8. Save your work", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    Text("Save the project so you can reopen and edit it later. You can also save your synchronized lyrics as an LRC file.")

                    HorizontalDivider(color = TextSecondary.copy(alpha = 0.25f))
                    Text("TIPS", color = NeonCyan, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text("• Use clear, properly formatted lyrics for easier synchronization.\n• Preview the entire timeline before exporting.\n• Keep your original audio, photo and video files available when reopening a project if Android requests them.", color = TextSecondary, fontSize = 12.sp, lineHeight = 18.sp)
                    Text("Developed by Wins Mediatama", color = TextSecondary, fontSize = 12.sp)
                }
            },
            confirmButton = { TextButton(onClick = { aboutDialog = false }) { Text(tr("Close"), color = NeonCyan) } }
        )
    }

}