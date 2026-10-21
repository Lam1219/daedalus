import Foundation
import Supabase

/// Backs up the phone's health data to the signed-in account in Supabase, and deletes it.
/// Same rules as the Android app:
/// - Backup: profile, medication schedule, dose logs, check-ins and watch session files. Runs at
///   start and after each change; whatever fails (offline) is retried next time.
///   Guests and people who turned backup off keep everything on the phone only.
/// - Deleting: free up space (phone copies of backed-up sessions), delete uploaded data
///   (account only, turns backup off), or delete everything (phone + account, then sign out).
@MainActor
final class CloudBackup {
    static let shared = CloudBackup()

    private let store = LocalStore.shared
    private let auth = AuthStore.shared
    private var running: Task<Bool, Never>?
    private let defaults = UserDefaults(suiteName: "daedalus_uploads") ?? .standard

    /// Starts a backup in the background (coalesces with one already running).
    func syncSoon() { Task { _ = await sync() } }

    /// Backs up everything not yet in the account. True when it's all there.
    @discardableResult
    func sync() async -> Bool {
        if let running { return await running.value }
        let task = Task { await syncNow() }
        running = task
        let result = await task.value
        running = nil
        return result
    }

    enum FreeUpResult { case done, notBackedUp }

    /// Deletes session files from this phone, only once every one of them is in the account.
    func freeUpPhoneSpace() async -> FreeUpResult {
        guard await sync(), let owner = auth.email else { return .notBackedUp }
        let uploaded = uploadedSet(owner, .sessions)
        for file in LocalStore.sessionFiles() where uploaded.contains(file.lastPathComponent) {
            try? FileManager.default.removeItem(at: file)
        }
        return .done
    }

    /// Deletes everything in the account (files, then rows) and turns backup off. Phone data stays.
    func deleteUploaded() async -> Bool {
        if let running { _ = await running.value }
        guard let client = auth.client, let owner = auth.email, let userId = auth.userId else { return false }
        do {
            // Session files first: Storage only lets each user delete their own folder.
            let bucket = client.storage.from("sessions")
            for _ in 0..<200 {
                let files = try await bucket.list(path: userId, options: SearchOptions(limit: 100))
                if files.isEmpty { break }
                _ = try await bucket.remove(paths: files.map { "\(userId)/\($0.name)" })
            }
            try await client.rpc("delete_my_data").execute()
        } catch {
            print("Delete uploaded data failed: \(error)")
            return false
        }
        // Nothing is in the account now; keep it that way until backup is turned back on.
        for kind in Kind.allCases { defaults.removeObject(forKey: "\(kind.rawValue):\(owner)") }
        store.cloudBackup = false
        store.profileUploadPending = true
        return true
    }

    /// Deletes the account's data and this phone's health data, then signs out (guests: phone only).
    func deleteEverything() async -> Bool {
        let signedIn = auth.email != nil
        if signedIn, !(await deleteUploaded()) { return false }
        for file in LocalStore.sessionFiles() { try? FileManager.default.removeItem(at: file) }
        store.clearHealthData()
        store.cloudBackup = true
        await MedReminders.reschedule()
        if signedIn { await auth.signOut() }
        return true
    }

    // MARK: Backup

    private struct ScheduleRow: Encodable { let id, name, dosage: String; let hour, minute: Int }
    private struct DoseRow: Encodable { let id, medication, dosage: String; let taken_at: Date; let schedule_id: String? }
    private struct CheckInRow: Encodable { let id, feeling: String; let recorded_at: Date }
    private struct SessionRow: Encodable { let object_path: String; let mode: String?; let started_at: Date? }
    private struct IdRow: Decodable { let id: String }

