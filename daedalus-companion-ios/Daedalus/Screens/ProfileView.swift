import SwiftUI

struct ProfileView: View {
    @State private var auth = AuthStore.shared
    @State private var store = LocalStore.shared
    /// nil while loading or when the choice couldn't be read (offline).
    @State private var allowResearch: Bool?
    @State private var researchSaveFailed = false
    /// Sign-out confirmation: nil = hidden, true = everything is backed up, false = some isn't.
    @State private var signOutBackedUp: Bool?
    @State private var checkingBackup = false

    private var signedIn: Bool { auth.email != nil }

    var body: some View {
        ScreenScroll {
            Text(L("profile_title")).font(DS.title).kerning(-0.6).foregroundStyle(DS.ink)

            // Account
            SageCard(padding: .card()) {
                ListRow(
                    title: auth.email ?? L("account_guest"),
                    subtitle: L(signedIn ? "account_via_email" : "account_guest_sub"),
                    icon: "person.crop.circle"
                ) {
                    if signedIn {
                        // Back up first, then confirm: signing out clears this phone's health data.
                        Button(L("account_sign_out")) {
                            checkingBackup = true
                            Task {
                                signOutBackedUp = await CloudBackup.shared.isFullyBackedUp()
                                checkingBackup = false
                            }
                        }
                        .font(DS.font(15, .semibold)).foregroundStyle(DS.sageDeep)
                        .disabled(checkingBackup)
                        .frame(minHeight: 44)
                    } else {
                        // Guest data stays on the phone; the person chooses what to do with it after signing in.
                        Button(L("account_sign_in")) { Task { await auth.signOut() } }
                            .font(DS.font(15, .semibold)).foregroundStyle(DS.sageDeep)
                            .frame(minHeight: 44)
                    }
                }
            }

            // Research opt-out (signed-in accounts only)
            if signedIn {
                SageCard(padding: .card()) {
                    ListRow(
                        title: L("data_research_title"),
                        subtitle: L(researchSaveFailed ? "data_research_failed" : allowResearch == nil ? "data_research_unavailable" : "data_research_profile_sub"),
                        icon: "flask",
                        showDivider: true
                    ) {
                        SageToggle(label: L("data_research_title"), isOn: Binding(get: { allowResearch == true }, set: { allow in
                            let before = allowResearch
                            allowResearch = allow; researchSaveFailed = false
                            Task {
                                if !(await auth.setAnalysisOptOut(!allow)) { allowResearch = before; researchSaveFailed = true }
                            }
                        }))
                        .disabled(allowResearch == nil)
                    }
                    Text(L("data_promise_short")).font(DS.caption).foregroundStyle(DS.muted).padding(.leading, 36).padding(.vertical, 12)
                }
                .task(id: auth.email) { allowResearch = await auth.analysisOptOut().map { !$0 } }
            }

            // Language: iOS keeps a per-app language in Settings.
            SageCard(padding: .card()) {
                ListRow(title: L("language"), subtitle: Locale.current.localizedString(forLanguageCode: Locale.current.language.languageCode?.identifier ?? "en")?.capitalized,
                        icon: "globe", action: { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } })
            }

            // Devices
            SageCard(padding: .card()) {
                Text(L("devices")).font(DS.section).foregroundStyle(DS.ink).padding(.top, 14)
                ListRow(title: L("device_watch"), subtitle: L(WatchLink.shared.isReady ? "device_connected" : "watch_sub_pair"), icon: "applewatch")
            }

            SageCard(padding: .card()) {
                NavigationLink { MedScheduleView() } label: {
                    ListRow(title: L("schedule_title"),
                            subtitle: store.schedule.isEmpty ? L("schedule_not_set") : L("schedule_count", store.schedule.count),
                            icon: "pills", showDivider: true) { chevron }
                }
                NavigationLink { SessionsView() } label: {
                    ListRow(title: L("profile_sessions"), subtitle: L("profile_sessions_count", LocalStore.sessionFiles().count),
                            icon: "folder", showDivider: true) { chevron }
                }
                NavigationLink { DataView() } label: {
                    ListRow(title: L("data_title"), subtitle: L("data_row_sub"), icon: "checkmark.shield") { chevron }
                }
            }
            .buttonStyle(.plain)

            Text(L("disclaimer") + "\n" + L("version", Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? ""))
                .font(DS.caption).foregroundStyle(DS.muted).multilineTextAlignment(.center).frame(maxWidth: .infinity)
        }
        .toolbar(.hidden, for: .navigationBar)
        .alert(L("account_sign_out_title"), isPresented: Binding(get: { signOutBackedUp != nil }, set: { if !$0 { signOutBackedUp = nil } })) {
            Button(L("account_sign_out"), role: signOutBackedUp == true ? nil : .destructive) {
                Task { await CloudBackup.shared.signOutAndClear(force: true) }
            }
            Button(L("cancel"), role: .cancel) {}
        } message: {
            Text(L(signOutBackedUp == true ? "account_sign_out_backed_up" : "account_sign_out_not_backed_up"))
        }
    }

    private var chevron: some View {
        Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(DS.navInactive)
    }
}

/// Profile → Recorded sessions: CSV files from the Apple Watch app, shareable.
struct SessionsView: View {
    @State private var files = LocalStore.sessionFiles()

    var body: some View {
        ScreenScroll {
            SageCard(padding: .card()) {
                if files.isEmpty {
                    Text(L("sessions_empty")).font(DS.font(14)).foregroundStyle(DS.muted).padding(.vertical, 16)
                }
                let sorted = files.sorted { $0.lastPathComponent > $1.lastPathComponent }
                ForEach(Array(sorted.enumerated()), id: \.element) { i, file in
                    let info = SessionFileName(file.deletingPathExtension().lastPathComponent)
                    let size = Int64((try? file.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
                    ListRow(title: L(CueMode.byId(info.mode)?.labelKey ?? "mode_other"),
                            subtitle: "\(info.startedAt.map(Fmt.dayTime) ?? file.lastPathComponent) · \(Fmt.fileSize(size))",
                            icon: "doc.text", showDivider: i < sorted.count - 1) {
                        ShareLink(item: file) { Image(systemName: "square.and.arrow.up").foregroundStyle(DS.sageDeep).frame(width: 44, height: 44) }
                            .accessibilityLabel(L("sessions_download_cd"))
                    }
                }
            }
        }
        .navigationTitle(L("profile_sessions"))
        .onAppear { files = LocalStore.sessionFiles() }
    }
}
