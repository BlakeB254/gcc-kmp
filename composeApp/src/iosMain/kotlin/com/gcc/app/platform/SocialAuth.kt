package com.gcc.app.platform

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.*
import platform.Foundation.*
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

actual fun isAppleSignInAvailable(): Boolean = true

// ── Google Sign-In bridge ─────────────────────────────────────────────
//
// Kotlin/Native cannot speak to Google's GoogleSignIn iOS SDK directly
// (it's a Swift/Obj-C library distributed via SPM, not Apple's first-
// party AVFoundation/AuthenticationServices bindings). We use the same
// bridge pattern as PushNotificationManager: Swift sets a callback at
// app startup; Kotlin invokes it when the user taps "Continue with
// Google"; Swift drives the GIDSignIn sheet and reports back via the
// inner result callback.

/**
 * Bridge between Kotlin/Native and the Swift GoogleSignIn SDK.
 *
 * The Swift side (AppDelegate at app launch) must:
 *   1. Configure `GIDSignIn.sharedInstance.configuration` with the iOS
 *      OAuth client ID from Google Cloud Console.
 *   2. Set [signInCallback] to a closure that presents
 *      `GIDSignIn.sharedInstance.signIn(presentingViewController:)`
 *      and reports the resulting idToken / profile back via the
 *      provided result callback.
 *   3. Set [isConfigured] = true once both of the above are complete.
 *
 * If the bridge is not configured (e.g. SPM package missing, client ID
 * not set), [signInWithGoogle] returns null and the caller should
 * surface a friendly "Google Sign-In is not available right now" message.
 */
object GoogleSignInBridge {
    /**
     * Set true by Swift once GIDSignIn is configured. Drives
     * [isGoogleSignInAvailable] for UI.
     */
    var isConfigured: Boolean = false

    /**
     * Invoked by Kotlin to start a Google sign-in. The outer lambda is
     * the bridge entry point; Swift calls the inner result lambda when
     * the sheet completes.
     *
     * Result lambda parameters:
     *   - idToken: Google's OIDC id_token, or null on cancel/error.
     *   - fullName: profile display name, may be null.
     *   - email: user email from the verified profile, may be null.
     */
    var signInCallback: ((onResult: (idToken: String?, fullName: String?, email: String?) -> Unit) -> Unit)? = null
}

actual fun isGoogleSignInAvailable(): Boolean =
    GoogleSignInBridge.isConfigured && GoogleSignInBridge.signInCallback != null

actual suspend fun signInWithGoogle(): SocialAuthResult? = suspendCancellableCoroutine { cont ->
    val callback = GoogleSignInBridge.signInCallback
    if (callback == null) {
        if (cont.isActive) cont.resume(null)
        return@suspendCancellableCoroutine
    }

    callback { idToken, fullName, email ->
        if (!cont.isActive) return@callback
        if (idToken == null) {
            cont.resume(null)
        } else {
            cont.resume(
                SocialAuthResult(
                    identityToken = idToken,
                    fullName = fullName,
                    email = email,
                )
            )
        }
    }
}

/**
 * Strong-reference holder for in-flight Apple Sign-In requests.
 *
 * `ASAuthorizationController.delegate` and `.presentationContextProvider` are
 * `weak` properties on the Obj-C side. The Kotlin objects we assign to them
 * have no other owner once this function returns to the suspending coroutine,
 * so Kotlin/Native may free them before the user finishes the sheet — making
 * the button silently fail. We pin the delegate, presenter, and controller
 * here for the duration of the request.
 */
private val pendingAppleAuthRequests = mutableListOf<Any>()

private const val NONCE_LENGTH = 32
private const val NONCE_CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._"
private val SHA256_K = intArrayOf(
    0x428a2f98.toInt(), 0x71374491.toInt(), 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
    0x3956c25b.toInt(), 0x59f111f1.toInt(), 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01.toInt(), 0x243185be.toInt(), 0x550c7dc3.toInt(),
    0x72be5d74.toInt(), 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6.toInt(), 0x240ca1cc.toInt(),
    0x2de92c6f.toInt(), 0x4a7484aa.toInt(), 0x5cb0a9dc.toInt(), 0x76f988da.toInt(),
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351.toInt(), 0x14292967.toInt(),
    0x27b70a85.toInt(), 0x2e1b2138.toInt(), 0x4d2c6dfc.toInt(), 0x53380d13.toInt(),
    0x650a7354.toInt(), 0x766a0abb.toInt(), 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
    0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070.toInt(),
    0x19a4c116.toInt(), 0x1e376c08.toInt(), 0x2748774c.toInt(), 0x34b0bcb5.toInt(),
    0x391c0cb3.toInt(), 0x4ed8aa4a.toInt(), 0x5b9cca4f.toInt(), 0x682e6ff3.toInt(),
    0x748f82ee.toInt(), 0x78a5636f.toInt(), 0x84c87814.toInt(), 0x8cc70208.toInt(),
    0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
)

@OptIn(ExperimentalForeignApi::class)
private fun randomNonce(length: Int = NONCE_LENGTH): String {
    val bytes = ByteArray(length)
    bytes.usePinned { pinned ->
        val status = SecRandomCopyBytes(
            kSecRandomDefault,
            bytes.size.convert(),
            pinned.addressOf(0),
        )
        if (status != errSecSuccess) {
            error("Unable to generate secure Apple Sign-In nonce")
        }
    }
    return bytes.joinToString(separator = "") { byte ->
        val index = (byte.toInt() and 0xff) % NONCE_CHARSET.length
        NONCE_CHARSET[index].toString()
    }
}

