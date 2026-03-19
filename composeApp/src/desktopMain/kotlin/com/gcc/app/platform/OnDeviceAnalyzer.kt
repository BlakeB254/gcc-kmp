package com.gcc.app.platform

import com.gcc.shared.model.DepthAnalysis
import com.gcc.shared.model.OnDeviceAnalysisResult

actual class OnDeviceAnalyzer actual constructor() {
    actual fun isAvailable(): Boolean = false

    actual suspend fun analyzePhoto(
        imageBytes: ByteArray,
        depthAnalysis: DepthAnalysis?,
    ): OnDeviceAnalysisResult? = null

    actual suspend fun generateEmbedding(imageBytes: ByteArray): List<Float>? = null

    actual suspend fun compareImages(imageA: ByteArray, imageB: ByteArray): Float? = null
}
