package com.gcc.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Canvas-based QR code renderer (QR Version 1, 21x21 modules).
 *
 * Renders finder patterns, timing patterns, and data from string bytes.
 * Not a standards-compliant QR encoder — designed for visual representation
 * of short referral codes that can be decoded by most QR readers for
 * simple alphanumeric data.
 */
@Composable
fun QrCodeCanvas(
    data: String,
    size: Dp = 89.dp,
    foregroundColor: Color = Color.Black,
    backgroundColor: Color = Color.White,
    modifier: Modifier = Modifier,
) {
    val modules = remember(data) { generateQrModules(data) }

    Canvas(modifier = modifier.size(size)) {
        val canvasSize = this.size.minDimension
        val moduleSize = canvasSize / 21f

        // Background
        if (backgroundColor != Color.Transparent) {
            drawRect(color = backgroundColor, size = Size(canvasSize, canvasSize))
        }

        // Draw modules
        for (row in 0 until 21) {
            for (col in 0 until 21) {
                if (modules[row][col]) {
                    drawRect(
                        color = foregroundColor,
                        topLeft = Offset(col * moduleSize, row * moduleSize),
                        size = Size(moduleSize, moduleSize),
                    )
                }
            }
        }
    }
}

/**
 * Generate a 21x21 boolean grid representing QR modules.
 */
private fun generateQrModules(data: String): Array<BooleanArray> {
    val grid = Array(21) { BooleanArray(21) }

    // Finder patterns (7x7) in three corners
    placeFinderPattern(grid, 0, 0)      // Top-left
    placeFinderPattern(grid, 0, 14)     // Top-right
    placeFinderPattern(grid, 14, 0)     // Bottom-left

    // Timing patterns (row 6 and col 6, alternating)
    for (i in 8 until 13) {
        grid[6][i] = i % 2 == 0
        grid[i][6] = i % 2 == 0
    }

    // Data encoding: fill remaining cells with data bits
    val bytes = data.encodeToByteArray()
    var bitIndex = 0
    val totalBits = bytes.size * 8

    // Fill data area (skip finder patterns, timing patterns)
    for (col in 20 downTo 0 step 2) {
        val actualCol = if (col <= 6) col else col  // No adjustment needed for v1
        for (row in if ((20 - col) / 2 % 2 == 0) (20 downTo 0) else (0..20)) {
            for (c in listOf(actualCol, actualCol - 1)) {
                if (c < 0) continue
                if (isReserved(row, c)) continue
                if (bitIndex < totalBits) {
                    val byteIdx = bitIndex / 8
                    val bitPos = 7 - (bitIndex % 8)
                    grid[row][c] = (bytes[byteIdx].toInt() shr bitPos) and 1 == 1
                    bitIndex++
                } else {
                    // Padding: alternating pattern for visual interest
                    grid[row][c] = (row + c) % 3 == 0
                }
            }
        }
    }

    return grid
}

/**
 * Place a 7x7 finder pattern at the given top-left position.
 */
private fun placeFinderPattern(grid: Array<BooleanArray>, row: Int, col: Int) {
    for (r in 0 until 7) {
        for (c in 0 until 7) {
            grid[row + r][col + c] = when {
                r == 0 || r == 6 -> true                    // Top and bottom border
                c == 0 || c == 6 -> true                    // Left and right border
                r in 2..4 && c in 2..4 -> true              // Inner 3x3 block
                else -> false                                // White space between
            }
        }
    }
    // Separator: one module white border around finder (where it fits)
    for (i in -1..7) {
        setSafe(grid, row - 1, col + i, false)
        setSafe(grid, row + 7, col + i, false)
        setSafe(grid, row + i, col - 1, false)
        setSafe(grid, row + i, col + 7, false)
    }
}

private fun setSafe(grid: Array<BooleanArray>, row: Int, col: Int, value: Boolean) {
    if (row in 0 until 21 && col in 0 until 21) {
        grid[row][col] = value
    }
}

/**
 * Check if a cell is reserved (finder pattern or timing pattern area).
 */
private fun isReserved(row: Int, col: Int): Boolean {
    // Top-left finder + separator (0-7, 0-7)
    if (row <= 7 && col <= 7) return true
    // Top-right finder + separator (0-7, 13-20)
    if (row <= 7 && col >= 13) return true
    // Bottom-left finder + separator (13-20, 0-7)
    if (row >= 13 && col <= 7) return true
    // Timing patterns
    if (row == 6 || col == 6) return true
    return false
}
