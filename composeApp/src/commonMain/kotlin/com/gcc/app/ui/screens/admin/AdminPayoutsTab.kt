package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.components.NetworkImage
import com.gcc.app.ui.theme.GccBlue
import com.gcc.app.ui.theme.GccErrorText
import com.gcc.app.ui.theme.GccGreen
import com.gcc.app.ui.theme.GccWarningText
import com.gcc.shared.api.PendingReview
import com.gcc.shared.api.PendingReviewTrust
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.gcc.shared.model.AdminPayout
import com.gcc.shared.util.GccFormatters

@Composable
internal fun AdminPayoutsTab(
    reviews: List<PendingReview>,
    pendingChecks: List<AdminPayout>,
    recentPayouts: List<AdminPayout>,
    payoutActionError: String?,
    onApprove: (jobId: Int, onResult: (success: Boolean, error: String?) -> Unit) -> Unit,
    onDeny: (jobId: Int, reason: String, onResult: (success: Boolean, error: String?) -> Unit) -> Unit,
    onMarkMailed: (payoutId: Int, onResult: (success: Boolean, error: String?) -> Unit) -> Unit,
    onDismissActionError: () -> Unit,
) {
    // Deny dialog state
    var denyTargetJobId by remember { mutableStateOf<Int?>(null) }
    var denyReason by remember { mutableStateOf("") }
    var actionInProgress by remember { mutableStateOf(false) }
    // Mark-as-mailed confirm state
    var mailTarget by remember { mutableStateOf<AdminPayout?>(null) }

    // Dismiss payout action error snackbar
    payoutActionError?.let { error ->
        LaunchedEffect(error) {
            // error is shown via AlertDialog below; clear after user dismisses
        }
    }

    if (reviews.isEmpty() && pendingChecks.isEmpty() && recentPayouts.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "💰\nNo pending approvals or checks",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (reviews.isNotEmpty()) {
            item(key = "header-approvals") {
                PayoutSectionHeader("Job approvals (${reviews.size})")
            }
            items(reviews, key = { "review-${it.jobId}" }) { review ->
                PayoutReviewCard(
                    review = review,
                    actionInProgress = actionInProgress,
                    onApprove = {
                        actionInProgress = true
                        onApprove(review.jobId) { _, _ -> actionInProgress = false }
                    },
                    onDenyClick = {
                        denyTargetJobId = review.jobId
                        denyReason = ""
                    },
                )
            }
        }

        if (pendingChecks.isNotEmpty()) {
            item(key = "header-checks") {
                PayoutSectionHeader("Checks to mail (${pendingChecks.size})")
            }
            items(pendingChecks, key = { "check-${it.id}" }) { payout ->
                CheckToMailCard(
                    payout = payout,
                    actionInProgress = actionInProgress,
                    onMarkMailed = { mailTarget = payout },
                )
            }
        }

        if (recentPayouts.isNotEmpty()) {
            item(key = "header-recent") {
                PayoutSectionHeader("Recent payouts")
            }
            items(recentPayouts, key = { "recent-${it.id}" }) { payout ->
                RecentPayoutRow(payout)
            }
        }
    }

    // Mark-as-mailed confirm dialog
    mailTarget?.let { payout ->
        AlertDialog(
            onDismissRequest = { mailTarget = null },
            title = { Text("Mark check as mailed?") },
            text = {
                Text(
                    "${GccFormatters.formatCents(payout.amountCents)} to " +
                        "${payout.workerName ?: "worker #${payout.workerId}"}. This records " +
                        "the check as sent and notifies the worker — make sure it's " +
                        "actually in the mail.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    enabled = !actionInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                    onClick = {
                        actionInProgress = true
                        onMarkMailed(payout.id) { _, _ ->
                            actionInProgress = false
                            mailTarget = null
                        }
                    },
                ) { Text("Mark as mailed") }
            },
            dismissButton = {
                TextButton(onClick = { mailTarget = null }) { Text("Cancel") }
            },
        )
    }

    // Deny reason dialog
    denyTargetJobId?.let { jobId ->
        AlertDialog(
            onDismissRequest = { denyTargetJobId = null; denyReason = "" },
            title = { Text("Deny Job #$jobId") },
            text = {
                Column {
                    Text("Enter the reason for denial:", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = denyReason,
                        onValueChange = { denyReason = it },
                        label = { Text("Reason") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = denyReason.isNotBlank() && !actionInProgress,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        val reason = denyReason.trim()
                        actionInProgress = true
                        onDeny(jobId, reason) { _, _ ->
                            actionInProgress = false
                            denyTargetJobId = null
                            denyReason = ""
                        }
                    },
                ) { Text("Deny") }
            },
            dismissButton = {
                TextButton(onClick = { denyTargetJobId = null; denyReason = "" }) { Text("Cancel") }
            },
        )
    }

    // Action error dialog
    payoutActionError?.let { error ->
        AlertDialog(
            onDismissRequest = onDismissActionError,
            title = { Text("Action Failed") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = onDismissActionError) { Text("OK") }
            },
        )
    }
}

