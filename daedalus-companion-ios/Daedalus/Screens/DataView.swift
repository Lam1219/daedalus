import SwiftUI

/// Profile → Your data: account backup, and the three ways to delete data.
struct DataView: View {
    private enum Action: Identifiable { case freeUp, uploaded, everything, account; var id: Self { self } }

    @State private var auth = AuthStore.shared
    @State private var store = LocalStore.shared
    @State private var files = LocalStore.sessionFiles()
    @State private var busy = false
    @State private var messageKey: String?
    @State private var confirm: Action?

    private var signedIn: Bool { auth.email != nil }
    private var filesSize: Int64 {
        files.reduce(0) { $0 + Int64((try? $1.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0) }
    }

    var body: some View {
        ScreenScroll {
            Text(L("data_promise_short")).font(DS.font(14)).foregroundStyle(DS.muted)

            SageCard(padding: .card()) {
                if signedIn {
                    ListRow(title: L("data_backup_title"), subtitle: L(store.cloudBackup ? "data_backup_on" : "data_backup_off"),
                            icon: store.cloudBackup ? "icloud.and.arrow.up" : "icloud.slash") {
                        SageToggle(label: L("data_backup_title"), isOn: Binding(get: { store.cloudBackup }, set: { on in
                            store.cloudBackup = on
                            if on { run { await CloudBackup.shared.sync() ? "data_backup_done" : "data_backup_failed" } }
                        }))
                        .disabled(busy)
                    }
                } else {
                    ListRow(title: L("data_guest_title"), subtitle: L("data_guest_sub"), icon: "icloud.slash")
                }
            }

            Text(L("data_delete_section")).font(DS.section).foregroundStyle(DS.ink).padding(.top, 4)
            SageCard(padding: .card()) {
                if signedIn {
                    ListRow(
                        title: L("data_free_up_title"),
                        subtitle: !store.cloudBackup ? L("data_free_up_needs_backup")
                            : files.isEmpty ? L("data_free_up_empty")
                            : L("data_free_up_sub", files.count, Fmt.fileSize(filesSize)),
                        icon: "sparkles", showDivider: true,
                        action: store.cloudBackup && !files.isEmpty && !busy ? { confirm = .freeUp } : nil
                    )
                    ListRow(title: L("data_delete_uploaded_title"), subtitle: L("data_delete_uploaded_sub"), icon: "icloud.slash",
                            showDivider: true, action: busy ? nil : { confirm = .uploaded })
                }
                ListRow(title: L("data_delete_all_title"), subtitle: L(signedIn ? "data_delete_all_sub" : "data_delete_all_guest_sub"),
                        icon: "trash", showDivider: signedIn, action: busy ? nil : { confirm = .everything })
                if signedIn {
                    ListRow(title: L("data_delete_account_title"), subtitle: L("data_delete_account_sub"),
                            icon: "person.crop.circle.badge.xmark", action: busy ? nil : { confirm = .account })
                }
            }

            if busy { ProgressView().progressViewStyle(.linear).tint(DS.sage) }
            if let messageKey { Text(L(messageKey)).font(DS.font(14)).foregroundStyle(DS.sageDeep) }
        }
        .navigationTitle(L("data_title"))
        .alert(item: $confirm) { action in
            let (title, body, button): (String, String, String) = switch action {
            case .freeUp: ("data_free_up_title", "data_free_up_confirm", "data_free_up_button")
            case .uploaded: ("data_delete_uploaded_title", "data_delete_uploaded_confirm", "data_delete_button")
            case .everything: ("data_delete_all_title", signedIn ? "data_delete_all_confirm" : "data_delete_all_guest_confirm", "data_delete_button")
            case .account: ("data_delete_account_title", "data_delete_account_confirm", "data_delete_account_button")
            }
            return Alert(
                title: Text(L(title)), message: Text(L(body)),
                primaryButton: .destructive(Text(L(button))) { perform(action) },
                secondaryButton: .cancel(Text(L("cancel")))
            )
        }
    }

    private func perform(_ action: Action) {
        run {
            switch action {
            case .freeUp:
                await CloudBackup.shared.freeUpPhoneSpace() == .done ? "data_free_up_done" : "data_free_up_not_backed_up"
            case .uploaded:
                await CloudBackup.shared.deleteUploaded() ? "data_delete_uploaded_done" : "data_delete_failed"
            case .everything:
                // Signed-in accounts are signed out afterwards, which leaves this screen.
                await CloudBackup.shared.deleteEverything() ? "data_delete_all_done" : "data_delete_failed"
            case .account:
                // Signs out afterwards, which leaves this screen.
                await CloudBackup.shared.deleteAccount() ? "data_delete_all_done" : "data_delete_failed"
            }
        }
    }

    private func run(_ block: @escaping () async -> String) {
        busy = true; messageKey = nil
        Task {
            messageKey = await block()
            files = LocalStore.sessionFiles()
            busy = false
        }
    }
}
