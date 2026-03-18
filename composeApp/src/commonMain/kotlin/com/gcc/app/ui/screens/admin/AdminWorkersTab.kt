package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.*
import com.gcc.shared.model.VerificationStatus
import com.gcc.shared.model.Worker

@Composable
internal fun AdminWorkersTab(
    workers: List<Worker>,
    onWorkerClick: (workerId: Int) -> Unit = {},
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                "${workers.size} workers",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        items(workers, key = { it.id }) { worker ->
            val verified = worker.verification.status == VerificationStatus.APPROVED
            val statusColor = when (worker.verification.status) {
                VerificationStatus.APPROVED -> GccGreen
                VerificationStatus.PENDING -> GccGold
                VerificationStatus.REJECTED -> GccError
                VerificationStatus.NEEDS_INFO -> GccBlue
            }
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onWorkerClick(worker.id) },
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(worker.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text(
                            worker.email,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = statusColor.copy(alpha = 0.15f),
                    ) {
                        Text(
                            if (verified) "Verified" else worker.verification.status.name.lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        worker.role,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (worker.role == "admin") GccGold else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
