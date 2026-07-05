import SwiftUI
import UserNotifications
import ComposeApp

#if canImport(GoogleSignIn)
import GoogleSignIn
#endif

@main
struct iosAppApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .onOpenURL { url in
                    // Google Sign-In gets first crack at custom-scheme URLs
                    // (com.googleusercontent.apps.* redirects). If GIDSignIn
                    // doesn't claim it, fall through to deep-link routing.
                    #if canImport(GoogleSignIn)
                    if GIDSignIn.sharedInstance.handle(url) {
                        return
                    }
                    #endif
                    AppNavigator.shared.handleDeepLink(uri: url.absoluteString)
                }
        }
    }
}

// ── Google Sign-In configuration ──────────────────────────────────────
//
// To finish wiring native Google Sign-In:
//   1. Create an iOS OAuth 2.0 Client ID in the Google Cloud Console for
//      bundle id `com.globalcommunitycleaners.worker` and copy:
//        - the iOS client ID (e.g. 1234567890-abc.apps.googleusercontent.com)
//        - the reversed client ID (e.g. com.googleusercontent.apps.1234567890-abc)
//   2. Replace `GIDClientID` in Info.plist with the iOS client ID.
//   3. Replace the placeholder CFBundleURLTypes scheme in Info.plist with the
//      reversed client ID.
//
// Until the real IDs are present, `GoogleSignInBridge.isConfigured` stays false
// and the LoginScreen Google button hides itself.

private let googleIosClientIdPlaceholder = "GOOGLE_IOS_CLIENT_ID_PLACEHOLDER"

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        UNUserNotificationCenter.current().delegate = self

        // Apple-native crash/hang diagnostics (MetricKit): payloads arrive on
        // the launch after an incident and are persisted for the in-app
        // diagnostics panel + Files-app retrieval.
        CrashDiagnosticsObserver.shared.start()

        // Native RealityKit AR surface for the environment scan. Registration
        // is the opt-in — if this line is removed (or the factory throws),
        // Kotlin falls back to its SceneKit render path automatically.
        GccArNative.register()

        // Bridge UIApplication push methods to Kotlin (K/N can't call these directly)
        PushNotificationManager.companion.registerCallback = {
            DispatchQueue.main.async {
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
        PushNotificationManager.companion.unregisterCallback = {
            DispatchQueue.main.async {
                UIApplication.shared.unregisterForRemoteNotifications()
            }
        }

        configureGoogleSignIn()

        return true
    }

    // MARK: - Push Notification Token

    func application(
        _ application: UIApplication,
        didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
    ) {
        PushNotificationManager.companion.didRegisterForRemoteNotifications(deviceTokenData: deviceToken)
    }

    func application(
        _ application: UIApplication,
        didFailToRegisterForRemoteNotificationsWithError error: Error
    ) {
        PushNotificationManager.companion.didFailToRegisterForRemoteNotifications()
    }

    // MARK: - Universal Links

    func application(
        _ application: UIApplication,
        continue userActivity: NSUserActivity,
        restorationHandler: @escaping ([UIUserActivityRestoring]?) -> Void
    ) -> Bool {
        guard userActivity.activityType == NSUserActivityTypeBrowsingWeb,
              let url = userActivity.webpageURL else {
            return false
        }
        AppNavigator.shared.handleDeepLink(uri: url.absoluteString)
        return true
    }

    // MARK: - Foreground Notification Display

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        // Show notification banner even when app is in foreground
        completionHandler([.banner, .sound, .badge])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        // Handle notification tap — extract deep link URL from payload if present
        let userInfo = response.notification.request.content.userInfo
        if let deepLink = userInfo["deep_link"] as? String {
            AppNavigator.shared.handleDeepLink(uri: deepLink)
        }
        completionHandler()
    }

    // MARK: - Google Sign-In bridge

    private func configureGoogleSignIn() {
        #if canImport(GoogleSignIn)
        let googleIosClientId = Bundle.main.object(forInfoDictionaryKey: "GIDClientID") as? String
        guard let googleIosClientId,
              !googleIosClientId.isEmpty,
              googleIosClientId != googleIosClientIdPlaceholder else {
            // Bridge stays unconfigured — Kotlin reports
            // isGoogleSignInAvailable() == false and the Google button hides.
            return
        }

        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: googleIosClientId)
        GoogleSignInBridge.shared.isConfigured = true
        GoogleSignInBridge.shared.signInCallback = { onResult in
            DispatchQueue.main.async {
                guard let presenter = topViewController() else {
                    _ = onResult(nil, nil, nil)
                    return
                }
                GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
                    if let error = error {
                        // Silent on user-cancel; log other errors.
                        let nsErr = error as NSError
                        if nsErr.code != GIDSignInError.canceled.rawValue {
                            print("Google Sign-In error: \(error.localizedDescription)")
                        }
                        _ = onResult(nil, nil, nil)
                        return
                    }
                    let user = result?.user
                    let idToken = user?.idToken?.tokenString
                    let profile = user?.profile
                    let fullName = profile?.name
                    let email = profile?.email
                    _ = onResult(idToken, fullName, email)
                }
            }
        }
        #endif
    }
}

// Walk the key window's view controller stack so we can present GIDSignIn
// from whatever's currently on screen (Compose root, modal sheet, etc.).
private func topViewController(base: UIViewController? = nil) -> UIViewController? {
    let resolvedBase = base ?? UIApplication.shared
        .connectedScenes
        .compactMap { ($0 as? UIWindowScene)?.keyWindow }
        .first?
        .rootViewController

    if let nav = resolvedBase as? UINavigationController {
        return topViewController(base: nav.visibleViewController)
    }
    if let tab = resolvedBase as? UITabBarController {
        return topViewController(base: tab.selectedViewController)
    }
    if let presented = resolvedBase?.presentedViewController {
        return topViewController(base: presented)
    }
    return resolvedBase
}
