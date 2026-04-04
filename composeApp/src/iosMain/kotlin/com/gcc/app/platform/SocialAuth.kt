package com.gcc.app.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AuthenticationServices.*
import platform.Foundation.*
import platform.darwin.NSObject
import kotlin.coroutines.resume

actual fun isAppleSignInAvailable(): Boolean = true

actual suspend fun signInWithApple(): SocialAuthResult? = suspendCancellableCoroutine { cont ->
    val request = ASAuthorizationAppleIDProvider().createRequest().apply {
        requestedScopes = listOf(ASAuthorizationScopeFullName, ASAuthorizationScopeEmail)
    }

    val controller = ASAuthorizationController(authorizationRequests = listOf(request))

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
    controller.performRequests()
}
