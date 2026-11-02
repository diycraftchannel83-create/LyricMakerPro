package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import com.example.data.PresetLibrary
import com.example.model.*
import com.example.ui.theme.*
import com.example.viewmodel.LyricMakerViewModel

@Composable
fun StudioTabs(
    viewModel: LyricMakerViewModel,
    project: VideoProject,
    currentTab: LyricMakerViewModel.StudioTab,
    onTabSelected: (LyricMakerViewModel.StudioTab) -> Unit,
    onOpenTapToSync: () -> Unit,
    onPickAudio: () -> Unit = {},
    onPickVideo: () -> Unit = {},
    onPickImage: () -> Unit = {},
    isExpanded: Boolean = true,
    onToggleExpanded: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var showImportDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(StudioSurface)
    ) {
        // Drag handle / expand-collapse affordance for the mobile bottom editor panel.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(isExpanded) {
                    var totalDrag = 0f
                    detectVerticalDragGestures(
                        onDragStart = { _ -> totalDrag = 0f },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            totalDrag += dragAmount
                        },
                        onDragEnd = {
                            if (totalDrag < -24f) onToggleExpanded(true)
                            else if (totalDrag > 24f) onToggleExpanded(false)
                        }
                    )
                }
                .clickable { onToggleExpanded(!isExpanded) },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(42.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(TextSecondary.copy(alpha = 0.7f))
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (isExpanded) "Collapse editor panel" else "Expand editor panel",
                tint = NeonCyan,
                modifier = Modifier.size(18.dp)
            )
        }

        // Tab Navigation Bar
        ScrollableTabRow(
            selectedTabIndex = currentTab.ordinal,
            containerColor = StudioSurface,
            contentColor = NeonCyan,
            edgePadding = 8.dp,
            divider = { HorizontalDivider(color = StudioCardBorder) }
        ) {
            LyricMakerViewModel.StudioTab.entries.forEach { tab ->
                val isSelected = tab == currentTab
                Tab(
                    selected = isSelected,
                    onClick = { onTabSelected(tab) },
                    text = {
                        Text(
                            text = tab.label,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 12.sp,
                            color = if (isSelected) NeonCyan else TextSecondary
                        )
                    },
                    modifier = Modifier.testTag("tab_${tab.name.lowercase()}")
                )
            }
        }

        // The editor body is hidden while collapsed and becomes a bounded,
        // scrollable workspace when the sheet is expanded.
        if (isExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(min = 120.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .testTag("studio_tab_content")
            ) {
            when (currentTab) {
                LyricMakerViewModel.StudioTab.TIMELINE -> {
                    TimelineControlsTab(viewModel, project, onOpenTapToSync)
                }
                LyricMakerViewModel.StudioTab.TYPOGRAPHY -> {
                    TypographyTab(viewModel, project)
                }
                LyricMakerViewModel.StudioTab.EFFECTS -> {
                    EffectsTab(viewModel, project)
                }
                LyricMakerViewModel.StudioTab.VISUALIZER -> {
                    VisualizerTab(viewModel, project)
                }
                LyricMakerViewModel.StudioTab.BACKGROUND -> {
                    BackgroundTab(viewModel, project, onPickAudio, onPickVideo, onPickImage)
                }
                LyricMakerViewModel.StudioTab.LYRICS_SYNC -> {
                    LyricsSyncTab(
                        viewModel = viewModel,
                        project = project,
                        onOpenTapToSync = onOpenTapToSync,
                        onOpenImport = { showImportDialog = true }
                    )
                }
            }
            }
        }
    }

    if (showImportDialog) {
        ImportLyricsDialog(
            onDismiss = { showImportDialog = false },
            onImport = { text ->
                viewModel.importLyricsText(text)
                showImportDialog = false
            }
        )
    }
}

@Composable
private fun TimelineControlsTab(
    viewModel: LyricMakerViewModel,
    project: VideoProject,
    onOpenTapToSync: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Quick Action Card: Live Tap-to-Sync
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant),
            border = androidx.compose.foundation.BorderStroke(1.dp, NeonMagenta.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Real-Time Lyric Sync",
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Listen to music and tap on each beat to lock in timestamps with haptic feedback.",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onOpenTapToSync,
                    colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                    modifier = Modifier.testTag("launch_tap_to_sync_button")
                ) {
                    Icon(imageVector = Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Sync Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Add / Edit Line Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { viewModel.addLyricLine() },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonCyan)
                Spacer(Modifier.width(6.dp))
                Text("Add Lyric Line", fontSize = 12.sp, color = NeonCyan)
            }

            OutlinedButton(
                onClick = { viewModel.setShowTemplatesSheet(true) },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.LibraryMusic, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyberYellow)
                Spacer(Modifier.width(6.dp))
                Text("Presets Library", fontSize = 12.sp, color = CyberYellow)
            }
        }
    }
}