@Composable
private fun PayoutSectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun CheckToMailCard(
    payout: AdminPayout,
    actionInProgress: Boolean,
    onMarkMailed: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        payout.workerName ?: "Worker #${payout.workerId}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    payout.workerEmail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    GccFormatters.formatCents(payout.amountCents),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = GccGreen,
                )
            }

            payout.mailingAddress?.let { address ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "📬 ${address.name}\n${address.street}\n${address.city}, ${address.state} ${address.zip}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            payout.note?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "“$note”",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onMarkMailed,
                enabled = !actionInProgress,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
            ) { Text("Mark as mailed") }
        }
    }
}

@Composable
private fun RecentPayoutRow(payout: AdminPayout) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    payout.workerName ?: "Worker #${payout.workerId}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    adminPayoutStatusLabel(payout),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                GccFormatters.formatCents(payout.amountCents),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (payout.status == "failed") MaterialTheme.colorScheme.error else GccGreen,
            )
        }
    }
}

private fun adminPayoutStatusLabel(payout: AdminPayout): String = when (payout.status) {
    "processing" -> "Stripe payout processing"
    "completed" -> "Paid via Stripe"
    "failed" -> "Stripe payout FAILED — reconcile"
    "check_pending" -> "Check waiting to be mailed"
    "check_mailed" -> "Check mailed"
    else -> payout.status
}

/** "near_duplicate_before_after" → "near duplicate before after". */
private fun flagLabel(flag: String): String = flag.replace('_', ' ')

private fun litres(value: Double): String = "${(value * 10).toLong() / 10.0} L"

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

private fun yesNo(value: Boolean?): String = when (value) {
    true -> "yes"
    false -> "no"
    null -> "?"
}

/**
 * Detail lines from the review worker's verdict (scripts/ai_review/verdict.py
 * `aggregate`): item counts before → after, same location, cleaned,
 * confidence and the first concerns; or the error text of an 'error' row.
 */
