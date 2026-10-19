package com.gcc.app.ui.screens.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gcc.app.ui.theme.GccTheme
import com.gcc.shared.api.PendingReview
import com.gcc.shared.serialization.GccJson
import kotlinx.serialization.decodeFromString
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Renders the admin payout review cards to a PNG, for PR screenshots.
 * A no-op unless GCC_REVIEW_SAMPLE_JSON (a GET /api/admin/jobs/pending-review
 * `data` array) and GCC_REVIEW_SCREENSHOT_OUT are set, so CI never runs it.
 */
class ReviewCardScreenshotTest {
    @Test
    fun renderReviewCards() {
        val input = System.getenv("GCC_REVIEW_SAMPLE_JSON") ?: return
        val output = System.getenv("GCC_REVIEW_SCREENSHOT_OUT") ?: return
        val reviews = GccJson.decodeFromString<List<PendingReview>>(File(input).readText())
        val cardWidth = 420
        val scene = ImageComposeScene(
            width = ((cardWidth + 16) * reviews.size + 16) * 2,
            height = 940 * 2,
            density = Density(2f),
        ) {
            GccTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        reviews.forEach { review ->
                            Row(Modifier.width(cardWidth.dp)) {
                                PayoutReviewCard(review = review, actionInProgress = false, onApprove = {}, onDenyClick = {})
                            }
                        }
                    }
                }
            }
        }
        // Give the async photo loads time to land before the final frame.
        var nanos = 0L
        repeat(60) {
            scene.render(nanos)
            nanos += 100_000_000L
            Thread.sleep(100)
        }
        val image = scene.render(nanos)
        File(output).writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
    }
}
