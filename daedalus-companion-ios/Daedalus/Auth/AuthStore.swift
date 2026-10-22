import Foundation
import Observation
import Supabase

enum AuthState: Equatable {
    /// Restoring a saved session.
    case loading
    case signedOut
    /// Using the app on this phone only, without an account.
    case guest
    case signedIn(email: String?)
    /// Opened from a password-reset email: choose a new password before continuing.
    case settingNewPassword
}

/// Why a sign-in step failed, mapped to a message on the login screens.
enum AuthFailure: Error {
    case notConfigured, invalidCredentials, emailNotConfirmed, userExists, weakPassword, invalidEmail, linkExpired, network, unknown

    var messageKey: String {
        switch self {
        case .notConfigured: "login_err_not_configured"
        case .invalidCredentials: "login_err_credentials"
        case .emailNotConfirmed: "login_err_not_confirmed"
        case .userExists: "login_err_exists"
        case .weakPassword: "login_err_weak"
        case .invalidEmail: "login_err_email"
        case .linkExpired: "login_err_link_expired"
        case .network: "login_err_network"
        case .unknown: "login_err_unknown"
        }
    }
}

enum SignUpResult { case signedIn, confirmEmail }

/// Supabase account for the iPhone app: email + password sign-in, the research consent, and the
/// calls CloudBackup uses. The Supabase library keeps the session in the Keychain.
@MainActor
@Observable
final class AuthStore {
    static let shared = AuthStore()
    static let redirect = URL(string: "daedalus://auth-callback")!

    private(set) var state: AuthState = .loading
    /// Error from the last email link (expired confirmation / reset), shown on the sign-in screens.
    var linkError: AuthFailure?

    @ObservationIgnored let client: SupabaseClient?
    var isConfigured: Bool { client != nil }

    private init() {
        // From the generated SupabaseConfig (tools/supabase_config.sh).
        if let url = URL(string: SupabaseConfig.url), url.host != nil, !SupabaseConfig.anonKey.isEmpty {
            let key = SupabaseConfig.anonKey
            client = SupabaseClient(
                supabaseURL: url,
                supabaseKey: key,
                // PKCE: an email link only completes on the phone that started the sign-up or
                // reset (it holds the code verifier), so a link from anyone else can't sign in.
                options: SupabaseClientOptions(auth: .init(redirectToURL: Self.redirect, flowType: .pkce))
            )
        } else {
            client = nil
        }
        if let session = client?.auth.currentSession {
            state = .signedIn(email: session.user.email)
        } else {
            state = UserDefaults.standard.bool(forKey: "guest") ? .guest : .signedOut
        }
    }

    // MARK: Email

    func signIn(email: String, password: String) async throws {
        guard let client else { throw AuthFailure.notConfigured }
        do {
            let session = try await client.auth.signIn(email: email, password: password)
            signedIn(session.user.email)
        } catch { throw map(error) }
    }

    /// `analysisOptOut` is the sign-up screen's research choice; the server stores it in `consents`.
    func signUp(email: String, password: String, analysisOptOut: Bool) async throws -> SignUpResult {
        guard let client else { throw AuthFailure.notConfigured }
        do {
            let response = try await client.auth.signUp(
                email: email, password: password,
                data: ["analysis_opt_out": .bool(analysisOptOut)],
                redirectTo: Self.redirect
            )
            // With "Confirm email" on (the default) there is no session until the link is opened.
            if let session = response.session { signedIn(session.user.email); return .signedIn }
            setPendingLink(.signUp)
            return .confirmEmail
        } catch { throw map(error) }
    }

    func sendPasswordReset(email: String) async throws {
        guard let client else { throw AuthFailure.notConfigured }
        do {
            try await client.auth.resetPasswordForEmail(email, redirectTo: Self.redirect)
            setPendingLink(.recovery)
        } catch { throw map(error) }
    }

    func updatePassword(_ password: String) async throws {
        guard let client else { throw AuthFailure.notConfigured }
        do {
            let user = try await client.auth.update(user: UserAttributes(password: password))
            signedIn(user.email)
        } catch { throw map(error) }
    }

