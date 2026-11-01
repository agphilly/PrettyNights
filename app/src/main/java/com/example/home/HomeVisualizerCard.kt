package com.example.home

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.statemachine.RideState
import kotlin.math.sin

@Composable
fun HomeVisualizerCard(
    selectedMode: String,
    isRunning: Boolean,
    isMusicRunning: Boolean,
    activeState: RideState,
    musicBands: FloatArray,
    uiColorPrimary: Color,
    uiColorSecondary: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    val waveOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "waveOffset"
    )

    if (selectedMode == "STANDARD") {
        // --- Glowing Bike Art visual ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // Background grid pattern
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        val strokeColor = Color.DarkGray.copy(alpha = 0.15f)
                        val step = 35.dp.toPx()
                        var x = 0f
                        while (x < size.width) {
                            drawLine(strokeColor, start = Offset(x, 0f), end = Offset(x, size.height), strokeWidth = 1f)
                            x += step
                        }
                        var y = 0f
                        while (y < size.height) {
                            drawLine(strokeColor, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1f)
                            y += step
                        }
                    }
            )

            Canvas(modifier = Modifier.size(240.dp, 140.dp)) {
                // Wheels
                drawCircle(
                    color = Color.DarkGray.copy(alpha = 0.35f),
                    radius = 28.dp.toPx(),
                    center = Offset(40.dp.toPx(), 95.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx())
                )
                drawCircle(
                    color = Color.DarkGray.copy(alpha = 0.35f),
                    radius = 28.dp.toPx(),
                    center = Offset(200.dp.toPx(), 95.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx())
                )

                // Frame bars
                drawLine(Color.Gray.copy(alpha = 0.35f), start = Offset(40.dp.toPx(), 95.dp.toPx()), end = Offset(100.dp.toPx(), 95.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                drawLine(Color.Gray.copy(alpha = 0.35f), start = Offset(100.dp.toPx(), 95.dp.toPx()), end = Offset(150.dp.toPx(), 40.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                drawLine(Color.Gray.copy(alpha = 0.35f), start = Offset(150.dp.toPx(), 40.dp.toPx()), end = Offset(200.dp.toPx(), 95.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                drawLine(Color.Gray.copy(alpha = 0.35f), start = Offset(40.dp.toPx(), 95.dp.toPx()), end = Offset(90.dp.toPx(), 50.dp.toPx()), strokeWidth = 2.5.dp.toPx())
                drawLine(Color.Gray.copy(alpha = 0.35f), start = Offset(90.dp.toPx(), 50.dp.toPx()), end = Offset(150.dp.toPx(), 40.dp.toPx()), strokeWidth = 2.5.dp.toPx())

                // Draw glowing neon light string if running
                val glowBrush = Brush.linearGradient(
                    colors = listOf(uiColorPrimary, uiColorSecondary)
                )

                if (isRunning) {
                    val path = Path().apply {
                        moveTo(48.dp.toPx(), 90.dp.toPx())
                        lineTo(90.dp.toPx(), 50.dp.toPx())
                        lineTo(142.dp.toPx(), 42.dp.toPx())
                        lineTo(192.dp.toPx(), 90.dp.toPx())
                    }
                    drawPath(
                        path = path,
                        brush = glowBrush,
                        style = Stroke(width = 9.dp.toPx() * pulseAlpha, cap = StrokeCap.Round),
                        alpha = 0.85f
                    )
                    drawPath(
                        path = path,
                        color = Color.White,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }

            // Central Status Text
            Text(
                text = if (isRunning) activeState.name else "STANDBY",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.8.sp,
                color = if (isRunning) {
                    when (activeState) {
                        RideState.BRAKING, RideState.BRAKING_HOLD -> Color.Red
                        RideState.CRUISING -> Color.Cyan
                        RideState.IDLE -> Color(0xFFC084FC)
                    }
                } else Color.LightGray,
                style = TextStyle(
                    shadow = Shadow(
                        color = uiColorPrimary.copy(alpha = 0.75f),
                        blurRadius = 12f
                    )
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 12.dp)
            )
        }
    } else {
        // Equalizer Visualizer (Music Mode)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(170.dp)
                .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(20.dp))
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black)
                .padding(16.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                for (i in 0 until 16) {
                    val amplitude = if (isMusicRunning) {
                        musicBands.getOrElse(i) { 0f }
                    } else {
                        0.15f + 0.15f * sin(waveOffset + i * 0.5f)
                    }

                    val animatedAmplitude by animateFloatAsState(
                        targetValue = amplitude,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        label = "bar_$i"
                    )

                    val barColor = remember(i) {
                        val ratio = i / 15f
                        when {
                            ratio < 0.4f -> Color(0xFF00E5FF) // Cyan
                            ratio < 0.75f -> Color(0xFFD500F9) // Purple / Magenta
                            else -> Color(0xFFFF3D00) // Orange / Red
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(fraction = animatedAmplitude.coerceIn(0.02f, 1.0f))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(barColor)
                            .testTag("eq_bar_$i")
                    )
                }
            }

            Text(
                text = if (isMusicRunning) "STREAMING" else "STANDBY",
                fontSize = 18.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.8.sp,
                color = if (isMusicRunning) Color(0xFFE040FB) else Color.Gray,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
        }
    }
}
