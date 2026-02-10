package com.gcc.admin.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.components.*
import com.gcc.admin.viewmodel.SponsorManagementViewModel
import org.koin.compose.koinInject

/**
 * Detail screen for a single sponsor showing all sponsor information.
 */
@Composable
fun AdminSponsorDetailScreen(
    sponsorId: Int,
    onBack: () -> Unit,
    viewModel: SponsorManagementViewModel = koinInject(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(sponsorId) {
        viewModel.selectSponsor(sponsorId)
    }

    AdminScaffold(
        title = "Sponsor Detail",
        subtitle = "Sponsors",
        actions = {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        val sponsor = state.selectedSponsor
        if (sponsor == null) {
            Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Column(modifier = Modifier.padding(24.dp)) {
                if (state.error != null) {
                    Text(
                        text = state.error ?: "",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Sponsor Info
                    DetailPanel(
                        title = "Sponsor Information",
                        items = listOf(
                            "ID" to sponsor.id.toString(),
                            "Name" to sponsor.name,
                            "Email" to sponsor.email,
                            "Phone" to (sponsor.phone ?: "N/A"),
                            "Type" to sponsor.type.name,
                            "Email Verified" to if (sponsor.emailVerified) "Yes" else "No",
                            "Joined" to sponsor.createdAt.toString().take(10),
                        ),
                        modifier = Modifier.weight(1f),
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        // Business Info
                        DetailPanel(
                            title = "Business Details",
                            items = listOf(
                                "Business Name" to (sponsor.business?.name ?: "N/A"),
                                "Website" to (sponsor.business?.website ?: "N/A"),
                                "Description" to (sponsor.business?.description ?: "N/A"),
                            ),
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Preferences
                        DetailPanel(
                            title = "Preferences",
                            items = listOf(
                                "Frequency" to sponsor.preferences.frequency.name,
                                "Neighborhood" to (sponsor.preferences.neighborhoodPreference ?: "Any"),
                                "Wants Updates" to if (sponsor.preferences.wantsUpdates) "Yes" else "No",
                                "Anonymous" to if (sponsor.preferences.isAnonymous) "Yes" else "No",
                            ),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Stats
                DetailPanel(
                    title = "Funding Statistics",
                    items = listOf(
                        "Total Donated" to ("$" + (sponsor.stats.totalDonated / 100).toString()),
                        "Blocks Sponsored" to sponsor.stats.blocksSponsored.toString(),
                        "Last Donation" to (sponsor.stats.lastDonationAt?.toString()?.take(10) ?: "Never"),
                    ),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Stripe Info
                DetailPanel(
                    title = "Stripe",
                    items = listOf(
                        "Customer ID" to (sponsor.stripe.customerId ?: "Not connected"),
                        "Subscription" to (sponsor.stripe.subscriptionId ?: "None"),
                        "Payment Method" to (sponsor.stripe.defaultPaymentMethod ?: "None"),
                    ),
                )
            }
        }
    }
}
