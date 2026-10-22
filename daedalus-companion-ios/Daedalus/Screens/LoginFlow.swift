import SwiftUI

/// Sign-in, Wealthsimple-style: welcome (Sign in / Sign up) → email and password on one screen,
/// plus create account and password reset.
struct LoginFlow: View {
    private enum Step { case welcome, signIn, create, checkEmail }
    private enum Field { case email, password }

    @State private var auth = AuthStore.shared
    @State private var step: Step = .welcome
    @State private var email = ""
    @State private var password = ""
    @State private var busy = false
    @State private var error: AuthFailure?
    @State private var checkEmailKey = "login_check_reset"
    // Research use is off unless the person chooses it (privacy by default; changeable in Profile).
    @State private var allowResearch = false
    @FocusState private var focus: Field?

    private var looksLikeEmail: Bool { email.range(of: #"^[^@\s]+@[^@\s]+\.[^@\s]+$"#, options: .regularExpression) != nil }

    var body: some View {
        if step == .welcome {
            WelcomeView(
                onSignIn: { go(.signIn) },
                onSignUp: { go(.create) },
                onGuest: { auth.continueAsGuest() }
            )
        } else {
            AuthFrame(onBack: { go(.welcome) }, notConfigured: !auth.isConfigured, busy: busy) {
                content
            }
        }
    }

    @ViewBuilder private var content: some View {
        switch step {
        case .welcome:
            EmptyView()
        case .signIn:
            EmailField(text: $email) { focus = .password }
                .focused($focus, equals: .email)
            PasswordField(label: L("login_password"), text: $password) { signIn() }
                .focused($focus, equals: .password)
            ErrorText(error ?? auth.linkError)
            // Sign-in doesn't say whether an email has an account (that would reveal who uses a
            // Parkinson's app), so after a failed attempt offer both ways forward.
            if error == .invalidCredentials { NoAccountHint { go(.create) } }
            Button(L("login_forgot")) {
                guard looksLikeEmail else { error = .invalidEmail; return }
                run { try await auth.sendPasswordReset(email: email); checkEmailKey = "login_check_reset"; go(.checkEmail) }
            }
            .font(DS.font(15, .semibold)).foregroundStyle(DS.sageDeep).disabled(busy)
            .frame(minHeight: 44)
            Spacer(minLength: 24)
            Button(L("login_sign_in")) { signIn() }
                .buttonStyle(PillButtonStyle()).disabled(!looksLikeEmail || password.isEmpty || busy)

        case .create:
            Headline(title: L("login_create_title"), subtitle: L("login_create_subtitle"))
            EmailField(text: $email) {}
            PasswordField(label: L("login_new_password"), text: $password, rule: L("login_password_rule")) {}
            DataPromise(allowResearch: $allowResearch)
            ErrorText(error)
            Spacer(minLength: 24)
            Button(L("login_create")) {
                run {
                    if try await auth.signUp(email: email, password: password, analysisOptOut: !allowResearch) == .confirmEmail {
                        checkEmailKey = "login_check_confirm"; go(.checkEmail)
                    }
                }
            }
            .buttonStyle(PillButtonStyle()).disabled(!looksLikeEmail || password.count < 8 || busy)

        case .checkEmail:
            Image(systemName: "envelope.badge").font(.system(size: 36)).foregroundStyle(DS.sage)
            Headline(title: L("login_check_title"))
            Text(L(checkEmailKey, email)).font(DS.font(16)).foregroundStyle(DS.muted)
            Spacer(minLength: 24)
            Button(L("login_back_to_login")) { go(.signIn) }.buttonStyle(PillButtonStyle())
        }
    }

    private func go(_ to: Step) { withAnimation(.easeInOut(duration: 0.2)) { step = to }; error = nil; password = "" }

    private func signIn() {
        guard looksLikeEmail, !password.isEmpty else { return }
        run { try await auth.signIn(email: email, password: password) }
    }

    private func run(_ block: @escaping () async throws -> Void) {
        busy = true; error = nil
        Task {
            do { try await block() } catch { self.error = error as? AuthFailure ?? .unknown }
            busy = false
        }
    }
}

/// After a failed sign-in: check the email, or create an account with it.
private struct NoAccountHint: View {
    let onCreate: () -> Void
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(L("login_no_account_hint")).font(DS.font(14)).foregroundStyle(DS.ink).fixedSize(horizontal: false, vertical: true)
            Button(L("login_create_account_button"), action: onCreate)
                .buttonStyle(OutlinePillButtonStyle(border: DS.sageDeep, height: 48))
        }
        .padding(16)
        .background(DS.sageTint, in: RoundedRectangle(cornerRadius: 16))
    }
}