@Composable
private fun TypographyTab(viewModel: LyricMakerViewModel, project: VideoProject) {
    val typography = project.typography

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Typography Presets Carousel
        item {
            Text(
                text = "Preset Styles",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = TextPrimary
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(PresetLibrary.typographyPresets) { preset ->
                    val isSelected = preset.name == typography.name
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) ElectricPurple.copy(alpha = 0.4f) else StudioSurfaceVariant
                        ),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) NeonCyan else StudioCardBorder
                        ),
                        modifier = Modifier
                            .clickable { viewModel.setTypographyPreset(preset) }
                            .testTag("preset_${preset.name.lowercase().replace(" ", "_")}")
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Lyrics",
                                fontSize = 15.sp,
                                fontWeight = if (preset.isBold) FontWeight.Bold else FontWeight.Normal,
                                color = parseColorHex(preset.highlightColorHex)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(text = preset.name, fontSize = 11.sp, color = TextSecondary)
                        }
                    }
                }
            }
        }

        // Font Family Selector
        item {
            Text(text = "Font Family", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("SansSerif" to "Sans", "Monospace" to "Mono", "Serif" to "Serif").forEach { (fam, label) ->
                    val isSelected = typography.fontFamilyName.equals(fam, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.updateTypographyProperties(fontFamily = fam) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }

        // Font Size Slider
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Font Size", fontSize = 12.sp, color = TextSecondary)
                Text(text = "${typography.fontSizeSp.toInt()}sp", fontSize = 12.sp, color = NeonCyan)
            }
            Slider(
                value = typography.fontSizeSp,
                onValueChange = { viewModel.updateTypographyProperties(fontSizeSp = it) },
                valueRange = 18f..48f,
                colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
            )
        }

        // Global lyric placement controls (applies to every lyric line).
        item {
            Text("Lyric Position (all lines)", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Horizontal", fontSize = 12.sp, color = TextSecondary)
                Text("${typography.positionXPercent.toInt()}%", fontSize = 12.sp, color = NeonCyan)
            }
            Slider(value = typography.positionXPercent, onValueChange = { viewModel.updateTypographyProperties(positionXPercent = it) }, valueRange = 5f..95f)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Vertical", fontSize = 12.sp, color = TextSecondary)
                Text("${typography.positionYPercent.toInt()}%", fontSize = 12.sp, color = NeonCyan)
            }
            Slider(value = typography.positionYPercent, onValueChange = { viewModel.updateTypographyProperties(positionYPercent = it) }, valueRange = 5f..95f)
            Text("Drag directly on the preview to reposition all lyric lines.", fontSize = 10.sp, color = TextSecondary)
        }

        // Glow Radius Slider
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Neon Glow Intensity", fontSize = 12.sp, color = TextSecondary)
                Text(text = "${typography.glowRadiusDp.toInt()}dp", fontSize = 12.sp, color = NeonMagenta)
            }
            Slider(
                value = typography.glowRadiusDp,
                onValueChange = { viewModel.updateTypographyProperties(glowRadiusDp = it) },
                valueRange = 0f..24f,
                colors = SliderDefaults.colors(thumbColor = NeonMagenta, activeTrackColor = NeonMagenta)
            )
        }

        // Stroke Width Slider
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Outline Stroke", fontSize = 12.sp, color = TextSecondary)
                Text(text = "${typography.strokeWidthDp.toInt()}dp", fontSize = 12.sp, color = CyberYellow)
            }
            Slider(
                value = typography.strokeWidthDp,
                onValueChange = { viewModel.updateTypographyProperties(strokeWidthDp = it) },
                valueRange = 0f..6f,
                colors = SliderDefaults.colors(thumbColor = CyberYellow, activeTrackColor = CyberYellow)
            )
        }
    }
}