private fun aiVerdictLines(verdict: JsonElement?): List<String> {
    val v = verdict as? JsonObject ?: return emptyList()
    v.str("error")?.let { return listOf("Error: ${it.take(160)}") }
    val lines = mutableListOf<String>()
    val before = (v["items_before"] as? JsonObject)?.num("count")?.toInt()
    val after = (v["items_after"] as? JsonObject)?.num("count")?.toInt()
    if (before != null && after != null) lines += "Litter items: $before before → $after after"
    (v["same_location"] as? JsonObject)?.let { loc ->
        val reason = loc.str("reason")?.takeIf { it.isNotBlank() }?.let { " — ${it.take(120)}" } ?: ""
        lines += "Same location: ${yesNo(loc.bool("value"))}$reason"
    }
    val confidence = v.num("confidence")?.let { " · confidence ${(it * 100).toInt()}%" } ?: ""
    if (v.containsKey("cleaned")) lines += "Cleaned: ${yesNo(v.bool("cleaned"))}$confidence"
    (v["concerns"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        ?.take(2)
        ?.forEach { lines += "Concern: ${it.take(140)}" }
    return lines
}

/** Tabular figures, so counts and ratios line up between cards. */
private val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** Severity of one server check. Conveyed by icon + text label, never colour alone. */
private enum class CheckLevel(val icon: ImageVector, val label: String) {
    OK(Icons.Filled.CheckCircle, "OK"),
    INFO(Icons.Filled.Info, "INFO"),
    WARN(Icons.Filled.Warning, "FLAGGED"),
    ERROR(Icons.Filled.Error, "ERROR"),
}

/**
 * Text/icon colour per level. Text on the light card is only brand green /
 * brand blue (logo tokens) or a status-text token that clears WCAG AA 4.5:1
 * on white; brand sky (2.98:1) is never used for text.
 */
@Composable
private fun CheckLevel.tint(): Color = when (this) {
    CheckLevel.OK -> GccGreen
    CheckLevel.INFO -> MaterialTheme.colorScheme.onSurface
    CheckLevel.WARN -> GccWarningText
    CheckLevel.ERROR -> GccErrorText
}

/** One check: icon + optional bold label (e.g. "SUSPECT") + 14sp text. */
@Composable
private fun CheckLine(level: CheckLevel, text: String, label: String? = null) {
    val tint = level.tint()
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            imageVector = level.icon,
            contentDescription = label ?: level.label,
            tint = tint,
            modifier = Modifier.size(18.dp).padding(top = 1.dp),
        )
        Text(
            text = buildAnnotatedString {
                if (label != null) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
                    append("  ")
                }
                append(text)
            },
            style = MaterialTheme.typography.bodyMedium.merge(Tabular),
            color = if (level == CheckLevel.INFO) MaterialTheme.colorScheme.onSurface else tint,
        )
    }
}

/**
 * What the server checked for this job (site/src/lib/review-trust.ts): the
 * qualifying scan's coverage and GPS plausibility, hotspot volume as claimed
 * vs counted, photo fingerprint flags, and the AI review.
 */
