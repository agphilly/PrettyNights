package com.example.patterns

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun hexToRgb(hex: String): Triple<Int, Int, Int> {
    val clean = hex.trim().removePrefix("#")
    return try {
        if (clean.length == 6) {
            val r = clean.substring(0, 2).toInt(16)
            val g = clean.substring(2, 4).toInt(16)
            val b = clean.substring(4, 6).toInt(16)
            Triple(r, g, b)
        } else if (clean.length == 3) {
            val r = (clean.substring(0, 1) + clean.substring(0, 1)).toInt(16)
            val g = (clean.substring(1, 2) + clean.substring(1, 2)).toInt(16)
            val b = (clean.substring(2, 3) + clean.substring(2, 3)).toInt(16)
            Triple(r, g, b)
        } else {
            Triple(255, 120, 0)
        }
    } catch (e: Exception) {
        Triple(255, 120, 0)
    }
}

fun rgbToHex(r: Int, g: Int, b: Int): String {
    return String.format("#%02X%02X%02X", r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerSliders(
    r: Int,
    g: Int,
    b: Int,
    onColorChanged: (Int, Int, Int) -> Unit,
    isLocked: Boolean,
    stateName: String,
    typeName: String
) {
    val quickColors = listOf(
        Pair("Red", "#FF0000"),
        Pair("Amber", "#FF7800"),
        Pair("Yellow", "#FFFF00"),
        Pair("Green", "#00FF00"),
        Pair("Cyan", "#00B4FF"),
        Pair("Blue", "#0000FF"),
        Pair("Purple", "#800080"),
        Pair("White", "#FFFFFF")
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Quick Colors chips array
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickColors.forEach { (name, hex) ->
                val (qr, qg, qb) = hexToRgb(hex)
                val isSelected = r == qr && g == qg && b == qb
                val chipColor = remember(hex) { Color(android.graphics.Color.parseColor(hex)) }

                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(
                            1.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable(enabled = !isLocked) {
                            onColorChanged(qr, qg, qb)
                        }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(chipColor)
                                .border(0.5.dp, Color.White, CircleShape)
                        )
                        Text(
                            text = name,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // RED slider
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Red", fontSize = 11.sp, color = Color.Gray)
                Text(r.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = r.toFloat(),
                    onValueChange = { onColorChanged(it.toInt().coerceIn(0, 255), g, b) },
                    enabled = !isLocked,
                    valueRange = 0f..255f,
                    modifier = Modifier.weight(1f).testTag("slider_${stateName}_${typeName}_r")
                )
                OutlinedTextField(
                    value = r.toString(),
                    onValueChange = { input ->
                        val parsed = input.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        onColorChanged(parsed, g, b)
                    },
                    enabled = !isLocked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(64.dp).testTag("input_${stateName}_${typeName}_r"),
                    singleLine = true
                )
            }
        }

        // GREEN slider
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Green", fontSize = 11.sp, color = Color.Gray)
                Text(g.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = g.toFloat(),
                    onValueChange = { onColorChanged(r, it.toInt().coerceIn(0, 255), b) },
                    enabled = !isLocked,
                    valueRange = 0f..255f,
                    modifier = Modifier.weight(1f).testTag("slider_${stateName}_${typeName}_g")
                )
                OutlinedTextField(
                    value = g.toString(),
                    onValueChange = { input ->
                        val parsed = input.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        onColorChanged(r, parsed, b)
                    },
                    enabled = !isLocked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(64.dp).testTag("input_${stateName}_${typeName}_g"),
                    singleLine = true
                )
            }
        }

        // BLUE slider
        Column {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Blue", fontSize = 11.sp, color = Color.Gray)
                Text(b.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Slider(
                    value = b.toFloat(),
                    onValueChange = { onColorChanged(r, g, it.toInt().coerceIn(0, 255)) },
                    enabled = !isLocked,
                    valueRange = 0f..255f,
                    modifier = Modifier.weight(1f).testTag("slider_${stateName}_${typeName}_b")
                )
                OutlinedTextField(
                    value = b.toString(),
                    onValueChange = { input ->
                        val parsed = input.toIntOrNull()?.coerceIn(0, 255) ?: 0
                        onColorChanged(r, g, parsed)
                    },
                    enabled = !isLocked,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(64.dp).testTag("input_${stateName}_${typeName}_b"),
                    singleLine = true
                )
            }
        }
    }
}
