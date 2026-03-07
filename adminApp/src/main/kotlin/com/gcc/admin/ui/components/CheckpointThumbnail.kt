package com.gcc.admin.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.gcc.shared.model.CheckpointType
import com.gcc.shared.ui.drawAlleyPointScene
import com.gcc.shared.ui.drawEndScene
import com.gcc.shared.ui.drawPhotoPointScene
import com.gcc.shared.ui.drawStartScene

/**
 * Procedural checkpoint thumbnail for the admin checkpoint editor.
 *
 * Uses shared DrawScope drawing functions from the shared module to maintain
 * visual consistency with the mobile app: START (green flag), END (red
 * checkered), PHOTO/RECORD/VERIFY (teal camera), ALLEY (brown alley).
 */
@Composable
fun AdminCheckpointThumbnail(
    type: CheckpointType,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.clip(RoundedCornerShape(4.dp))) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            when (type) {
                CheckpointType.ROUTE_START, CheckpointType.CLEAN -> drawStartScene()
                CheckpointType.ROUTE_END, CheckpointType.VERIFY -> drawEndScene()
                CheckpointType.PHOTO_POINT, CheckpointType.RECORD,
                CheckpointType.RECORD_CLEAN_VERIFY -> drawPhotoPointScene()
                CheckpointType.ALLEY_ENTRANCE -> drawAlleyPointScene()
            }
        }
    }
}