/// First screen when signed out: the app name, with Sign up and Sign in at the bottom.
private struct WelcomeView: View {
    let onSignIn: () -> Void
    let onSignUp: () -> Void
    let onGuest: () -> Void

    var body: some View {
        VStack(spacing: 12) {
            Spacer()
            Text(L("app_name")).font(DS.font(48, .semibold)).kerning(-1).foregroundStyle(.white)
            Spacer()
            Button(L("login_sign_up"), action: onSignUp).buttonStyle(PillButtonStyle(dark: false))
            Button(L("login_sign_in_button"), action: onSignIn)
                .buttonStyle(OutlinePillButtonStyle(border: DS.onSageDeep, foreground: .white, background: .clear, height: 56))
            Button(L("login_guest"), action: onGuest)
                .font(DS.font(13)).foregroundStyle(DS.onSageDeep).padding(.top, 8)
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 16)
        .background(DS.sageDeep.ignoresSafeArea())
        .statusBarHidden() // dark status-bar text would be unreadable on the dark sage
    }
}

/// Opened from a password-reset email: pick a new password.
struct NewPasswordView: View {
    @State private var password = ""
    @State private var busy = false
    @State private var error: AuthFailure?

    var body: some View {
        AuthFrame(onBack: nil, notConfigured: false, busy: busy) {
            Headline(title: L("login_reset_title"), subtitle: L("login_reset_subtitle"))
            PasswordField(label: L("login_new_password"), text: $password, rule: L("login_password_rule")) {}
            ErrorText(error)
            Spacer(minLength: 24)
            Button(L("login_reset_save")) {
                busy = true
                Task {
                    do { try await AuthStore.shared.updatePassword(password) } catch { self.error = error as? AuthFailure ?? .unknown }
                    busy = false
                }
            }
            .buttonStyle(PillButtonStyle()).disabled(password.count < 8 || busy)
        }
    }
}

// MARK: - Shared building blocks (sign-in and setup)

/// Back arrow (or wordmark), a thin progress or busy bar, then content with actions at the bottom.
struct AuthFrame<Content: View>: View {
    let onBack: (() -> Void)?
    var notConfigured = false
    var busy = false
    /// Setup progress (0–1); nil on the sign-in screens.
    var progress: Double? = nil
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                if let onBack {
                    Button(action: onBack) {
                        Image(systemName: "arrow.left").font(.system(size: 20, weight: .medium)).foregroundStyle(DS.ink).frame(width: 44, height: 44)
                    }
                    .accessibilityLabel(L("back"))
                    .padding(.leading, -10)
                } else {
                    Text(L("app_name")).font(DS.font(18, .bold)).foregroundStyle(DS.sageDeep)
                }
                Spacer()
            }
            .frame(height: 52)
            if busy {
                ProgressView().progressViewStyle(.linear).tint(DS.sage).frame(height: 3)
            } else if let progress {
                ProgressView(value: progress).progressViewStyle(.linear).tint(DS.sageDeep).frame(height: 4)
            } else {
                Color.clear.frame(height: 3)
            }
            if notConfigured {
                Text(L("login_not_configured"))
                    .font(DS.font(12)).foregroundStyle(DS.sageDeep)
                    .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                    .background(DS.sageTint, in: RoundedRectangle(cornerRadius: 12)).padding(.top, 8)
            }
            // Scrolls on small screens; otherwise the Spacer pushes the actions to the bottom.
            GeometryReader { geo in
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) { content }
                        .padding(.vertical, 24)
                        .frame(minHeight: geo.size.height, alignment: .top)
                }
                .scrollDismissesKeyboard(.interactively)
            }
        }
        .padding(.horizontal, 24)
        .background(DS.ground.ignoresSafeArea())
    }
}