@Composable
private fun EffectsTab(viewModel: LyricMakerViewModel, project: VideoProject) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(TextTransitionEffect.entries) { effect ->
            val isSelected = effect == project.transitionEffect
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) ElectricPurple.copy(alpha = 0.35f) else StudioSurfaceVariant
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isSelected) 1.5.dp else 0.5.dp,
                    color = if (isSelected) NeonMagenta else StudioCardBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setTransitionEffect(effect) }
                    .testTag("effect_${effect.name.lowercase()}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) NeonMagenta else StudioCardBorder),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = when (effect) {
                                TextTransitionEffect.KARAOKE_SWEEP -> Icons.Default.MusicNote
                                TextTransitionEffect.KINETIC_BOUNCE -> Icons.Default.Bolt
                                TextTransitionEffect.NEON_PULSE -> Icons.Default.Flare
                                TextTransitionEffect.TYPEWRITER_GLITCH -> Icons.Default.Terminal
                                TextTransitionEffect.SLIDE_FADE_UP -> Icons.Default.ArrowUpward
                                TextTransitionEffect.WAVE_RIPPLE -> Icons.Default.Waves
                                TextTransitionEffect.BURST_ZOOM -> Icons.Default.FitScreen
                            },
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = effect.label,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isSelected) NeonMagenta else TextPrimary
                        )
                        Text(
                            text = effect.description,
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }

                    if (isSelected) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = NeonMagenta, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun VisualizerTab(viewModel: LyricMakerViewModel, project: VideoProject) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Visualizer Overlay Style Selection
        item {
            Text(text = "Visualizer Type", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
        }

        items(VisualizerType.entries) { type ->
            val isSelected = type == project.visualizer
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) StudioSurfaceVariant else StudioSurface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) NeonCyan else StudioCardBorder
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setVisualizerType(type) }
                    .testTag("vis_${type.name.lowercase()}")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (type) {
                            VisualizerType.BARS_SPECTRUM -> Icons.Default.BarChart
                            VisualizerType.CIRCULAR_WAVE -> Icons.Default.DonutLarge
                            VisualizerType.OSCILLOSCOPE -> Icons.Default.ShowChart
                            VisualizerType.FLOATING_PARTICLES -> Icons.Default.Grain
                            VisualizerType.VINYL_SPINNER -> Icons.Default.Album
                            VisualizerType.BASS_PULSE -> Icons.Default.SurroundSound
                            VisualizerType.NONE -> Icons.Default.Block
                        },
                        contentDescription = null,
                        tint = if (isSelected) NeonCyan else TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = type.label,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = if (isSelected) NeonCyan else TextPrimary
                        )
                        Text(text = type.description, fontSize = 10.sp, color = TextSecondary)
                    }
                    if (isSelected) {
                        Icon(Icons.Default.Check, contentDescription = null, tint = NeonCyan, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Visualizer Neon Color Swatches
        item {
            Spacer(Modifier.height(6.dp))
            Text(text = "Visualizer Color Glow", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            val colors = listOf("#00F5D4", "#FF007F", "#FEE440", "#7B2CBF", "#FF5964", "#3A86FF", "#FFFFFF")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                colors.forEach { hex ->
                    val isColorSelected = project.visualizerColorHex.equals(hex, ignoreCase = true)
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(parseColorHex(hex))
                            .border(
                                width = if (isColorSelected) 2.5.dp else 1.dp,
                                color = if (isColorSelected) Color.White else Color.Transparent,
                                shape = CircleShape
                            )
                            .clickable { viewModel.setVisualizerColor(hex) }
                    )
                }
            }
        }
    }
}