@Composable
internal fun ServerChecks(trust: PendingReviewTrust) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "Server checks",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = GccBlue,
        )
        val scan = trust.scan
        if (scan == null) {
            CheckLine(CheckLevel.WARN, "No qualifying scan on record for this job", label = "NO SCAN")
        } else {
            val ratio = scan.verifiedCoverageRatio
            if (ratio == null) {
                CheckLine(CheckLevel.WARN, "Route coverage not verified by the server", label = "UNVERIFIED")
            } else {
                CheckLine(CheckLevel.OK, "Route coverage (server): ${(ratio * 100).toInt()}%")
            }
            val gpsText = "GPS fixes: ${scan.gpsPointsKept} kept, ${scan.gpsPointsDropped} dropped"
            if (scan.gpsSuspect) {
                CheckLine(CheckLevel.ERROR, gpsText, label = "SUSPECT")
            } else {
                CheckLine(if (scan.gpsPointsDropped > 0) CheckLevel.INFO else CheckLevel.OK, gpsText)
            }
            val reasons = scan.gpsDropReasons.filterValues { it > 0 }
            if (reasons.isNotEmpty()) {
                CheckLine(CheckLevel.INFO, "Dropped: " + reasons.entries.joinToString { "${flagLabel(it.key)} ${it.value}" })
            }
        }
        trust.volume?.takeIf { it.hotspots > 0 }?.let { v ->
            if (v.clientLiters > v.cappedLiters + 0.05) {
                CheckLine(
                    CheckLevel.WARN,
                    "Hotspot volume: ${litres(v.cappedLiters)} counted, client claimed ${litres(v.clientLiters)}",
                    label = "CAPPED",
                )
            } else {
                CheckLine(
                    CheckLevel.OK,
                    "Hotspot volume: ${litres(v.cappedLiters)} across ${v.hotspots} hotspot${if (v.hotspots == 1) "" else "s"}",
                )
            }
        }
        val flagged = trust.photos.filter { it.flags.isNotEmpty() || it.status == "unverified" }
        val unchecked = trust.photos.count { it.status == "not_fingerprinted" }
        if (unchecked > 0) {
            CheckLine(CheckLevel.INFO, "Photos: $unchecked not fingerprinted (submitted before checks existed)")
        } else if (flagged.isEmpty() && trust.photos.isNotEmpty()) {
            CheckLine(CheckLevel.OK, "Photos: no duplicates or reuse found")
        }
        flagged.forEach { p ->
            val reused = p.flags.any { it.startsWith("reused") || it.startsWith("identical") }
            CheckLine(
                if (reused) CheckLevel.ERROR else CheckLevel.WARN,
                "${flagLabel(p.role)} photo: " + p.flags.ifEmpty { listOf(p.status) }.joinToString { flagLabel(it) },
                label = if (reused) "DUPLICATE" else "CHECK",
            )
        }
        val ai = trust.aiReview
        if (ai == null) {
            CheckLine(CheckLevel.INFO, "AI review: not reviewed yet")
        } else {
            val level = when (ai.status) {
                "ok" -> CheckLevel.OK
                "flagged" -> CheckLevel.WARN
                else -> CheckLevel.ERROR
            }
            // `model` exactly as the review worker stored it.
            CheckLine(level, "AI review by ${ai.model}", label = ai.status.uppercase())
            aiVerdictLines(ai.verdict).forEach { CheckLine(CheckLevel.INFO, it) }
        }
    }
}

@Composable
internal fun PayoutReviewCard(
    review: PendingReview,
    actionInProgress: Boolean,
    onApprove: () -> Unit,
    onDenyClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp),
        // White, so every text colour below is measured against #FFFFFF.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: worker + payout amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = review.workerName ?: "Unknown Worker",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "Job #${review.jobId}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val amountText = review.payoutAmount
                    ?.let { com.gcc.shared.util.GccFormatters.formatCents(it.toInt()) }
                    ?: "N/A"
                Text(
                    text = amountText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = GccGreen,
                )
            }

            // Scan indicator
            if (review.hasScan) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (review.scanDepthAvailable) "🔬 Scan + Depth Data" else "📡 Route Scan",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = GccBlue,
                    )
                }
            }

            review.trust?.let { trust ->
                Spacer(Modifier.height(8.dp))
                ServerChecks(trust)
            }

            // Before / After photos side-by-side
            if (review.beforePhotos.isNotEmpty() || review.afterPhotos.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Before column
                    if (review.beforePhotos.isNotEmpty()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Before",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            NetworkImage(
                                url = review.beforePhotos.first(),
                                contentDescription = "Before photo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                            )
                            if (review.beforePhotos.size > 1) {
                                Text(
                                    "+${review.beforePhotos.size - 1} more",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    // After column
                    if (review.afterPhotos.isNotEmpty()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "After",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            NetworkImage(
                                url = review.afterPhotos.first(),
                                contentDescription = "After photo",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(1f),
                            )
                            if (review.afterPhotos.size > 1) {
                                Text(
                                    "+${review.afterPhotos.size - 1} more",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Approve / Deny buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onDenyClick,
                    enabled = !actionInProgress,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        // colorScheme.error (#EF4444) is 3.8:1 on white — too low for text.
                        contentColor = GccErrorText,
                    ),
                ) { Text("Deny") }
                Button(
                    onClick = onApprove,
                    enabled = !actionInProgress,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = GccGreen),
                ) { Text("Approve") }
            }
        }
    }
}