    /// daedalus://auth-callback?code=… from a confirmation or password-reset email this phone asked
    /// for (or ?error=… when the link expired). Any other link is ignored, and a signed-in account
    /// is never replaced by one.
    func handle(url: URL) async {
        guard let client, url.scheme == "daedalus", url.host == "auth-callback", let pending = pendingLink else { return }
        if case .signedIn = state, pending != .recovery { return }
        if Self.params(of: url)["error"] != nil { linkError = .linkExpired; return }
        do {
            let session = try await client.auth.session(from: url)
            linkError = nil
            setPendingLink(nil)
            if pending == .recovery {
                state = .settingNewPassword
            } else {
                signedIn(session.user.email)
            }
        } catch {
            linkError = .linkExpired
        }
    }

    // MARK: Email links this phone is waiting for

    enum PendingLink: String { case signUp, recovery }

    /// The email link this phone asked for in the last day (sign-up confirmation or password reset).
    private var pendingLink: PendingLink? {
        let d = UserDefaults.standard
        guard let at = d.object(forKey: "pendingLinkAt") as? Date, Date.now.timeIntervalSince(at) < 24 * 3600 else { return nil }
        return d.string(forKey: "pendingLink").flatMap(PendingLink.init(rawValue:))
    }

    private func setPendingLink(_ link: PendingLink?) {
        let d = UserDefaults.standard
        d.set(link?.rawValue, forKey: "pendingLink")
        d.set(link == nil ? nil : Date.now, forKey: "pendingLinkAt")
    }

    func signOut() async {
        try? await client?.auth.signOut()
        UserDefaults.standard.set(false, forKey: "guest")
        state = .signedOut
    }

    /// Uses the app on this phone only. Sign-in stays available from Profile.
    func continueAsGuest() {
        UserDefaults.standard.set(true, forKey: "guest")
        state = .guest
    }

    var email: String? { if case let .signedIn(email) = state { email } else { nil } }
    var userId: String? { client?.auth.currentUser?.id.uuidString.lowercased() }

    // MARK: Consent

    /// Whether the user opted out of research analysis, or nil if it can't be read (offline).
    func analysisOptOut() async -> Bool? {
        struct Row: Decodable { let analysis_opt_out: Bool }
        guard let client else { return nil }
        let row: Row? = try? await client.from("consents").select("analysis_opt_out").single().execute().value
        return row?.analysis_opt_out
    }

    func setAnalysisOptOut(_ optOut: Bool) async -> Bool {
        guard let client else { return false }
        do {
            try await client.rpc("set_analysis_opt_out", params: ["opt_out": optOut]).execute()
            return true
        } catch { return false }
    }

    // MARK: Personal profile

    enum RemoteProfile { case found(PersonalProfile), missing, unavailable }

    func fetchProfile() async -> RemoteProfile {
        guard let client else { return .unavailable }
        do {
            let rows: [PersonalProfile] = try await client.from("profiles").select().limit(1).execute().value
            return rows.first.map(RemoteProfile.found) ?? .missing
        } catch { return .unavailable }
    }

    func saveProfile(_ profile: PersonalProfile) async -> Bool {
        guard let client else { return false }
        do { try await client.from("profiles").upsert(profile).execute(); return true } catch { return false }
    }

    // MARK: Helpers

    private func signedIn(_ email: String?) {
        UserDefaults.standard.set(false, forKey: "guest")
        state = .signedIn(email: email)
    }

    private static func params(of url: URL) -> [String: String] {
        let raw = url.fragment ?? URLComponents(url: url, resolvingAgainstBaseURL: false)?.query ?? ""
        var out: [String: String] = [:]
        for pair in raw.split(separator: "&") {
            let kv = pair.split(separator: "=", maxSplits: 1).map(String.init)
            if kv.count == 2 { out[kv[0]] = kv[1].removingPercentEncoding ?? kv[1] }
        }
        return out
    }

    private func map(_ error: Error) -> AuthFailure {
        if error is URLError { return .network }
        let text = String(describing: error).lowercased()
        if text.contains("invalid_credentials") || text.contains("invalid login credentials") { return .invalidCredentials }
        if text.contains("email_not_confirmed") || text.contains("not confirmed") { return .emailNotConfirmed }
        if text.contains("user_already_exists") || text.contains("already registered") { return .userExists }
        if text.contains("weak_password") || text.contains("password should be") { return .weakPassword }
        if text.contains("validation_failed") || text.contains("invalid format") || text.contains("email address") { return .invalidEmail }
        print("Auth error: \(error)")
        return .unknown
    }
}
