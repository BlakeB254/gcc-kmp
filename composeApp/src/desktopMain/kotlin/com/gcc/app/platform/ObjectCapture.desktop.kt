package com.gcc.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Desktop stub — guided Object Capture is iOS-only (RealityKit). */
actual class ObjectCaptureController actual constructor() {
    actual val stage: StateFlow<ObjectCaptureStage> = MutableStateFlow(ObjectCaptureStage.UNSUPPORTED)
    actual val progress: StateFlow<Double> = MutableStateFlow(0.0)
    actual val outcome: StateFlow<ObjectCaptureOutcome?> = MutableStateFlow(null)
    actual fun advance() {}
    actual fun finishCapture() {}
    actual fun cancel() {}
}

actual fun objectCaptureSupported(): Boolean = false

@Composable
actual fun ObjectCaptureHostView(controller: ObjectCaptureController, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize())
}

actual fun readLocalFileBytes(path: String): ByteArray? =
    runCatching { File(path).takeIf { it.exists() }?.readBytes() }.getOrNull()

actual fun deleteLocalFile(path: String) {
    runCatching { File(path).delete() }
}

actual fun presentUsdzPreview(path: String) {}

// Journal file ops (Pillar 6): desktop never sets a journalPath (see
// sessionJournalPath below), but implements these with plain java.io so
// desktopTest can exercise the real recorder journal read/write path.

actual fun appendLineToFile(path: String, line: String) {
    val file = File(path)
    file.parentFile?.mkdirs()
    java.io.FileWriter(file, /* append = */ true).use { writer ->
        writer.write(line)
        writer.write("\n")
    }
}

actual fun writeFileLines(path: String, lines: List<String>) {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeText(if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
}

actual fun listFilesInDir(dirPath: String): List<String> =
    runCatching { File(dirPath).listFiles()?.map { it.path } ?: emptyList() }.getOrDefault(emptyList())

actual fun sessionJournalPath(sessionId: String): String? = null

actual fun sessionEventLogPath(sessionId: String): String? = null

actual fun scanSessionsDirPath(): String? = null

actual fun captureSessionsDirPath(): String? = null
