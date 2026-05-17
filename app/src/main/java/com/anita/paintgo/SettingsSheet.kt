package com.anita.paintgo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import kotlin.math.roundToInt
import android.graphics.Color as AndroidColor

// Order of chips in the View mode row. "Uncovered" is the user-facing label for
// [ViewMode.Fog] — they think of it as "uncovered area", not "fog".
private enum class ViewModeChoice(val mode: ViewMode, val label: String) {
    Uncovered(ViewMode.Fog, "Uncovered"),
    Speed(ViewMode.Speed, "Speed"),
    Recency(ViewMode.Recency, "Recency"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    onDismiss: () -> Unit,
    onEnterDeleteMode: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { SettingsStore.get(context) }
    val showBoundaries by store.showBoundaries.collectAsState()
    val showDebugGrid by store.showDebugGrid.collectAsState()
    val sampleIntervalMs by store.sampleIntervalMs.collectAsState()
    val fogColor by store.fogColor.collectAsState()
    val fogOpacity by store.fogOpacity.collectAsState()
    val viewMode by store.viewMode.collectAsState()
    // skipPartiallyExpanded: open straight to full height so the bottom of the list
    // (currently "Delete points mode") isn't clipped at the half-height stop.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var customPickerOpen by remember { mutableStateOf(false) }

    val isCustomColor = SettingsStore.FOG_COLOR_CHOICES.none { it.equals(fogColor, ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("View mode", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ViewModeChoice.entries.forEach { choice ->
                        FilterChip(
                            selected = choice.mode == viewMode,
                            onClick = { store.setViewMode(choice.mode) },
                            label = { Text(choice.label) },
                        )
                    }
                }
            }

            ToggleRow(
                label = "Region borders",
                description = null,
                checked = showBoundaries,
                onCheckedChange = store::setShowBoundaries,
            )

            ToggleRow(
                label = "Debug grid",
                description = null,
                checked = showDebugGrid,
                onCheckedChange = store::setShowDebugGrid,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Fog color", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CustomFogColorSwatch(
                        currentHex = fogColor,
                        isCustom = isCustomColor,
                        onClick = { customPickerOpen = true },
                    )
                    SettingsStore.FOG_COLOR_CHOICES.forEach { hex ->
                        FogColorSwatch(
                            hex = hex,
                            selected = hex.equals(fogColor, ignoreCase = true),
                            onClick = { store.setFogColor(hex) },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Sample frequency", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingsStore.SAMPLE_INTERVAL_CHOICES_MS.forEach { ms ->
                        FilterChip(
                            selected = ms == sampleIntervalMs,
                            onClick = { store.setSampleIntervalMs(ms) },
                            label = { Text("${ms / 1000}s") },
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Delete points mode", style = MaterialTheme.typography.titleMedium)
                OutlinedButton(onClick = onEnterDeleteMode) { Text("Enter") }
            }

            Spacer(Modifier.height(4.dp))
        }
    }

    if (customPickerOpen) {
        FogColorPickerDialog(
            initialHex = fogColor,
            initialAlpha = fogOpacity,
            onDismiss = { customPickerOpen = false },
            onConfirm = { pickedHex, pickedAlpha ->
                store.setFogColor(pickedHex)
                store.setFogOpacity(pickedAlpha)
                customPickerOpen = false
            },
        )
    }
}

@Composable
private fun FogColorSwatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    val color = Color(hex.toColorInt())
    // White checkmark on dark swatches, dark on light — luminance threshold from sRGB.
    val checkColor = if (color.luminance() < 0.5f) Color.White else Color(0xFF212121)
    val borderColor = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .border(width = 2.dp, color = borderColor, shape = CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Selected",
                tint = checkColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// The Custom slot at the end of the swatch row. When the user has dialed in a custom
// hex, this chip displays it as a selected swatch (so the choice is visible at a glance);
// otherwise it shows a hollow "+" placeholder. Tapping always opens the picker dialog
// initialized to the current color.
@Composable
private fun CustomFogColorSwatch(currentHex: String, isCustom: Boolean, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    if (isCustom) {
        val color = Color(currentHex.toColorInt())
        val checkColor = if (color.luminance() < 0.5f) Color.White else Color(0xFF212121)
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color)
                .border(width = 2.dp, color = MaterialTheme.colorScheme.onSurface, shape = CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Custom color selected — tap to edit",
                tint = checkColor,
                modifier = Modifier.size(20.dp),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .border(width = 1.5.dp, color = outline, shape = CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Pick a custom fog color",
                tint = outline,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// Aseprite-style picker: a 2D saturation/value box driven by drag, a hue strip, and an
// alpha strip. Hex display is RGB-only; alpha is a separate scalar piped into the
// fog layer's fillOpacity so transparency reads correctly against the basemap.
@Composable
private fun FogColorPickerDialog(
    initialHex: String,
    initialAlpha: Float,
    onDismiss: () -> Unit,
    onConfirm: (hex: String, alpha: Float) -> Unit,
) {
    val initialHsv = remember(initialHex) {
        val arr = FloatArray(3)
        AndroidColor.colorToHSV(initialHex.toColorInt(), arr)
        arr
    }
    var hue by remember { mutableFloatStateOf(initialHsv[0]) }
    var sat by remember { mutableFloatStateOf(initialHsv[1]) }
    var value by remember { mutableFloatStateOf(initialHsv[2]) }
    var alpha by remember { mutableFloatStateOf(initialAlpha.coerceIn(0f, 1f)) }

    val currentColor = Color.hsv(hue, sat, value)
    val hex = "#%02X%02X%02X".format(
        (currentColor.red * 255f).roundToInt(),
        (currentColor.green * 255f).roundToInt(),
        (currentColor.blue * 255f).roundToInt(),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom fog color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SvBox(hue = hue, sat = sat, value = value) { s, v ->
                    sat = s; value = v
                }
                HueSlider(hue = hue) { hue = it }
                AlphaSlider(color = currentColor, alpha = alpha) { alpha = it }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CheckerSwatch(color = currentColor.copy(alpha = alpha))
                    Text(hex, style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(hex, alpha) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// 2D saturation-value plane. Implemented as a stack: a white→pure-hue horizontal
// gradient under a transparent→black vertical gradient. Tap or drag anywhere to
// jump the cursor; gestures eat the down event so AlertDialog doesn't steal it.
@Composable
private fun SvBox(
    hue: Float,
    sat: Float,
    value: Float,
    onChange: (sat: Float, value: Float) -> Unit,
) {
    val pureHue = Color.hsv(hue, 1f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .clip(RoundedCornerShape(8.dp))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fun apply(p: Offset) {
                            val s = (p.x / size.width).coerceIn(0f, 1f)
                            val v = (1f - p.y / size.height).coerceIn(0f, 1f)
                            onChange(s, v)
                        }
                        apply(down.position)
                        down.consume()
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach {
                                if (it.pressed) {
                                    apply(it.position)
                                    it.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Brush.horizontalGradient(listOf(Color.White, pureHue))),
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))),
        )
        Canvas(modifier = Modifier.matchParentSize()) {
            val cx = sat * size.width
            val cy = (1f - value) * size.height
            val r = 7.dp.toPx()
            // Double-stroke crosshair (dark halo + white core) so it stays visible on
            // any background — corners of the SV box are near-white and near-black.
            drawCircle(Color.Black, radius = r, center = Offset(cx, cy), style = Stroke(width = 3f))
            drawCircle(Color.White, radius = r, center = Offset(cx, cy), style = Stroke(width = 1.5f))
        }
    }
}

@Composable
private fun HueSlider(hue: Float, onChange: (Float) -> Unit) {
    val hueColors = remember {
        (0..6).map { Color.hsv(it * 60f, 1f, 1f) }
    }
    StripSlider(
        background = Brush.horizontalGradient(hueColors),
        position = hue / 360f,
        onChange = { onChange(it * 360f) },
    )
}

@Composable
private fun AlphaSlider(color: Color, alpha: Float, onChange: (Float) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(4.dp)),
    ) {
        Checkerboard(modifier = Modifier.matchParentSize())
        StripSlider(
            background = Brush.horizontalGradient(
                listOf(color.copy(alpha = 0f), color.copy(alpha = 1f)),
            ),
            position = alpha,
            onChange = onChange,
            modifier = Modifier.matchParentSize(),
            clip = false,
        )
    }
}

// Shared horizontal strip: tappable/draggable, with a thumb drawn at `position`.
@Composable
private fun StripSlider(
    background: Brush,
    position: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    clip: Boolean = true,
) {
    val base = modifier
        .then(if (clip) Modifier.fillMaxWidth().height(24.dp).clip(RoundedCornerShape(4.dp)) else Modifier)
        .background(background)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun apply(p: Offset) {
                        onChange((p.x / size.width).coerceIn(0f, 1f))
                    }
                    apply(down.position)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach {
                            if (it.pressed) {
                                apply(it.position)
                                it.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
        }
    Box(modifier = base) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val x = position * size.width
            drawLine(
                color = Color.Black,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 4f,
            )
            drawLine(
                color = Color.White,
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
            )
        }
    }
}

@Composable
private fun Checkerboard(modifier: Modifier = Modifier, tileDp: Int = 6) {
    Canvas(modifier = modifier) {
        val tile = tileDp.dp.toPx()
        val cols = (size.width / tile).toInt() + 1
        val rows = (size.height / tile).toInt() + 1
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val light = (r + c) % 2 == 0
                drawRect(
                    color = if (light) Color(0xFFFFFFFF) else Color(0xFFCCCCCC),
                    topLeft = Offset(c * tile, r * tile),
                    size = Size(tile, tile),
                )
            }
        }
    }
}

@Composable
private fun CheckerSwatch(color: Color) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                shape = CircleShape,
            ),
    ) {
        Checkerboard(modifier = Modifier.matchParentSize(), tileDp = 8)
        Box(modifier = Modifier.matchParentSize().background(color))
    }
}

@Composable
private fun ToggleRow(
    label: String,
    description: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