@Composable
private fun BackgroundTab(viewModel: LyricMakerViewModel, project: VideoProject, onPickAudio: () -> Unit, onPickVideo: () -> Unit, onPickImage: () -> Unit) {
    val bg = project.background

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Canvas media import controls.
        item {
            Text("Media", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onPickAudio, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 3.dp)) { Text("Audio", fontSize = 11.sp) }
                OutlinedButton(onClick = onPickVideo, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 3.dp)) { Text("Video", fontSize = 11.sp) }
                OutlinedButton(onClick = onPickImage, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 3.dp)) { Text("Photo", fontSize = 11.sp) }
            }
        }

        // Aspect Ratio
        item {
            Text(text = "Export Aspect Ratio", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CanvasRatio.entries.forEach { ratio ->
                    val isSelected = ratio == project.canvasRatio
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setCanvasRatio(ratio) },
                        label = { Text(ratio.label, fontSize = 12.sp) }
                    )
                }
            }
        }

        // Background Atmosphere Presets
        item {
            Text(text = "Atmosphere & Themes", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary)
            Spacer(Modifier.height(6.dp))

            val themes = listOf(
                "Neon Cyber" to BackgroundConfig(BackgroundType.GRADIENT, "#090A14", "#231138", "#FF007F"),
                "Cyber Grid" to BackgroundConfig(BackgroundType.CYBER_GRID, "#08090F", "#190826", "#00F5D4"),
                "Deep Space" to BackgroundConfig(BackgroundType.GRADIENT, "#06070B", "#101322", "#00F5D4"),
                "Lo-Fi Noir" to BackgroundConfig(BackgroundType.GRADIENT, "#12141A", "#1E232B", "#FFEAA7"),
                "Sunset Gold" to BackgroundConfig(BackgroundType.GRADIENT, "#1C0D0A", "#361614", "#E17055"),
                "Minimal Solid" to BackgroundConfig(BackgroundType.SOLID, "#090A0F")
            )

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(themes) { (name, cfg) ->
                    val isSelected = bg.type == cfg.type && bg.primaryColorHex == cfg.primaryColorHex
                    Card(
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant),
                        border = androidx.compose.foundation.BorderStroke(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) NeonCyan else StudioCardBorder
                        ),
                        modifier = Modifier.clickable { viewModel.updateBackground(cfg) }
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp, 28.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(parseColorHex(cfg.primaryColorHex))
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(name, fontSize = 10.sp, color = if (isSelected) NeonCyan else TextSecondary)
                        }
                    }
                }
            }
        }

        // Dark Vignette Opacity Slider
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Dark Contrast Overlay", fontSize = 12.sp, color = TextSecondary)
                Text(text = "${(bg.darkOverlayOpacity * 100).toInt()}%", fontSize = 12.sp, color = NeonCyan)
            }
            Slider(
                value = bg.darkOverlayOpacity,
                onValueChange = { viewModel.updateBackground(bg.copy(darkOverlayOpacity = it)) },
                valueRange = 0f..0.85f,
                colors = SliderDefaults.colors(thumbColor = NeonCyan, activeTrackColor = NeonCyan)
            )
        }

        // Watermark Setting
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(text = "Creator Tag / Watermark", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                    Text(text = "Display ${project.watermarkText} in corner", fontSize = 11.sp, color = TextSecondary)
                }
                Switch(
                    checked = project.showWatermark,
                    onCheckedChange = { viewModel.toggleWatermark(it) }
                )
            }
        }
    }
}

@Composable
private fun LyricsSyncTab(
    viewModel: LyricMakerViewModel,
    project: VideoProject,
    onOpenTapToSync: () -> Unit,
    onOpenImport: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Quick Action Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpenTapToSync,
                colors = ButtonDefaults.buttonColors(containerColor = NeonMagenta),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tap-to-Sync Mode", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            OutlinedButton(
                onClick = onOpenImport,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp), tint = NeonCyan)
                Spacer(Modifier.width(6.dp))
                Text("Import LRC/Text", fontSize = 12.sp, color = NeonCyan)
            }
        }

        // Editable Lines List
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(project.lyrics, key = { it.id }) { line ->
                LyricLineEditorCard(
                    line = line,
                    onTextChange = { viewModel.updateLyricText(line.id, it) },
                    onSelect = { viewModel.selectLyricLine(line.id) }
                )
            }
        }
    }
}

@Composable
private fun LyricLineEditorCard(
    line: LyricLine,
    onTextChange: (String) -> Unit,
    onSelect: () -> Unit
) {
    var textValue by remember(line.text) { mutableStateOf(line.text) }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = StudioSurfaceVariant),
        border = androidx.compose.foundation.BorderStroke(1.dp, StudioCardBorder),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Timestamp Pill
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = ElectricPurple.copy(alpha = 0.3f),
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(
                    text = formatMs(line.startMs),
                    fontSize = 11.sp,
                    color = NeonCyan,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }

            // Editable text input
            OutlinedTextField(
                value = textValue,
                onValueChange = {
                    textValue = it
                    onTextChange(it)
                },
                modifier = Modifier.weight(1f),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, color = TextPrimary),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = Color.Transparent
                ),
                singleLine = true
            )
        }
    }
}

@Composable
private fun ImportLyricsDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var rawText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import Lyrics or LRC File", color = TextPrimary, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Paste raw text or timestamped .lrc lines. Plain text will be automatically segmented into editable video cue lines.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    placeholder = { Text("[00:04.20] Walking down the avenue\n[00:08.50] Dreams are coming into view", fontSize = 11.sp) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (rawText.isNotBlank()) onImport(rawText) },
                colors = ButtonDefaults.buttonColors(containerColor = NeonCyan)
            ) {
                Text("Import Lyrics", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        containerColor = StudioSurface
    )
}

private fun formatMs(ms: Long): String {
    val sec = ms / 1000
    val tenth = (ms % 1000) / 100
    return "${sec}.${tenth}s"
}
