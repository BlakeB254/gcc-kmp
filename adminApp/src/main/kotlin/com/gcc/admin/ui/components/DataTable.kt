package com.gcc.admin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.*

/**
 * Describes a single column in a [DataTable].
 *
 * @param header The text displayed in the header row.
 * @param weight The relative width weight of this column.
 * @param content Composable that renders the cell value for a given row item.
 */
data class TableColumn<T>(
    val header: String,
    val weight: Float = 1f,
    val content: @Composable (T) -> Unit,
)

/**
 * A sortable, scrollable data table for admin list views.
 *
 * Features:
 * - Bold header row with distinct background
 * - Alternating row backgrounds for readability
 * - Hover highlight effect on rows
 * - Optional row click handler
 *
 * @param items The list of data items to display.
 * @param columns Column definitions controlling header text, width, and cell rendering.
 * @param onRowClick Optional callback invoked when a row is clicked.
 * @param modifier Modifier applied to the outer container.
 */
@Composable
fun <T> DataTable(
    items: List<T>,
    columns: List<TableColumn<T>>,
    onRowClick: ((T) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(AdminSurface),
    ) {
        // ── Header Row ───────────────────────────────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(TableHeaderBg)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            columns.forEach { column ->
                Box(
                    modifier = Modifier.weight(column.weight),
                ) {
                    Text(
                        text = column.header,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = AdminOnSurfaceVariant,
                    )
                }
            }
        }

        // ── Data Rows ────────────────────────────────────────────────────
        if (items.isEmpty()) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
            ) {
                Text(
                    text = "No data available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AdminOnSurfaceVariant,
                )
            }
        } else {
            LazyColumn {
                itemsIndexed(items) { index, item ->
                    DataTableRow(
                        item = item,
                        columns = columns,
                        isEven = index % 2 == 0,
                        onClick = if (onRowClick != null) {
                            { onRowClick(item) }
                        } else null,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> DataTableRow(
    item: T,
    columns: List<TableColumn<T>>,
    isEven: Boolean,
    onClick: (() -> Unit)?,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val rowBackground = when {
        isHovered -> TableRowHover
        isEven -> TableRowEven
        else -> TableRowOdd
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .hoverable(interactionSource)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        onClick = onClick,
                    )
                } else Modifier
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        columns.forEach { column ->
            Box(
                modifier = Modifier.weight(column.weight),
            ) {
                column.content(item)
            }
        }
    }
}