    private func syncNow() async -> Bool {
        guard let client = auth.client, let owner = auth.email, let userId = auth.userId, store.cloudBackup else { return false }
        do {
            if let profile = store.personalProfile(owner: owner), store.profileUploadPending {
                try await client.from("profiles").upsert(profile).execute()
                store.profileUploadPending = false
            }

            // The schedule mirrors the phone: removed dose times are removed from the account too.
            let schedule = store.schedule
            if !schedule.isEmpty {
                try await client.from("medication_schedule")
                    .upsert(schedule.map { ScheduleRow(id: $0.id, name: $0.name, dosage: $0.dosage, hour: $0.hour, minute: $0.minute) }, onConflict: "id")
                    .execute()
            }
            let remote: [IdRow] = try await client.from("medication_schedule").select("id").execute().value
            let stale = Set(remote.map(\.id)).subtracting(schedule.map(\.id))
            if !stale.isEmpty { try await client.from("medication_schedule").delete().in("id", values: Array(stale)).execute() }

            // Dose logs and check-ins are only ever added, so just send the new ones.
            let scheduleIds = Set(schedule.map(\.id))
            let newDoses = store.medications.filter { !uploadedSet(owner, .doses).contains($0.id) }
            if !newDoses.isEmpty {
                let rows = newDoses.map {
                    DoseRow(id: $0.id, medication: $0.name, dosage: $0.dosage, taken_at: $0.timestamp,
                            schedule_id: $0.slotId.flatMap { scheduleIds.contains($0) ? $0 : nil })
                }
                try await client.from("dose_logs").upsert(rows, onConflict: "id").execute()
                markUploaded(owner, .doses, newDoses.map(\.id))
            }

            let newCheckIns = store.checkIns.filter { !uploadedSet(owner, .checkIns).contains($0.id) }
            if !newCheckIns.isEmpty {
                let rows = newCheckIns.map { CheckInRow(id: $0.id, feeling: $0.feeling.rawValue.lowercased(), recorded_at: $0.timestamp) }
                try await client.from("checkins").upsert(rows, onConflict: "id").execute()
                markUploaded(owner, .checkIns, newCheckIns.map(\.id))
            }

            let doneSessions = uploadedSet(owner, .sessions)
            for file in LocalStore.sessionFiles() where !doneSessions.contains(file.lastPathComponent) {
                let name = file.lastPathComponent
                let path = "\(userId)/\(name)"
                try await client.storage.from("sessions")
                    .upload(path, data: try Data(contentsOf: file), options: FileOptions(contentType: "text/csv", upsert: true))
                let info = SessionFileName(file.deletingPathExtension().lastPathComponent)
                try await client.from("session_files")
                    .upsert(SessionRow(object_path: path, mode: info.mode, started_at: info.startedAt), onConflict: "object_path", ignoreDuplicates: true)
                    .execute()
                markUploaded(owner, .sessions, [name])
            }
            return true
        } catch {
            print("Backup failed: \(error)")
            return false
        }
    }

    // MARK: Upload markers

    private enum Kind: String, CaseIterable {
        case sessions = "uploaded_sessions", doses = "uploaded_doses", checkIns = "uploaded_checkins"
    }

    private func uploadedSet(_ owner: String, _ kind: Kind) -> Set<String> {
        Set(defaults.stringArray(forKey: "\(kind.rawValue):\(owner)") ?? [])
    }

    private func markUploaded(_ owner: String, _ kind: Kind, _ ids: [String]) {
        defaults.set(Array(uploadedSet(owner, kind).union(ids)), forKey: "\(kind.rawValue):\(owner)")
    }
}

/// session_<mode>_<yyyyMMdd_HHmmss>: the same naming as the Android app.
struct SessionFileName {
    let mode: String?
    let startedAt: Date?

    init(_ name: String) {
        let parts = name.split(separator: "_")
        mode = parts.count >= 2 ? String(parts[1]) : nil
        if parts.count >= 4 {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.dateFormat = "yyyyMMdd_HHmmss"
            startedAt = f.date(from: "\(parts[2])_\(parts[3])")
        } else {
            startedAt = nil
        }
    }
}
