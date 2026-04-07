package com.gcc.app.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.*
import platform.Foundation.*
import platform.UIKit.UIApplication
import platform.UIKit.UIWindow
import platform.darwin.NSObject
import kotlin.coroutines.resume

actual fun isAppleSignInAvailable(): Boolean = true

@Suppress("CAST_NEVER_SUCCEEDS")
actual suspend fun signInWithApple(): SocialAuthResult? = suspendCancellableCoroutine { cont ->
    val request = ASAuthorizationAppleIDProvider().createRequest().apply {
        requestedScopes = listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail)
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

    val delegate = object : NSObject(), ASAuthorizationControllerDelegateProtocol {
        override fun authorizationController(
            controller: ASAuthorizationController,
            didCompleteWithAuthorization: ASAuthorization,
        ) {
            val credential = didCompleteWithAuthorization.credential as? ASAuthorizationAppleIDCredential
            if (credential == null) {
                cont.resume(null)
                return
            }

            val tokenData = credential.identityToken
            val identityToken = tokenData?.let {
                NSString.create(data = it, encoding = NSUTF8StringEncoding) as? String
            }

            if (identityToken == null) {
                cont.resume(null)
                return
            }

            val fullName = credential.fullName?.let { name ->
                listOfNotNull(
                    name.givenName,
                    name.familyName,
                ).joinToString(" ").ifBlank { null }
            }

            val email = credential.email

            cont.resume(
                SocialAuthResult(
                    identityToken = identityToken,
                    fullName = fullName,
                    email = email,
                )
            )
        }

        override fun authorizationController(
            controller: ASAuthorizationController,
            didCompleteWithError: NSError,
        ) {
            cont.resume(null)
        }
    }

    controller.delegate = delegate
    controller.presentationContextProvider = presentationProvider
    controller.performRequests()
}
