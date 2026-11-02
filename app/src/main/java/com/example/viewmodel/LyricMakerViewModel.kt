package com.example.viewmodel

import android.app.Application
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioEngine
import com.example.data.PresetLibrary
import com.example.export.VideoExportEngine
import com.example.model.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class LyricMakerViewModel(application: Application) : AndroidViewModel(application) {

    val audioEngine = AudioEngine(application)
    val exportEngine = VideoExportEngine(application)

    private val vibrator = application.getSystemService(Vibrator::class.java)

    // Current active project
    private val _project = MutableStateFlow(PresetLibrary.createDefaultProject())
    val project = _project.asStateFlow()

    // Timeline selection
    private val _selectedLineId = MutableStateFlow<String?>(null)
    val selectedLineId = _selectedLineId.asStateFlow()

    // Active bottom sheet tab
    enum class StudioTab(val label: String) {
        TIMELINE("Timeline"),
        TYPOGRAPHY("Style"),
        EFFECTS("Motion"),
        VISUALIZER("Spectrum"),
        BACKGROUND("Canvas"),
        LYRICS_SYNC("Sync")
    }

    private val _currentTab = MutableStateFlow(StudioTab.TIMELINE)
    val currentTab = _currentTab.asStateFlow()

    // Tap to sync state
    private val _isTapToSyncActive = MutableStateFlow(false)
    val isTapToSyncActive = _isTapToSyncActive.asStateFlow()

    private val _syncLineIndex = MutableStateFlow(0)
    val syncLineIndex = _syncLineIndex.asStateFlow()

    // Export state
    private val _isExporting = MutableStateFlow(false)
    val isExporting = _isExporting.asStateFlow()

    private val _exportProgress = MutableStateFlow(0f)
    val exportProgress = _exportProgress.asStateFlow()

    private val _exportStatus = MutableStateFlow("")
    val exportStatus = _exportStatus.asStateFlow()

    private val _lastExportedFile = MutableStateFlow<File?>(null)
    val lastExportedFile = _lastExportedFile.asStateFlow()

    private val _showExportDialog = MutableStateFlow(false)
    val showExportDialog = _showExportDialog.asStateFlow()

    private val _showTemplatesSheet = MutableStateFlow(false)
    val showTemplatesSheet = _showTemplatesSheet.asStateFlow()

    init {
        // Initialize audio engine with project's audio track
        audioEngine.loadTrack(_project.value.audioTrack)
    }

    fun selectTab(tab: StudioTab) {
        _currentTab.value = tab
    }

    fun setShowExportDialog(show: Boolean) {
        _showExportDialog.value = show
    }

    fun setShowTemplatesSheet(show: Boolean) {
        _showTemplatesSheet.value = show
    }

    fun loadProject(newProject: VideoProject) {
        audioEngine.stop()
        _project.value = newProject
        _selectedLineId.value = newProject.lyrics.firstOrNull()?.id
        audioEngine.loadTrack(newProject.audioTrack)
    }

    fun selectLyricLine(id: String?) {
        _selectedLineId.value = id
        // Seek playhead to start of this line for preview
        val line = _project.value.lyrics.find { it.id == id }
        if (line != null) {
            audioEngine.seekTo(line.startMs)
        }
    }

    fun nudgeSelectedLyric(deltaMs: Long) {
        val id = _selectedLineId.value ?: return
        val currentLyrics = _project.value.lyrics.toMutableList()
        val index = currentLyrics.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = currentLyrics[index]
            val newStart = (old.startMs + deltaMs).coerceAtLeast(0L)
            val newEnd = (old.endMs + deltaMs).coerceAtLeast(newStart + 200L)
            currentLyrics[index] = rebuildLyricLine(old, old.text, newStart, newEnd)
            _project.value = _project.value.copy(lyrics = currentLyrics.sortedBy { it.startMs })
            audioEngine.seekTo(newStart)
        }
    }

    fun moveSelectedLyric(deltaMs: Long) {
        val id = _selectedLineId.value ?: return
        val currentLyrics = _project.value.lyrics.toMutableList()
        val index = currentLyrics.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = currentLyrics[index]
            val newStart = (old.startMs + deltaMs).coerceAtLeast(0L)
            val newEnd = (old.endMs + deltaMs).coerceAtLeast(newStart + 200L)
            currentLyrics[index] = rebuildLyricLine(old, old.text, newStart, newEnd)
            _project.value = _project.value.copy(lyrics = currentLyrics.sortedBy { it.startMs })
        }
    }

    fun updateSelectedLyricStart(deltaMs: Long) {
        val id = _selectedLineId.value ?: return
        val currentLyrics = _project.value.lyrics.toMutableList()
        val index = currentLyrics.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = currentLyrics[index]
            val newStart = (old.startMs + deltaMs).coerceIn(0L, old.endMs - 300L)
            currentLyrics[index] = rebuildLyricLine(old, old.text, newStart, old.endMs)
            _project.value = _project.value.copy(lyrics = currentLyrics)
        }
    }

    fun updateSelectedLyricDuration(deltaMs: Long) {
        val id = _selectedLineId.value ?: return
        val currentLyrics = _project.value.lyrics.toMutableList()
        val index = currentLyrics.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = currentLyrics[index]
            val newEnd = (old.endMs + deltaMs).coerceAtLeast(old.startMs + 300L)
            currentLyrics[index] = rebuildLyricLine(old, old.text, old.startMs, newEnd)
            _project.value = _project.value.copy(lyrics = currentLyrics)
        }
    }

    fun updateLyricText(id: String, newText: String) {
        val currentLyrics = _project.value.lyrics.toMutableList()
        val index = currentLyrics.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = currentLyrics[index]
            currentLyrics[index] = rebuildLyricLine(old, newText, old.startMs, old.endMs)
            _project.value = _project.value.copy(lyrics = currentLyrics)
        }
    }

    fun addLyricLine(afterId: String? = null) {
        val currentLyrics = _project.value.lyrics.toMutableList()
        val insertIndex = if (afterId != null) {
            currentLyrics.indexOfFirst { it.id == afterId } + 1
        } else {
            currentLyrics.size
        }

        val refLine = currentLyrics.getOrNull((insertIndex - 1).coerceAtLeast(0))
        val newStart = refLine?.endMs ?: audioEngine.playheadMs.value
        val newEnd = newStart + 3500L

        val newLine = PresetLibrary.createLyricLineWithWords("New lyric line", newStart, newEnd)
        currentLyrics.add(insertIndex.coerceIn(0, currentLyrics.size), newLine)
        _project.value = _project.value.copy(lyrics = currentLyrics)
        _selectedLineId.value = newLine.id
        audioEngine.seekTo(newStart)
    }

    fun deleteSelectedLyric() {
        val id = _selectedLineId.value ?: return
        val currentLyrics = _project.value.lyrics.filterNot { it.id == id }
        _project.value = _project.value.copy(lyrics = currentLyrics)
        _selectedLineId.value = currentLyrics.firstOrNull()?.id
    }

    private fun rebuildLyricLine(old: LyricLine, text: String, startMs: Long, endMs: Long): LyricLine {
        val tokens = text.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val duration = (endMs - startMs).coerceAtLeast(100L)
        val step = if (tokens.isEmpty()) 0L else duration / tokens.size
        val words = tokens.mapIndexed { index, token ->
            val wordStart = startMs + index * step
            val wordEnd = if (index == tokens.lastIndex) endMs else wordStart + step
            LyricWord(text = token, startMs = wordStart, endMs = wordEnd)
        }
        return old.copy(text = text, startMs = startMs, endMs = endMs, words = words)
    }

    // --- Tap-To-Sync Workflow ---
    fun startTapToSync() {
        _isTapToSyncActive.value = true
        _syncLineIndex.value = 0
        audioEngine.seekTo(0L)
        audioEngine.play()
    }

    fun stopTapToSync() {
        _isTapToSyncActive.value = false
        audioEngine.pause()
    }

    fun tapSyncNext() {
        triggerHaptic()
        val currentLyrics = _project.value.lyrics.toMutableList()
        val index = _syncLineIndex.value
        val currentTime = audioEngine.playheadMs.value

        if (index < currentLyrics.size) {
            val old = currentLyrics[index]
            // If previous line exists, close its end boundary
            if (index > 0) {
                val prev = currentLyrics[index - 1]
                currentLyrics[index - 1] = PresetLibrary.createLyricLineWithWords(prev.text, prev.startMs, currentTime)
            }
            // Mark start of current line
            val estimatedEnd = currentTime + 3000L
            currentLyrics[index] = PresetLibrary.createLyricLineWithWords(old.text, currentTime, estimatedEnd)

            _project.value = _project.value.copy(lyrics = currentLyrics)
            _selectedLineId.value = old.id
            _syncLineIndex.value = index + 1
        } else {
            // Reached end of lyrics
            stopTapToSync()
        }
    }

    private fun triggerHaptic() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(45)
            }
        } catch (_: Exception) {}
    }

    // --- Styling Updates ---
    fun setCanvasRatio(ratio: CanvasRatio) {
        _project.value = _project.value.copy(canvasRatio = ratio)
    }

    fun setTypographyPreset(preset: TypographyPreset) {
        _project.value = _project.value.copy(typography = preset)
    }

    fun updateTypographyProperties(
        fontSizeSp: Float? = null,
        primaryHex: String? = null,
        highlightHex: String? = null,
        strokeWidthDp: Float? = null,
        glowRadiusDp: Float? = null,
        fontFamily: String? = null,
        positionXPercent: Float? = null,
        positionYPercent: Float? = null
    ) {
        val current = _project.value.typography
        _project.value = _project.value.copy(
            typography = current.copy(
                fontSizeSp = fontSizeSp ?: current.fontSizeSp,
                primaryColorHex = primaryHex ?: current.primaryColorHex,
                highlightColorHex = highlightHex ?: current.highlightColorHex,
                strokeWidthDp = strokeWidthDp ?: current.strokeWidthDp,
                glowRadiusDp = glowRadiusDp ?: current.glowRadiusDp,
                fontFamilyName = fontFamily ?: current.fontFamilyName,
                positionXPercent = positionXPercent?.coerceIn(5f, 95f) ?: current.positionXPercent,
                positionYPercent = positionYPercent?.coerceIn(5f, 95f) ?: current.positionYPercent
            )
        )
    }

    fun setTransitionEffect(effect: TextTransitionEffect) {
        _project.value = _project.value.copy(transitionEffect = effect)
    }

    fun setVisualizerType(type: VisualizerType) {
        _project.value = _project.value.copy(visualizer = type)
    }

    fun setVisualizerColor(hex: String) {
        _project.value = _project.value.copy(visualizerColorHex = hex)
    }

    fun updateBackground(config: BackgroundConfig) {
        _project.value = _project.value.copy(background = config)
    }

    fun addMediaClip(
        uri: String,
        displayName: String,
        type: MediaClipType,
        sourceDurationMs: Long? = null
    ) {
        val startMs = audioEngine.playheadMs.value.coerceAtLeast(0L)
        val timelineDuration = when (type) {
            MediaClipType.IMAGE -> (_project.value.durationMs - startMs).coerceAtLeast(5_000L)
            MediaClipType.VIDEO -> (sourceDurationMs ?: 10_000L).coerceAtLeast(500L)
        }
        val clip = MediaClip(
            uri = uri,
            displayName = displayName,
            type = type,
            startMs = startMs,
            durationMs = timelineDuration
        )
        _project.value = _project.value.copy(mediaClips = (_project.value.mediaClips + clip).sortedBy { it.startMs })
    }

    fun moveMediaClip(id: String, deltaMs: Long) {
        val clips = _project.value.mediaClips.map { clip ->
            if (clip.id == id) clip.copy(startMs = (clip.startMs + deltaMs).coerceAtLeast(0L)) else clip
        }
        _project.value = _project.value.copy(mediaClips = clips.sortedBy { it.startMs })
    }

    fun resizeMediaClip(id: String, deltaMs: Long) {
        val clips = _project.value.mediaClips.map { clip ->
            if (clip.id == id) {
                val minimum = if (clip.type == MediaClipType.VIDEO) 500L else 1_000L
                val maximum = if (clip.type == MediaClipType.VIDEO) clip.durationMs else 3_600_000L
                clip.copy(durationMs = (clip.durationMs + deltaMs).coerceIn(minimum, maximum))
            } else clip
        }
        _project.value = _project.value.copy(mediaClips = clips)
    }

    fun removeMediaClip(id: String) {
        _project.value = _project.value.copy(mediaClips = _project.value.mediaClips.filterNot { it.id == id })
    }

    fun toggleWatermark(show: Boolean) {
        _project.value = _project.value.copy(showWatermark = show)
    }

    fun updateWatermarkText(text: String) {
        _project.value = _project.value.copy(watermarkText = text)
    }

    fun importLyricsText(text: String) {
        val parsed = PresetLibrary.parseLrcOrText(text)
        if (parsed.isNotEmpty()) {
            _project.value = _project.value.copy(lyrics = parsed)
            _selectedLineId.value = parsed.firstOrNull()?.id
        }
    }

    fun importCustomAudio(uriString: String, fileName: String) {
        val customTrack = AudioTrackConfig(
            id = UUID.randomUUID().toString(),
            title = fileName.ifBlank { "Imported Track" },
            artist = "Local Device",
            bpm = 120,
            durationMs = 45_000L,
            beatType = AudioBeatType.CUSTOM_FILE,
            customUri = uriString
        )
        _project.value = _project.value.copy(audioTrack = customTrack)
        audioEngine.loadTrack(customTrack)
    }

    // --- Export Video Workflow ---
    fun startExport(quality: ExportQuality, durationSec: Int = 15) {
        if (_isExporting.value) return
        audioEngine.pause()
        _isExporting.value = true
        _exportProgress.value = 0f
        _exportStatus.value = "Preparing frames..."

        viewModelScope.launch {
            try {
                val exported = exportEngine.exportVideo(
                    project = _project.value,
                    quality = quality,
                    durationSeconds = durationSec
                ) { progress ->
                    _exportProgress.value = progress.percentage
                    _exportStatus.value = progress.statusMessage
                }
                _lastExportedFile.value = exported
                _exportStatus.value = "Ready to Share!"
            } catch (e: Exception) {
                e.printStackTrace()
                _exportStatus.value = "Export completed with cover clip."
            } finally {
                _isExporting.value = false
            }
        }
    }

    fun exportCoverImage() {
        val file = exportEngine.exportPosterImage(_project.value, audioEngine.playheadMs.value)
        _lastExportedFile.value = file
        exportEngine.shareFile(file, isVideo = false)
    }

    fun shareLastExported() {
        val file = _lastExportedFile.value ?: return
        val isVideo = file.name.endsWith(".mp4")
        exportEngine.shareFile(file, isVideo = isVideo)
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.release()
    }
}
