package com.gcc.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gcc.app.platform.DiagnosticsLog

private val ErrorRed = Color(0xFFFF6B6B)

/**
 * Field-testing diagnostics: a small floating 🐞 badge (with error count)
 * that expands into a scrollable panel of [DiagnosticsLog] entries, newest
 * first. Meant to be included on scan-flow screens so an error screenshot
 * captures the log tail. Place LAST in the host Box so it sits above all
 * other chrome (including the go-outside scrim).
 */
@Composable
fun DiagnosticsOverlay(modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val entries by DiagnosticsLog.entries.collectAsState()
    val errorCount by DiagnosticsLog.errorCount.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        if (expanded) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.6f)
                    .background(Color.Black.copy(alpha = 0.92f))
                    .navigationBarsPadding()
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Diagnostics — $errorCount error(s)",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "close ✕",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { expanded = false }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
                Spacer(modifier = Modifier.padding(top = 4.dp))
                if (entries.isEmpty()) {
                    Text(
                        text = "No diagnostics yet this session.",
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(entries) { entry ->
                        Text(
                            text = entry.format(),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                            color = if (entry.level == DiagnosticsLog.Level.ERROR) {
                                ErrorRed
                            } else {
                                Color.White.copy(alpha = 0.85f)
                            },
                            modifier = Modifier.padding(vertical = 1.dp),
                        )
                    }
                }
            }
        }

        // Collapsed badge — top-right, row 3 of the scan chrome (TestFlight
        // 145 overlap fix): row 1 (52dp) is the guidance caption's band,
        // row 2 (96dp) the scan-quality badge; at 52dp this badge sat ON
        // TOP of both.
        if (!expanded) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 140.dp, end = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(
                        if (errorCount > 0) ErrorRed.copy(alpha = 0.9f)
                        else Color.Black.copy(alpha = 0.45f)
                    )
                    .clickable { expanded = true }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (errorCount > 0) "🐞 $errorCount" else "🐞",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
        }
    }
}
