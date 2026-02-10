import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        let isDemo = ProcessInfo.processInfo.environment["GCC_DEMO"] == "true"
        if isDemo {
            return MainViewControllerKt.MainViewControllerDemo()
        } else {
            return MainViewControllerKt.MainViewController()
        }
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            .ignoresSafeArea(.all)
    }
}