struct Headline: View {
    let title: String
    var subtitle: String? = nil
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(DS.font(32, .semibold)).kerning(-0.6).foregroundStyle(DS.ink).fixedSize(horizontal: false, vertical: true)
            if let subtitle { Text(subtitle).font(DS.font(16)).foregroundStyle(DS.muted).fixedSize(horizontal: false, vertical: true) }
        }
    }
}

struct FieldBox<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        content
            .font(DS.font(17))
            .foregroundStyle(DS.ink)
            .tint(DS.sageDeep)
            .padding(.horizontal, 16).frame(minHeight: 56)
            .background(DS.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(DS.outline, lineWidth: 1))
    }
}

struct EmailField: View {
    @Binding var text: String
    var onSubmit: () -> Void
    var body: some View {
        FieldBox {
            TextField(L("login_email"), text: $text)
                .textContentType(.username).keyboardType(.emailAddress)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .submitLabel(.next).onSubmit(onSubmit)
                .onChange(of: text) { _, new in if new.contains(" ") { text = new.trimmingCharacters(in: .whitespaces) } }
        }
    }
}

struct PasswordField: View {
    let label: String
    @Binding var text: String
    var rule: String? = nil
    var onSubmit: () -> Void
    @State private var show = false
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            FieldBox {
                HStack {
                    Group {
                        if show { TextField(label, text: $text) } else { SecureField(label, text: $text) }
                    }
                    .textContentType(rule == nil ? .password : .newPassword)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                    .submitLabel(.done).onSubmit(onSubmit)
                    Button { show.toggle() } label: {
                        Image(systemName: show ? "eye.slash" : "eye").foregroundStyle(DS.muted)
                    }
                    .accessibilityLabel(L(show ? "login_hide_password" : "login_show_password"))
                }
            }
            if let rule { Text(rule).font(DS.font(12)).foregroundStyle(DS.muted).padding(.leading, 16) }
        }
    }
}

struct ErrorText: View {
    let error: AuthFailure?
    init(_ error: AuthFailure?) { self.error = error }
    var body: some View {
        if let error { Text(L(error.messageKey)).font(DS.font(14)).foregroundStyle(DS.alert) }
    }
}

/// How we treat health data, and the research opt-out, shown before an account is created.
struct DataPromise: View {
    @Binding var allowResearch: Bool
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L("data_promise_title")).font(DS.font(16, .semibold)).foregroundStyle(DS.ink)
            line("lock", "data_promise_secure")
            line("dollarsign.circle", "data_promise_not_sold")
            line("checkmark.shield", "data_promise_owned")
            Divider().overlay(DS.sageLine.opacity(0.5))
            HStack(alignment: .center, spacing: 12) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(L("data_research_title")).font(DS.font(15, .semibold)).foregroundStyle(DS.ink)
                    Text(L("data_research_signup_sub")).font(DS.font(13)).foregroundStyle(DS.muted).fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 0)
                SageToggle(label: L("data_research_title"), isOn: $allowResearch)
            }
        }
        .padding(18)
        .background(DS.sageTint, in: RoundedRectangle(cornerRadius: 20))
    }

    private func line(_ icon: String, _ key: String) -> some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: icon).font(.system(size: 15)).foregroundStyle(DS.sageDeep).frame(width: 20)
            Text(L(key)).font(DS.font(14)).foregroundStyle(DS.ink).fixedSize(horizontal: false, vertical: true)
        }
    }
}