private fun sha256Hex(input: String): String {
    val message = input.encodeToByteArray()
    val bitLength = message.size.toLong() * 8L
    val paddedLength = ((message.size + 9 + 63) / 64) * 64
    val padded = ByteArray(paddedLength)
    message.copyInto(padded)
    padded[message.size] = 0x80.toByte()
    for (i in 0 until 8) {
        padded[paddedLength - 1 - i] = (bitLength ushr (i * 8)).toByte()
    }

    var h0 = 0x6a09e667.toInt()
    var h1 = 0xbb67ae85.toInt()
    var h2 = 0x3c6ef372.toInt()
    var h3 = 0xa54ff53a.toInt()
    var h4 = 0x510e527f.toInt()
    var h5 = 0x9b05688c.toInt()
    var h6 = 0x1f83d9ab.toInt()
    var h7 = 0x5be0cd19.toInt()

    val w = IntArray(64)
    for (chunkStart in padded.indices step 64) {
        for (i in 0 until 16) {
            val j = chunkStart + i * 4
            w[i] = ((padded[j].toInt() and 0xff) shl 24) or
                ((padded[j + 1].toInt() and 0xff) shl 16) or
                ((padded[j + 2].toInt() and 0xff) shl 8) or
                (padded[j + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = rotateRight(w[i - 15], 7) xor rotateRight(w[i - 15], 18) xor (w[i - 15] ushr 3)
            val s1 = rotateRight(w[i - 2], 17) xor rotateRight(w[i - 2], 19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7

        for (i in 0 until 64) {
            val s1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + s1 + ch + SHA256_K[i] + w[i]
            val s0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = s0 + maj

            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h
    }

    val hexChars = "0123456789abcdef"
    val builder = StringBuilder(64)
    intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).forEach { word ->
        for (shift in 28 downTo 0 step 4) {
            builder.append(hexChars[(word ushr shift) and 0x0f])
        }
    }
    return builder.toString()
}

private fun rotateRight(value: Int, bits: Int): Int {
    return (value ushr bits) or (value shl (32 - bits))
}

@Suppress("CAST_NEVER_SUCCEEDS")
actual suspend fun signInWithApple(): SocialAuthResult? = suspendCancellableCoroutine { cont ->
    val rawNonce = randomNonce()
    val request = ASAuthorizationAppleIDProvider().createRequest().apply {
        requestedScopes = listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail)
        nonce = sha256Hex(rawNonce)
    }

    val controller = ASAuthorizationController(authorizationRequests = listOf(request))

    // Presentation context provider — required for iPad to anchor the sign-in sheet
    val presentationProvider = object : NSObject(), ASAuthorizationControllerPresentationContextProvidingProtocol {
        override fun presentationAnchorForAuthorizationController(
            controller: ASAuthorizationController,
        ): UIWindow {
            val scenes = UIApplication.sharedApplication.connectedScenes
            val windowScene = scenes.firstOrNull() as? platform.UIKit.UIWindowScene
            return windowScene?.windows?.firstOrNull() as? UIWindow
                ?: UIApplication.sharedApplication.windows.firstOrNull() as? UIWindow
                ?: UIWindow()
        }
    }

    // Holder is captured by the delegate's resume callbacks so we can release
    // the strong reference (delegate + presenter + controller) once the flow
    // completes — preventing them from leaking across multiple sign-in attempts.
    var holder: Any? = null

    val delegate = object : NSObject(), ASAuthorizationControllerDelegateProtocol {
        override fun authorizationController(
            controller: ASAuthorizationController,
            didCompleteWithAuthorization: ASAuthorization,
        ) {
            holder?.let { pendingAppleAuthRequests.remove(it) }
            holder = null

            val credential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
            if (credential == null) {
                if (cont.isActive) cont.resume(null)
                return
            }

            val tokenData = credential.identityToken
            val identityToken = tokenData?.let {
                NSString.create(data = it, encoding = NSUTF8StringEncoding) as? String
            }

            if (identityToken == null) {
                if (cont.isActive) cont.resume(null)
                return
            }

            val fullName = credential.fullName?.let { name ->
                listOfNotNull(
                    name.givenName,
                    name.familyName,
                ).joinToString(" ").ifBlank { null }
            }

            val email = credential.email

            if (cont.isActive) {
                cont.resume(
                    SocialAuthResult(
                        identityToken = identityToken,
                        fullName = fullName,
                        email = email,
                        nonce = rawNonce,
                    )
                )
            }
        }

        override fun authorizationController(
            controller: ASAuthorizationController,
            didCompleteWithError: NSError,
        ) {
            holder?.let { pendingAppleAuthRequests.remove(it) }
            holder = null
            if (!cont.isActive) return
            if (didCompleteWithError.code == ASAuthorizationErrorCanceled.toLong()) {
                cont.resume(null)
            } else {
                cont.cancel(
                    IllegalStateException(
                        "Apple Sign-In failed: ${didCompleteWithError.localizedDescription}",
                    ),
                )
            }
        }
    }

    controller.delegate = delegate
    controller.presentationContextProvider = presentationProvider

    // Pin all three objects until the delegate fires. ASAuthorizationController
    // holds delegate/presenter via weak refs, so without this pin the Kotlin
    // wrappers can be deallocated before the user dismisses the sheet.
    val pin = Triple(controller, delegate, presentationProvider)
    holder = pin
    pendingAppleAuthRequests.add(pin)

    cont.invokeOnCancellation {
        pendingAppleAuthRequests.remove(pin)
    }

    controller.performRequests()
}
