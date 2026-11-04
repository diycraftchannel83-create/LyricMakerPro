package com.winsmediatama.lyricmakerpro.viewmodel

import android.app.Application
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.winsmediatama.lyricmakerpro.audio.AudioEngine
import com.winsmediatama.lyricmakerpro.audio.AudioWaveformExtractor
import com.winsmediatama.lyricmakerpro.data.PresetLibrary
import com.winsmediatama.lyricmakerpro.export.VideoExportEngine
import com.winsmediatama.lyricmakerpro.model.*
import kotlinx.coroutines.Job
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
    private var tapSyncTrackingJob: Job? = null
    private var tapSyncLastCommittedIndex: Int = -1
    private var isFreshTapSyncSession: Boolean = false

    // Export state
    private val _isExporting = MutableStateFlow(false)
    val isExporting = _isExporting.asStateFlow()
    private var exportJob: Job? = null

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

    fun prepareAnotherExport() {
        if (_isExporting.value) return
        _lastExportedFile.value = null
        _exportProgress.value = 0f
        _exportStatus.value = ""
    }

    fun setShowTemplatesSheet(show: Boolean) {
        _showTemplatesSheet.value = show
    }

    fun createNewProject() {
        // A new project starts with no audio source, lyric cues, or media timeline.
        audioEngine.stop()
        val emptyProject = PresetLibrary.createDefaultProject().copy(
            title = "Untitled Project",
            artist = "",
            audioTrack = AudioTrackConfig(
                title = "No audio",
                artist = "",
                durationMs = 0L,
                beatType = AudioBeatType.NONE,
                customUri = null,
                waveformPeaks = emptyList()
            ),
            lyrics = emptyList(),
            mediaClips = emptyList(),
            videoAudioMuted = false
        )
        _project.value = emptyProject
        _selectedLineId.value = null
        _currentTab.value = StudioTab.TIMELINE
        _isTapToSyncActive.value = false
        _syncLineIndex.value = 0
        tapSyncTrackingJob?.cancel()
        tapSyncTrackingJob = null
        tapSyncLastCommittedIndex = -1
        isFreshTapSyncSession = false
        audioEngine.loadTrack(emptyProject.audioTrack)
    }

    fun loadProject(newProject: VideoProject) {
        // Restore the complete saved editor state before touching playback so Compose
        // receives one consistent project snapshot (including the original word timings).
        audioEngine.stop()
        val restoredLyrics = newProject.lyrics
            .sortedWith(compareBy<LyricLine> { it.startMs }.thenBy { it.endMs })
        val restoredProject = newProject.copy(lyrics = restoredLyrics)
        _project.value = restoredProject
        _selectedLineId.value = restoredLyrics.firstOrNull()?.id
        _currentTab.value = StudioTab.TIMELINE
        _isTapToSyncActive.value = false
        _syncLineIndex.value = 0

        // loadTrack reopens the persisted content:// URI. Keep the saved URI and
        // per-word timing intact; do not rebuild lyric lines during project restore.
        audioEngine.loadTrack(restoredProject.audioTrack)

        // Older project files may not contain cached waveform data. Recreate it from
        // the original audio URI without replacing any saved lyric/timing information.
        val track = restoredProject.audioTrack
        if (track.beatType == AudioBeatType.CUSTOM_FILE &&
            !track.customUri.isNullOrBlank() &&
            track.waveformPeaks.isEmpty()
        ) {
            viewModelScope.launch {
                val peaks = AudioWaveformExtractor.extract(
                    context = getApplication(),
                    uriString = track.customUri,
                    durationMs = track.durationMs
                )
                if (peaks.isNotEmpty() && _project.value.audioTrack.id == track.id) {
                    _project.value = _project.value.copy(
                        audioTrack = _project.value.audioTrack.copy(waveformPeaks = peaks)
                    )
                }
            }
        }
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
        val current = _project.value.lyrics
        val old = current.firstOrNull { it.id == id } ?: return
        val updated = moveLyricWithinNeighbours(current, id, old.startMs + deltaMs)
        _project.value = _project.value.copy(lyrics = updated)
        updated.firstOrNull { it.id == id }?.let { audioEngine.seekTo(it.startMs) }
    }

    fun moveSelectedLyric(deltaMs: Long) {
        val id = _selectedLineId.value ?: return
        val current = _project.value.lyrics
        val old = current.firstOrNull { it.id == id } ?: return
        _project.value = _project.value.copy(
            lyrics = moveLyricWithinNeighbours(current, id, old.startMs + deltaMs)
        )
    }

    private fun moveLyricWithinNeighbours(
        lyrics: List<LyricLine>,
        id: String,
        requestedStartMs: Long
    ): List<LyricLine> {
        val ordered = lyrics.sortedBy { it.startMs }
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return lyrics
        val line = ordered[index]
        val previous = ordered.getOrNull(index - 1)
        val next = ordered.getOrNull(index + 1)
        val minimumStart = (previous?.endMs ?: 0L) + if (previous == null) 0L else 1L
        val maximumStart = (next?.startMs ?: Long.MAX_VALUE) - line.durationMs - if (next == null) 0L else 1L
        if (maximumStart < minimumStart) return lyrics
        val safeStart = requestedStartMs.coerceIn(minimumStart, maximumStart)
        val safeEnd = safeStart + line.durationMs
        val shifted = shiftLyricLine(line, safeStart - line.startMs, safeStart, safeEnd)
        return ordered.toMutableList().apply { this[index] = shifted }
    }

    fun updateSelectedLyricStart(deltaMs: Long) {
        val id = _selectedLineId.value ?: return
        val current = _project.value.lyrics.sortedBy { it.startMs }
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = current[index]
        val previous = current.getOrNull(index - 1)
        val minimumStart = (previous?.endMs ?: 0L) + if (previous == null) 0L else 1L
        val maximumStart = old.endMs - 300L
        if (maximumStart < minimumStart) return
        val newStart = (old.startMs + deltaMs).coerceIn(minimumStart, maximumStart)
        _project.value = _project.value.copy(
            lyrics = current.toMutableList().apply { this[index] = resizeLyricLine(old, newStart, old.endMs) }
        )
    }

    fun updateSelectedLyricDuration(deltaMs: Long) {
        val id = _selectedLineId.value ?: return
        val current = _project.value.lyrics.sortedBy { it.startMs }
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val old = current[index]
        val next = current.getOrNull(index + 1)
        val maximumEnd = (next?.startMs ?: Long.MAX_VALUE) - if (next == null) 0L else 1L
        val minimumEnd = old.startMs + 300L
        if (maximumEnd < minimumEnd) return
        val newEnd = (old.endMs + deltaMs).coerceIn(minimumEnd, maximumEnd)
        _project.value = _project.value.copy(
            lyrics = current.toMutableList().apply { this[index] = resizeLyricLine(old, old.startMs, newEnd) }
        )
    }

    fun updateLyricText(id: String, newText: String) {
        val currentLyrics = _project.value.lyrics.toMutableList()
        val index = currentLyrics.indexOfFirst { it.id == id }
        if (index != -1) {
            val old = currentLyrics[index]
            currentLyrics[index] = updateLyricWords(old, newText)
            _project.value = _project.value.copy(lyrics = currentLyrics)
        }
    }

    fun addLyricLine(afterId: String? = null, initialText: String = "New lyric line") {
        val currentLyrics = _project.value.lyrics.toMutableList()
        val insertIndex = if (afterId != null) {
            currentLyrics.indexOfFirst { it.id == afterId } + 1
        } else {
            currentLyrics.size
        }

        val refLine = currentLyrics.getOrNull((insertIndex - 1).coerceAtLeast(0))
        val newStart = refLine?.endMs ?: audioEngine.playheadMs.value
        val newEnd = newStart + 3500L

        val newLine = PresetLibrary.createLyricLineWithWords(initialText, newStart, newEnd)
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

    /**
     * Re-times every word in each lyric cue against the decoded audio waveform.
     * Word duration is weighted by syllable count; internal boundaries are then
     * nudged toward nearby waveform attacks without moving the line-level LRC cues.
     * This is an explicit action so manually edited word timings are never overwritten silently.
     */
    fun autoSyncKaraokeWords(): Int {
        val project = _project.value
        val peaks = project.audioTrack.waveformPeaks
        if (peaks.isEmpty() || project.lyrics.isEmpty()) return 0

        val trackDuration = project.audioTrack.durationMs.coerceAtLeast(1L)
        val syncedLyrics = project.lyrics.map { line ->
            val tokens = (line.words.map { it.text }.takeIf { it.isNotEmpty() }
                ?: line.text.trim().split(Regex("\\s+")))
                .filter { it.isNotBlank() }
            if (tokens.isEmpty() || tokens.size == 1) {
                if (tokens.size == 1) line.copy(words = listOf(LyricWord(tokens[0], line.startMs, line.endMs)))
                else line
            } else {
                val start = line.startMs
                val end = line.endMs.coerceAtLeast(start + tokens.size * 60L)
                val duration = end - start
                val weights = tokens.map { token ->
                    val syllables = Regex("[aeiouy\\u00e0-\\u00fc]+", RegexOption.IGNORE_CASE)
                        .findAll(token)
                        .count()
                        .coerceAtLeast(1)
                    0.55f + syllables * 0.45f
                }
                val totalWeight = weights.sum().coerceAtLeast(1f)
                val minWordMs = 60L
                val searchWindowMs = (duration / (tokens.size * 4L)).coerceIn(35L, 140L)
                val boundaries = ArrayList<Long>(tokens.size + 1)
                boundaries += start

                var cumulativeWeight = 0f
                for (wordIndex in 0 until tokens.lastIndex) {
                    cumulativeWeight += weights[wordIndex]
                    val ideal = start + (duration * (cumulativeWeight / totalWeight)).toLong()
                    val remainingWords = tokens.size - wordIndex - 1
                    val minAllowed = boundaries.last() + minWordMs
                    val maxAllowed = end - remainingWords * minWordMs
                    val low = (ideal - searchWindowMs).coerceIn(minAllowed, maxAllowed)
                    val high = (ideal + searchWindowMs).coerceIn(low, maxAllowed)

                    var chosen = ideal.coerceIn(minAllowed, maxAllowed)
                    var bestScore = Double.NEGATIVE_INFINITY
                    if (peaks.size > 2 && high > low) {
                        val first = ((low.toDouble() / trackDuration) * peaks.size).toInt().coerceIn(1, peaks.lastIndex)
                        val last = ((high.toDouble() / trackDuration) * peaks.size).toInt().coerceIn(first, peaks.lastIndex)
                        for (bucket in first..last) {
                            val level = peaks[bucket].coerceIn(0f, 1f)
                            val rise = (level - peaks[bucket - 1]).coerceAtLeast(0f)
                            val candidateMs = (bucket.toDouble() / peaks.size * trackDuration).toLong()
                            val distance = kotlin.math.abs(candidateMs - ideal).toDouble() / searchWindowMs.coerceAtLeast(1L)
                            // Prefer a clear attack close to the syllable-weighted boundary.
                            val score = rise * 1.15 + level * 0.18 - distance * 0.42
                            if (score > bestScore) {
                                bestScore = score
                                chosen = candidateMs.coerceIn(minAllowed, maxAllowed)
                            }
                        }
                    }
                    boundaries += chosen.coerceIn(minAllowed, maxAllowed)
                }
                boundaries += end

                val words = tokens.mapIndexed { index, token ->
                    LyricWord(
                        text = token,
                        startMs = boundaries[index],
                        endMs = boundaries[index + 1].coerceAtLeast(boundaries[index] + 1L)
                    )
                }
                line.copy(words = words)
            }
        }

        val changed = syncedLyrics.count { updated ->
            val original = project.lyrics.firstOrNull { it.id == updated.id }
            original != null && original.words != updated.words
        }
        if (changed > 0) _project.value = project.copy(lyrics = syncedLyrics)
        return changed
    }

    /**
     * Rebuilds evenly spaced word cues only for operations that create a new sync
     * (for example a fresh tap-to-sync pass). Editing an existing line must use the
     * preservation helpers below so manual word timing is not silently destroyed.
     */
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

    private fun shiftLyricLine(
        old: LyricLine,
        deltaMs: Long,
        startMs: Long,
        endMs: Long
    ): LyricLine {
        val words = old.words.map { word ->
            val shiftedStart = (word.startMs + deltaMs).coerceAtLeast(startMs)
            val shiftedEnd = (word.endMs + deltaMs).coerceAtLeast(shiftedStart + 1L)
            word.copy(startMs = shiftedStart, endMs = shiftedEnd.coerceAtMost(endMs))
        }
        return old.copy(startMs = startMs, endMs = endMs, words = words)
    }

    /** Resize line boundaries while retaining absolute word cues wherever possible. */
    private fun resizeLyricLine(old: LyricLine, startMs: Long, endMs: Long): LyricLine {
        val safeEnd = endMs.coerceAtLeast(startMs + 1L)
        val words = old.words.mapNotNull { word ->
            val wordStart = word.startMs.coerceIn(startMs, safeEnd)
            val wordEnd = word.endMs.coerceIn(wordStart, safeEnd)
            if (wordEnd <= wordStart) null else word.copy(startMs = wordStart, endMs = wordEnd)
        }
        return old.copy(startMs = startMs, endMs = safeEnd, words = words)
    }

    /**
     * Preserve timestamps for unchanged words by position. Newly inserted words get
     * a local slot between neighbouring cues instead of retiming the whole sentence.
     */
    private fun updateLyricWords(old: LyricLine, newText: String): LyricLine {
        val tokens = newText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.isEmpty()) return old.copy(text = newText, words = emptyList())
        if (old.words.isEmpty()) return rebuildLyricLine(old, newText, old.startMs, old.endMs)

        val words = tokens.mapIndexed { index, token ->
            val previous = old.words.getOrNull(index)
            if (previous != null) {
                previous.copy(text = token)
            } else {
                val slot = (old.endMs - old.startMs).coerceAtLeast(tokens.size.toLong())
                val start = old.startMs + (slot * index / tokens.size)
                val end = old.startMs + (slot * (index + 1) / tokens.size)
                LyricWord(token, start, end.coerceAtLeast(start + 1L))
            }
        }.map { word ->
            val start = word.startMs.coerceIn(old.startMs, old.endMs)
            val end = word.endMs.coerceIn(start + 1L, old.endMs.coerceAtLeast(start + 1L))
            word.copy(startMs = start, endMs = end)
        }
        return old.copy(text = newText, words = words)
    }

    // --- Tap-To-Sync Workflow ---
    // Keep Tap-to-Sync on the exact same line-selection rule as VideoPreviewPlayer:
    // first line whose inclusive [startMs, endMs] contains the playhead; otherwise
    // the most recently completed line, falling back to the first line before lyrics begin.
    private fun previewAlignedLyricIndex(positionMs: Long, lyrics: List<LyricLine>): Int {
        if (lyrics.isEmpty()) return 0
        val activeIndex = lyrics.indexOfFirst { positionMs in it.startMs..it.endMs }
        if (activeIndex >= 0) return activeIndex
        val previousIndex = lyrics.indexOfLast { it.endMs < positionMs }
        return if (previousIndex >= 0) previousIndex else 0
    }

    fun startTapToSync() {
        _isTapToSyncActive.value = true
        tapSyncLastCommittedIndex = -1
        isFreshTapSyncSession = false
        audioEngine.seekTo(0L)
        _syncLineIndex.value = previewAlignedLyricIndex(
            audioEngine.playheadMs.value, _project.value.lyrics
        )

        // Track the same active/fallback line that the preview canvas renders.
        // A committed line remains advanced until playback reaches the next line;
        // seeking explicitly clears that guard so earlier timestamps can be corrected.
        tapSyncTrackingJob?.cancel()
        tapSyncTrackingJob = viewModelScope.launch {
            audioEngine.playheadMs.collect { position ->
                val lyrics = _project.value.lyrics
                if (isFreshTapSyncSession) {
                    // In a fresh sync, the cue advances only when the user taps.
                    // Existing placeholder timestamps must never auto-skip unsynced lines.
                    return@collect
                }
                if (lyrics.isEmpty()) {
                    _syncLineIndex.value = 0
                } else {
                    val previewIndex = previewAlignedLyricIndex(position, lyrics)
                    if (previewIndex > tapSyncLastCommittedIndex) {
                        _syncLineIndex.value = previewIndex
                    }
                }
            }
        }
        audioEngine.play()
    }

    fun resetTapToSync() {
        if (_project.value.lyrics.isEmpty()) return
        isFreshTapSyncSession = true
        tapSyncLastCommittedIndex = -1
        _syncLineIndex.value = 0
        audioEngine.seekTo(0L)
        if (!_isTapToSyncActive.value) _isTapToSyncActive.value = true
        audioEngine.play()
    }

    fun stopTapToSync() {
        _isTapToSyncActive.value = false
        isFreshTapSyncSession = false
        tapSyncTrackingJob?.cancel()
        tapSyncTrackingJob = null
        audioEngine.pause()
    }

    fun seekTapSyncTo(positionMs: Long) {
        // Seeking is an explicit correction/navigation action: allow the cue to move
        // both forward and backward to match the preview at the new playhead.
        tapSyncLastCommittedIndex = -1
        audioEngine.seekTo(positionMs)
        if (!isFreshTapSyncSession) {
            _syncLineIndex.value = previewAlignedLyricIndex(
                audioEngine.playheadMs.value, _project.value.lyrics
            )
        }
    }

    fun tapSyncNext() {
        triggerHaptic()
        val currentLyrics = _project.value.lyrics.toMutableList()
        val index = _syncLineIndex.value
        val currentTime = audioEngine.playheadMs.value

        if (index in currentLyrics.indices) {
            val old = currentLyrics[index]
            // Close the preceding line at this exact beat and correct this line's
            // start time while preserving the remaining saved timeline.
            if (index > 0) {
                val prev = currentLyrics[index - 1]
                val previousEnd = currentTime.coerceAtLeast(prev.startMs + 200L)
                currentLyrics[index - 1] = resizeLyricLine(prev, prev.startMs, previousEnd)
            }
            val nextStart = currentLyrics.getOrNull(index + 1)?.startMs
            val estimatedEnd = if (isFreshTapSyncSession) {
                currentTime + 3000L
            } else {
                nextStart?.takeIf { it > currentTime } ?: (currentTime + 3000L)
            }
            currentLyrics[index] = rebuildLyricLine(
                old, old.text, currentTime, estimatedEnd.coerceAtLeast(currentTime + 200L)
            )

            _project.value = _project.value.copy(lyrics = currentLyrics)
            _selectedLineId.value = old.id
            tapSyncLastCommittedIndex = index
            _syncLineIndex.value = (index + 1).coerceAtMost(currentLyrics.size)
        } else {
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
        val selectedSize = _project.value.typography.fontSizeSp
        _project.value = _project.value.copy(
            typography = preset.copy(fontSizeSp = selectedSize)
        )
    }

    fun updateTypographyProperties(
        fontSizeSp: Float? = null,
        primaryHex: String? = null,
        highlightHex: String? = null,
        strokeWidthDp: Float? = null,
        glowRadiusDp: Float? = null,
        fontFamily: String? = null,
        positionXPercent: Float? = null,
        positionYPercent: Float? = null,
        isBold: Boolean? = null,
        isItalic: Boolean? = null,
        visualEffect: FontVisualEffect? = null,
        lyricLayout: LyricLayoutMode? = null
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
                positionYPercent = positionYPercent?.coerceIn(5f, 95f) ?: current.positionYPercent,
                isBold = isBold ?: current.isBold,
                isItalic = isItalic ?: current.isItalic,
                visualEffect = visualEffect ?: current.visualEffect,
                lyricLayout = lyricLayout ?: current.lyricLayout
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
            durationMs = timelineDuration,
            sourceDurationMs = if (type == MediaClipType.VIDEO) timelineDuration else timelineDuration,
            sourceOffsetMs = 0L
        )
        _project.value = _project.value.copy(mediaClips = (_project.value.mediaClips + clip).sortedBy { it.startMs })
    }

    fun moveMediaClip(id: String, deltaMs: Long) {
        val project = _project.value
        val clips = project.mediaClips.map { clip ->
            if (clip.id == id) {
                val latestStart = (project.durationMs - clip.durationMs).coerceAtLeast(0L)
                val targetStart = (clip.startMs + deltaMs).coerceIn(0L, latestStart)
                clip.copy(startMs = targetStart)
            } else clip
        }
        _project.value = project.copy(mediaClips = clips.sortedBy { it.startMs })
    }

    fun resizeMediaClip(id: String, deltaMs: Long) {
        val clips = _project.value.mediaClips.map { clip ->
            if (clip.id == id) {
                val minimum = if (clip.type == MediaClipType.VIDEO) 500L else 1_000L
                val maximum = if (clip.type == MediaClipType.VIDEO) {
                    // Video timeline length follows its source duration, even when it exceeds song length.
                    (clip.sourceDurationMs - clip.sourceOffsetMs).coerceAtLeast(minimum)
                } else {
                    (_project.value.durationMs - clip.startMs).coerceIn(minimum, 3_600_000L)
                }
                clip.copy(durationMs = (clip.durationMs + deltaMs).coerceIn(minimum, maximum))
            } else clip
        }
        _project.value = _project.value.copy(mediaClips = clips)
    }

    /**
     * Trims the left edge while preserving the clip's right edge.
     * Positive delta removes source from the beginning; negative delta restores it.
     */
    fun trimMediaClipStart(id: String, deltaMs: Long) {
        val project = _project.value
        val clips = project.mediaClips.map { clip ->
            if (clip.id != id) return@map clip
            val minimum = if (clip.type == MediaClipType.VIDEO) 500L else 1_000L
            val maxOffset = (clip.sourceDurationMs - minimum).coerceAtLeast(0L)
            val requestedOffset = (clip.sourceOffsetMs + deltaMs).coerceIn(0L, maxOffset)
            val actualDelta = (requestedOffset - clip.sourceOffsetMs).coerceIn(
                -minOf(clip.startMs, clip.sourceOffsetMs),
                (clip.durationMs - minimum).coerceAtLeast(0L)
            )
            val newOffset = (clip.sourceOffsetMs + actualDelta).coerceAtLeast(0L)
            val newDuration = (clip.durationMs - actualDelta).coerceAtLeast(minimum)
            clip.copy(
                startMs = (clip.startMs + actualDelta).coerceAtLeast(0L),
                durationMs = newDuration,
                sourceOffsetMs = newOffset
            )
        }
        _project.value = project.copy(mediaClips = clips.sortedBy { it.startMs })
    }

    fun setVideoAudioMuted(muted: Boolean) {
        _project.value = _project.value.copy(videoAudioMuted = muted)
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

    fun importCustomAudio(uriString: String, fileName: String, durationMs: Long) {
        val safeDurationMs = durationMs.coerceAtLeast(500L)
        val customTrack = AudioTrackConfig(
            id = UUID.randomUUID().toString(),
            title = fileName.ifBlank { "Imported Track" },
            artist = "Local Device",
            bpm = 120,
            durationMs = safeDurationMs,
            beatType = AudioBeatType.CUSTOM_FILE,
            customUri = uriString
        )
        _project.value = _project.value.copy(audioTrack = customTrack)
        audioEngine.loadTrack(customTrack)

        // Decode real PCM samples off the UI thread; only apply them if this track
        // is still selected when extraction completes.
        viewModelScope.launch {
            val peaks = AudioWaveformExtractor.extract(
                context = getApplication(),
                uriString = uriString,
                durationMs = safeDurationMs
            )
            if (peaks.isNotEmpty() && _project.value.audioTrack.id == customTrack.id) {
                _project.value = _project.value.copy(
                    audioTrack = _project.value.audioTrack.copy(waveformPeaks = peaks)
                )
            }
        }
    }

    // --- Export Video Workflow ---
    fun startExport(quality: ExportQuality, durationSec: Int = 15, removeWatermark: Boolean = false) {
        if (_isExporting.value) return
        audioEngine.pause()
        _lastExportedFile.value = null
        _isExporting.value = true
        _exportProgress.value = 0f
        _exportStatus.value = "Preparing frames..."

        exportJob = viewModelScope.launch {
            try {
                val exported = exportEngine.exportVideo(
                    // Watermark is mandatory for free exports, regardless of the editor preview toggle.
                    // A verified rewarded-ad callback or permanent Play purchase is the only bypass.
                    project = _project.value.copy(
                        showWatermark = !removeWatermark,
                    ),
                    quality = quality,
                    durationSeconds = durationSec
                ) { progress ->
                    _exportProgress.value = progress.percentage
                    _exportStatus.value = progress.statusMessage
                }
                _lastExportedFile.value = exported
                _exportStatus.value = "Ready to Share!"
            } catch (e: kotlinx.coroutines.CancellationException) {
                _exportStatus.value = "Rendering cancelled"
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                _exportStatus.value = "Export failed: ${e.localizedMessage ?: "Unknown error"}"
            } finally {
                _isExporting.value = false
                exportJob = null
            }
        }
    }

    fun cancelExport() {
        if (!_isExporting.value) return
        _exportStatus.value = "Cancelling rendering..."
        exportJob?.cancel()
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
