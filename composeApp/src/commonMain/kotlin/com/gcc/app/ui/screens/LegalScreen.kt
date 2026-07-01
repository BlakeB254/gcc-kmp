package com.gcc.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.app.platform.appVersion
import com.gcc.app.ui.theme.*

enum class LegalScreenType(val title: String) {
    TERMS("Terms of Service"),
    PRIVACY("Privacy Policy"),
    HELP("Help & FAQ"),
    ABOUT("About"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalScreen(
    type: String = "terms",
    onBack: () -> Unit = {},
) {
    val screenType = try {
        LegalScreenType.valueOf(type.uppercase())
    } catch (_: Exception) {
        LegalScreenType.TERMS
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(screenType.title) },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("\u2190 Back", color = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = GccGreen,
                    titleContentColor = Color.White,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (screenType) {
                LegalScreenType.TERMS -> TermsContent()
                LegalScreenType.PRIVACY -> PrivacyContent()
                LegalScreenType.HELP -> HelpContent()
                LegalScreenType.ABOUT -> AboutContent()
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun TermsContent() {
    LegalSection("1. Acceptance of Terms",
        "By creating an account and using the Global Community Cleaners (\"GCC\") mobile application, you agree to be bound by these Terms of Service. If you do not agree, do not use the app.")
    LegalSection("2. Eligibility",
        "You must be at least 18 years of age and legally authorized to work in the United States. You must provide accurate identification and pass any required background checks.")
    LegalSection("3. Account Obligations",
        "You are responsible for maintaining the security of your account credentials. You must not share your account or allow unauthorized access. You agree to provide truthful, accurate information.")
    LegalSection("4. Service Description",
        "GCC connects community cleaning workers with route-based cleaning assignments in the Chicago metropolitan area. Workers accept routes, complete GPS-verified checkpoints, submit photo documentation, and receive payment for completed work.")
    LegalSection("5. Payment Terms",
        "Payouts are calculated per completed job and displayed before route acceptance. Payments are processed weekly via Stripe. GCC reserves the right to withhold payment for disputed or unverified work.")
    LegalSection("6. Photo Documentation & GPS",
        "Certain checkpoints require before/after photo documentation. By using the app, you consent to GPS location tracking during active jobs for route verification and worker safety.")
    LegalSection("7. Dispute Resolution",
        "Disputes will be resolved through binding arbitration in Cook County, Illinois. You waive your right to a jury trial and class action participation.")
    LegalSection("8. Limitation of Liability",
        "GCC is not liable for injuries, property damage, or losses incurred during cleaning operations. Workers are independent contractors and responsible for their own safety equipment and insurance.")
    LegalSection("9. Termination",
        "GCC may terminate your account at any time for violation of these terms, fraudulent activity, or failure to meet quality standards. You may delete your account at any time through app settings.")
    LegalSection("10. Governing Law",
        "These terms are governed by the laws of the State of Illinois. Global Community Cleaners NFP, Chicago, IL.")
}

@Composable
private fun PrivacyContent() {
    LegalSection("Data We Collect",
        "We collect: name, email address, phone number, GPS location during active jobs, photos submitted for checkpoint verification, device information, and usage analytics.")
    LegalSection("How We Use Your Data",
        "Your data is used to: assign and verify route completions, process payments, ensure worker safety, improve our services, and communicate important updates.")
    LegalSection("Third-Party Sharing",
        "We share data with: Stripe (payment processing), cloud storage providers (photo storage), and analytics services. We do not sell your personal information.")
    LegalSection("GPS & Location Data",
        "Location tracking is active only during accepted jobs. GPS data is used for checkpoint verification and safety. You may disable location services, but active jobs require GPS access.")
    LegalSection("Photo Data",
        "Photos submitted for checkpoint verification are stored securely and used solely for job verification and quality assurance. Photos may be retained for up to 90 days after job completion.")
    LegalSection("Illinois BIPA Compliance",
        "GCC does not collect biometric data (fingerprints, facial geometry). Photos are used for documentation purposes only and are not processed through facial recognition systems.")
    LegalSection("Data Retention & Deletion",
        "Account data is retained while your account is active. You may request data deletion by contacting privacy@globalcommunitycleaners.org. GPS and photo data is retained for 90 days after job completion.")
    LegalSection("Your Rights",
        "Under Illinois law, you have the right to: access your personal data, request correction of inaccurate data, request deletion of your data, and opt out of non-essential communications.")
    LegalSection("Contact",
        "For privacy inquiries: privacy@globalcommunitycleaners.org\nGlobal Community Cleaners NFP, Chicago, IL")
}

@Composable
private fun HelpContent() {
    LegalSection("How do I start a route?",
        "From the Dashboard, tap an available route to view details. Tap \"Claim Route\" to accept it. Once claimed, tap \"Start\" to begin GPS tracking and checkpoint completion.")
    LegalSection("What if I can't complete a route?",
        "If you need to abandon a route, contact support. Frequently abandoned routes may affect your account standing.")
    LegalSection("How do photos work?",
        "Some checkpoints require before/after photos. When you reach a photo checkpoint, the app will prompt you to take a \"before\" photo, complete the cleaning, then take an \"after\" photo.")
    LegalSection("When do I get paid?",
        "Payouts are processed weekly on Fridays for all jobs completed and verified by Wednesday. Payment is via Stripe direct deposit.")
    LegalSection("What are badges?",
        "Badges are earned by reaching milestones: completing jobs, earning thresholds, and maintaining streaks. Badges are visible on your profile and the leaderboard.")
    LegalSection("Need more help?",
        "Email support@globalcommunitycleaners.org or visit our website at globalcommunitycleaners.org.")
}

@Composable
private fun AboutContent() {
    Text(
        "Global Community Cleaners",
        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        color = GccGreen,
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "A Not-For-Profit Organization",
        style = MaterialTheme.typography.titleMedium,
        color = TextMuted,
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Our mission is to create sustainable employment opportunities through community cleaning while beautifying Chicago's neighborhoods. We connect motivated workers with funded cleaning routes, providing fair wages, GPS-verified accountability, and a gamified experience that rewards dedication.",
        style = MaterialTheme.typography.bodyMedium,
    )
    Spacer(modifier = Modifier.height(16.dp))
    LegalSection("Location", "Chicago, Illinois")
    LegalSection("App Version", appVersion())
    LegalSection("Website", "globalcommunitycleaners.org")
    LegalSection("Contact", "info@globalcommunitycleaners.org")
}

@Composable
private fun LegalSection(title: String, content: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            content,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    }
}
