// The iOS test app for scripts/dev/ios-e2e.sh: a login form, a second screen with a navigation
// bar (and its back button), a tab bar, and a URL scheme (testshop://cart) for deeplinks.
import SwiftUI

@main
struct TestShopApp: App {
    @State private var tab = "home"
    var body: some Scene {
        WindowGroup {
            TabView(selection: $tab) {
                LoginView().tabItem { Label("Home", systemImage: "house") }.tag("home")
                CartView().tabItem { Label("Cart", systemImage: "cart") }.tag("cart")
            }
            .onOpenURL { url in if url.host == "cart" { tab = "cart" } }
        }
    }
}

struct LoginView: View {
    @State private var email = ""
    @State private var password = ""
    @State private var remember = true
    @State private var loggedIn = false
    var body: some View {
        NavigationStack {
            Form {
                TextField("you@example.com", text: $email)
                    .accessibilityIdentifier("email").accessibilityLabel("E-mail")
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                SecureField("Password", text: $password).accessibilityIdentifier("password")
                Toggle("Remember me", isOn: $remember).accessibilityIdentifier("remember")
                Button("Log in") { loggedIn = true }.accessibilityIdentifier("login")
                    .disabled(email.isEmpty || password.isEmpty)
            }
            .navigationTitle("Log in")
            .navigationDestination(isPresented: $loggedIn) { WelcomeView(email: email) }
        }
    }
}

struct WelcomeView: View {
    let email: String
    var body: some View {
        VStack(spacing: 16) {
            Text("Welcome, \(email)").accessibilityIdentifier("welcome")
            Text("Blåbærsyltetøy").accessibilityIdentifier("unicode")
        }
        .navigationTitle("Welcome")
    }
}

struct CartView: View {
    var body: some View {
        NavigationStack {
            List { Text("Your cart is empty").accessibilityIdentifier("cart-empty") }
                .navigationTitle("Cart")
        }
    }
}
