package com.gcc.admin.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.unit.dp
import com.gcc.admin.ui.theme.AdminOnSurfaceVariant
import com.gcc.admin.ui.theme.TableBorder
import com.gcc.admin.ui.theme.TableHeaderBg
import com.gcc.admin.ui.theme.TableRowEven
import com.gcc.admin.ui.theme.TableRowHover
import com.gcc.admin.ui.theme.TableRowOdd

/**
 * Describes a single column in a [DataTable].
 *
 * @param header Display name shown in the header row.
 * @param weight Relative width weight (default 1f).
 * @param sortable Whether clicking the header toggles sort order.
 * @param content Composable that renders a cell for the given item.
 */
data class DataColumn<T>(
    val header: String,
    val weight: Float = 1f,
    val sortable: Boolean = false,
    val content: @Composable (T) -> Unit,
)

/**
 * A sortable, paginated data table.
 *
 * Renders [items] across the provided [columns] with alternating row
 * backgrounds, optional row click handling, and prev/next pagination.
 */
@Composable
fun <T> DataTable(
    items: List<T>,
    columns: List<DataColumn<T>>,
    onRowClick: ((T) -> Unit)? = null,
    pageSize: Int = 20,
    modifier: Modifier = Modifier,
) {
    var sortColumnIndex by remember { mutableStateOf<Int?>(null) }
    var sortAscending by remember { mutableStateOf(true) }
    var currentPage by remember { mutableStateOf(0) }
    var hoveredIndex by remember { mutableStateOf(-1) }

    // Reset page when items change size
    val totalPages by remember(items.size, pageSize) {
        derivedStateOf { maxOf(1, (items.size + pageSize - 1) / pageSize) }
    }
    if (currentPage >= totalPages) currentPage = maxOf(0, totalPages - 1)

    val pageItems by remember(items, currentPage, pageSize) {
        derivedStateOf {
            val start = currentPage * pageSize
            items.subList(start, minOf(start + pageSize, items.size))
        }
    }

    Column(modifier = modifier) {
        // ── Header Row ───────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableHeaderBg)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            columns.forEachIndexed { index, column ->
                Row(
                    modifier = Modifier
                        .weight(column.weight)
                        .then(
                            if (column.sortable) {
                                Modifier
                                    .clickable {
                                        if (sortColumnIndex == index) {
                                            sortAscending = !sortAscending
                                        } else {
                                            sortColumnIndex = index
                                            sortAscending = true
                                        }
                                    }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            } else Modifier
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = column.header,
                        style = MaterialTheme.typography.labelMedium,
                        color = AdminOnSurfaceVariant,
                    )
                    if (column.sortable && sortColumnIndex == index) {
                        Icon(
                            imageVector = if (sortAscending) Icons.Default.ArrowDropUp
                            else Icons.Default.ArrowDropDown,
                            contentDescription = if (sortAscending) "Sorted ascending" else "Sorted descending",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }

        Divider(color = TableBorder)

        // ── Body Rows ────────────────────────────────────────────────────
        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(pageItems) { index, item ->
                val isHovered = hoveredIndex == index
                val rowBackground = when {
                    isHovered -> TableRowHover
                    index % 2 == 0 -> TableRowEven
                    else -> TableRowOdd
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(rowBackground)
                        .then(
                            if (onRowClick != null) {
                                Modifier
                                    .clickable { onRowClick(item) }
                                    .pointerHoverIcon(PointerIcon.Hand)
                            } else Modifier
                        )
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    columns.forEach { column ->
                        Box(modifier = Modifier.weight(column.weight)) {
                            column.content(item)
                        }
                    }
                }

                if (index < pageItems.lastIndex) {
                    Divider(color = TableBorder, thickness = 0.5.dp)
                }
            }
        }

        Divider(color = TableBorder)

        // ── Pagination Footer ────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(TableHeaderBg)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${items.size} items",
                style = MaterialTheme.typography.bodySmall,
                color = AdminOnSurfaceVariant,
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Page ${currentPage + 1} of $totalPages",
                    style = MaterialTheme.typography.bodySmall,
                    color = AdminOnSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = { if (currentPage > 0) currentPage-- },
                    enabled = currentPage > 0,
                ) {
                    Icon(
                        Icons.Default.ChevronLeft,
                        contentDescription = "Previous page",
                        tint = if (currentPage > 0) MaterialTheme.colorScheme.onSurface
                        else AdminOnSurfaceVariant,
                    )
                }
                IconButton(
                    onClick = { if (currentPage < totalPages - 1) currentPage++ },
                    enabled = currentPage < totalPages - 1,
                ) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = "Next page",
                        tint = if (currentPage < totalPages - 1) MaterialTheme.colorScheme.onSurface
                        else AdminOnSurfaceVariant,
                    )
                }
            }
        }
    }
}
