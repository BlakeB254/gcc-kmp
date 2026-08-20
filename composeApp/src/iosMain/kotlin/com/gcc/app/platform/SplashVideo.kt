package com.gcc.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerActionAtItemEndPause
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerLayer
import platform.CoreGraphics.CGRect
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSBundle
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol

/**
 * iOS: chrome-free, non-looping `AVPlayer` for the splash hero video (plan §5).
 * A plain `AVPlayerLayer` in a `UIView` (not `AVPlayerViewController`) gives
 * aspect-fill playback with no transport controls; looping stays off and the
 * player pauses on the end frame while the Compose shell cross-fades out.
 *
 * NOTE: iOS targets can't compile on this Linux box — reviewed against the
 * AVFoundation/UIKit K/N interop surface (same `UIKitView` factory pattern as
 * [ObjectCaptureHostView]) but not compiler-verified here.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun SplashVideoPlayer(assetName: String, onFinished: () -> Unit, modifier: Modifier) {
    val currentOnFinished by rememberUpdatedState(onFinished)

    // Resolve the bundled resource by name up front. A missing asset is NOT
    // an error: finish immediately so the caller swaps in the Canvas
    // animation (returning users, asset not yet shipped).
    val videoUrl = remember(assetName) {
        NSBundle.mainBundle.URLForResource(assetName, withExtension = "mp4")
            ?: NSBundle.mainBundle.URLForResource(assetName, withExtension = "mov")
    }
    if (videoUrl == null) {
        DiagnosticsLog.info("splash-video", "asset '$assetName' not bundled — canvas fallback")
        LaunchedEffect(Unit) { currentOnFinished() }
        return
    }

    // Handle keeps the player + end-of-playback observer alive for as long as
    // the view is composed (NSNotificationCenter tokens are not retained).
    val handle = remember(videoUrl) { SplashVideoHandle(videoUrl) { currentOnFinished() } }
    DisposableEffect(handle) {
        handle.start()
        onDispose { handle.release() }
    }

    UIKitView(
        factory = { handle.view },
        modifier = modifier,
    )
}

@OptIn(ExperimentalForeignApi::class)
private class SplashVideoHandle(videoUrl: NSURL, onFinished: () -> Unit) {
    private val player: AVPlayer
    private val endObserver: NSObjectProtocol
    val view: SplashPlayerView

    init {
        val item = AVPlayerItem(uRL = videoUrl)
        player = AVPlayer(playerItem = item)
        // Hold on the end frame instead of looping or advancing past it —
        // the Compose shell fades the splash out from here.
        player.actionAtItemEnd = AVPlayerActionAtItemEndPause
        // A splash plays silent; autoplaying audio on app open is hostile.
        player.muted = true

        view = SplashPlayerView(CGRectMake(0.0, 0.0, 0.0, 0.0))
        view.playerLayer.player = player

        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = AVPlayerItemDidPlayToEndTimeNotification,
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? ->
            onFinished()
        }
    }

    fun start() {
        player.play()
    }

    fun release() {
        player.pause()
        NSNotificationCenter.defaultCenter.removeObserver(endObserver)
        view.playerLayer.player = null
    }
}

/** A UIView whose single sublayer is the player layer, resized on layout. */
@OptIn(ExperimentalForeignApi::class)
private class SplashPlayerView(frame: CValue<CGRect>) : UIView(frame) {
    val playerLayer: AVPlayerLayer = AVPlayerLayer()

    init {
        backgroundColor = UIColor.blackColor
        playerLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
        layer.addSublayer(playerLayer)
    }

    override fun layoutSubviews() {
        super.layoutSubviews()
        playerLayer.frame = bounds
    }
}
