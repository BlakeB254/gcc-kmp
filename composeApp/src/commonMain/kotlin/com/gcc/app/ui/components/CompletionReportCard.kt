package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.CardAccentBlue
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.GccSky
import com.gcc.app.ui.theme.TextMuted
import com.gcc.shared.model.BlockCompletionImagePair
import com.gcc.shared.model.BlockCompletionReport
import com.gcc.shared.model.Coordinates
import com.gcc.shared.model.asLineStringCoordinates

/**
 * Pillar 9 worker-facing completion report: duration, mini route path (scout
 * lap in sky-blue, clean lap in green), weight cleaned/identified, counts,
 * and up to [maxPhotoPairs] before/after photo rows.
 *
 * The report is generated non-fatally on the server right after job
 * completion (see `site/src/lib/block-completion-report.ts`), so it may not
 * exist yet when this card first renders — [isLoading] covers the initial
 * (and one automatic retry) fetch window, and a null [report] afterward is
 * the "still generating" steady state with a manual [onRetry] affordance,
 * not an error.
 *
 * Deliberately does not use the shared map component tree (GccMap /
 * GeoJsonLayers) — those are owned by the concurrent Pillar 8 map work. The
 * mini route path below is a standalone Canvas polyline scoped to this file.
 */
@Composable
fun CompletionReportCard(
    report: BlockCompletionReport?,
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    maxPhotoPairs: Int = 6,
) {
    GccCard(modifier = modifier, accentColor = CardAccentBlue) {
        Text(
            "Completion Report",
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
        )

        when {
            error != null -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(modifier = Modifier.height(8.dp))
                GccButton(text = "Retry", onClick = onRetry, variant = GccButtonVariant.OUTLINED)
            }

            isLoading -> {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = GccGreen)
                    Text(
                        "Putting together your report…",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextMuted,
                    )
                }
            }

            report == null -> {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Your report is still generating. This can take a minute after a big block.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted,
                )
                Spacer(modifier = Modifier.height(8.dp))
                GccButton(text = "Check again", onClick = onRetry, variant = GccButtonVariant.OUTLINED)
            }

            else -> CompletionReportContent(report, maxPhotoPairs)
        }
    }
}

@Composable
private fun CompletionReportContent(report: BlockCompletionReport, maxPhotoPairs: Int) {
    val scoutPath = report.gpsTrack?.scout.asLineStringCoordinates()
    val cleanPath = report.gpsTrack?.clean.asLineStringCoordinates()

    if (scoutPath.isNotEmpty() || cleanPath.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        MiniRoutePathCanvas(
            scoutPath = scoutPath,
            cleanPath = cleanPath,
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(10.dp)),
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ReportStat(value = durationLabel(report.durationSeconds), label = "Duration")
        ReportStat(value = weightLabel(report.cleanedEstimatedWeightLbs), label = "Cleaned")
        ReportStat(value = weightLabel(report.totalEstimatedWeightLbs), label = "Identified")
        ReportStat(value = "${report.areasCleaned?.size ?: 0}", label = "Areas")
    }

    val counts = report.countsByType
    if (counts != null && (counts.byType.isNotEmpty() || counts.bySeverity.isNotEmpty())) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "By type",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Text(
            counts.byType.entries.joinToString(" · ") { (type, count) -> "$type: $count" }
                .ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            color = TextMuted,
        )
    }

    val pairs = report.imagePairs.orEmpty()
    if (pairs.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            "Before / After",
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(modifier = Modifier.height(6.dp))
        pairs.take(maxPhotoPairs).forEach { pair ->
            BeforeAfterRow(pair)
            Spacer(modifier = Modifier.height(8.dp))
        }
        if (pairs.size > maxPhotoPairs) {
            Text(
                "+${pairs.size - maxPhotoPairs} more",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }
    }
}

@Composable
private fun ReportStat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted)
    }
}

@Composable
private fun BeforeAfterRow(pair: BlockCompletionImagePair) {
    Column {
        pair.itemType?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = TextMuted)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NetworkImage(
                url = pair.before.photoUrl,
                contentDescription = "Before",
                modifier = Modifier.size(96.dp),
            )
            val after = pair.after
            if (after != null) {
                NetworkImage(
                    url = after.photoUrl,
                    contentDescription = "After",
                    modifier = Modifier.size(96.dp),
                )
            } else {
                Column(
                    modifier = Modifier.size(96.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "No after photo yet",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                    )
                }
            }
        }
    }
}

/**
 * Standalone Canvas polyline of the two-lap GPS track — scout (lap 1, sky
 * blue) and clean (lap 2, green) — normalized into the drawing bounds by the
 * combined lat/lng bbox of both tracks. Deliberately not built on the shared
 * map stack; see the file-level doc comment on [CompletionReportCard].
 */
@Composable
private fun MiniRoutePathCanvas(
    scoutPath: List<Coordinates>,
    cleanPath: List<Coordinates>,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val allPoints = scoutPath + cleanPath
        if (allPoints.isEmpty()) return@Canvas

        val minLat = allPoints.minOf { it.lat }
        val maxLat = allPoints.maxOf { it.lat }
        val minLng = allPoints.minOf { it.lng }
        val maxLng = allPoints.maxOf { it.lng }
        val latSpan = (maxLat - minLat).takeIf { it > 1e-9 } ?: 1e-9
        val lngSpan = (maxLng - minLng).takeIf { it > 1e-9 } ?: 1e-9
        val pad = size.minDimension * 0.12f

        fun project(c: Coordinates): Offset {
            val x = pad + ((c.lng - minLng) / lngSpan).toFloat() * (size.width - 2 * pad)
            // Screen y grows downward; lat grows northward, so flip.
            val y = (size.height - pad) - ((c.lat - minLat) / latSpan).toFloat() * (size.height - 2 * pad)
            return Offset(x, y)
        }

        fun drawTrack(points: List<Coordinates>, color: Color) {
            if (points.size < 2) return
            val path = Path()
            val first = project(points.first())
            path.moveTo(first.x, first.y)
            points.drop(1).forEach { point ->
                val p = project(point)
                path.lineTo(p.x, p.y)
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }

        drawTrack(scoutPath, GccSky.copy(alpha = 0.65f))
        drawTrack(cleanPath, GccGreen)
    }
}

private fun durationLabel(durationSeconds: Int?): String {
    if (durationSeconds == null) return "—"
    val minutes = durationSeconds / 60
    val seconds = durationSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

private fun weightLabel(lbs: Double?): String {
    if (lbs == null || lbs <= 0.0) return "—"
    return "${(lbs * 10).let { kotlin.math.round(it) / 10 }} lbs"
}
