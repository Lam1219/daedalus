import Foundation
import Supabase

/// Backs up the phone's health data to the signed-in account in Supabase, restores it, and
/// deletes it. Same rules as the Android app:
/// - Phone data belongs to one account (LocalStore.owner) or to a guest. Only the owner's data
///   is backed up; guest data is added to an account only if the person agrees (ProfileSetupGate).
/// - Backup: profile, medication schedule, dose logs, check-ins and watch session files. Runs at
///   start and after each change; whatever fails (offline) is retried next time.
/// - Restore: the first time an account is used on this phone, its data is brought back from
///   Supabase before anything is uploaded, so a new phone never overwrites the account.
/// - Deleting: free up space (phone copies of backed-up sessions), delete uploaded data
///   (account only, turns backup off), delete everything (phone + account data, then sign out),
///   or delete the account itself.
@MainActor
final class CloudBackup {
    static let shared = CloudBackup()

    private let store = LocalStore.shared
    private let auth = AuthStore.shared
    private var running: Task<Bool, Never>?
    /// Set while deleting, so no backup can upload in the middle of it.
    private var deleting = false
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

    // MARK: Restore

    private struct ScheduleIn: Decodable { let id, name, dosage: String; let hour, minute: Int; let created_at: Date? }
    private struct DoseIn: Decodable { let id, medication, dosage: String; let taken_at: Date; let schedule_id: String? }
    private struct CheckInIn: Decodable { let id, feeling: String; let recorded_at: Date }
    private struct SessionIn: Decodable { let object_path: String }

    /// The first time this account is used on this phone: brings its schedule, doses, check-ins
    /// and session files back from Supabase. True once done (or nothing to restore).
    @discardableResult
    func restoreIfNeeded() async -> Bool {
        guard let client = auth.client, let userId = auth.userId, store.isAvailable, store.owner == userId else { return false }
        let key = "restored:\(userId)"
        if defaults.bool(forKey: key) { return true }
        do {
            let schedule: [ScheduleIn] = try await client.from("medication_schedule").select().execute().value
            let doses: [DoseIn] = try await client.from("dose_logs").select().execute().value
            let checkIns: [CheckInIn] = try await client.from("checkins").select().execute().value
            store.mergeFromAccount(
                schedule: schedule.map {
                    ScheduledDose(id: $0.id, name: $0.name, dosage: $0.dosage, hour: $0.hour, minute: $0.minute, createdAt: $0.created_at ?? .now)
                },
                medications: doses.map { MedicationLog(name: $0.medication, dosage: $0.dosage, timestamp: $0.taken_at, slotId: $0.schedule_id) },
                checkIns: checkIns.compactMap { c in Feeling(rawValue: c.feeling.uppercased()).map { FeelingCheckIn(feeling: $0, timestamp: c.recorded_at) } }
            )
            // What came from the account is already there.
            if let email = auth.email {
                markUploaded(email, .doses, store.medications.map(\.id))
                markUploaded(email, .checkIns, store.checkIns.map(\.id))
            }

            let sessions: [SessionIn] = try await client.from("session_files").select("object_path").execute().value
            let bucket = client.storage.from("sessions")
            for session in sessions {
                let name = (session.object_path as NSString).lastPathComponent
                let destination = LocalStore.sessionsDir.appendingPathComponent(name)
                if !FileManager.default.fileExists(atPath: destination.path) {
                    let data = try await bucket.download(path: session.object_path)
                    try data.write(to: destination, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
                }
                if let email = auth.email { markUploaded(email, .sessions, [name]) }
            }
            defaults.set(true, forKey: key)
            await MedReminders.reschedule()
            return true
        } catch {
            print("Restore failed: \(error)")
            return false
        }
    }

    // MARK: Sign out

    /// Backs up what it can, then clears this phone's health data and signs out, so the next
    /// person to use the phone starts empty. `force` signs out even if some data isn't backed up.
    /// Returns false (and keeps everything) when data isn't backed up and `force` is false.
    @discardableResult
    func signOutAndClear(force: Bool) async -> Bool {
        let backedUp = await sync()
        guard backedUp || force else { return false }
        store.clearHealthData()
        store.setOwner(nil)
        await MedReminders.reschedule()
        await auth.signOut()
        return true
    }

    /// Whether everything on this phone is in the account (used to word the sign-out warning).
    func isFullyBackedUp() async -> Bool { await sync() }

    // MARK: Delete

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
        deleting = true
        defer { deleting = false }
        if let running { _ = await running.value }
        let backupWasOn = store.cloudBackup
        // Off before deleting, so nothing is uploaded again meanwhile.
        store.cloudBackup = false
        guard let client = auth.client, let owner = auth.email, let userId = auth.userId else {
            store.cloudBackup = backupWasOn
            return false
        }
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
            store.cloudBackup = backupWasOn
            return false
        }
        // Nothing is in the account now; keep it that way until backup is turned back on.
        for kind in Kind.allCases { defaults.removeObject(forKey: "\(kind.rawValue):\(owner)") }
        store.profileUploadPending = true
        return true
    }

    /// Deletes the account's data and this phone's health data, then signs out (guests: phone only).
    func deleteEverything() async -> Bool {
        let signedIn = auth.email != nil
        if signedIn, !(await deleteUploaded()) { return false }
        store.clearHealthData()
        store.setOwner(nil)
        store.cloudBackup = true
        await MedReminders.reschedule()
        if signedIn { await auth.signOut() }
        return true
    }

    /// Deletes the account itself, everything saved in it, and this phone's health data.
    func deleteAccount() async -> Bool {
        guard let client = auth.client, let userId = auth.userId else { return false }
        guard await deleteUploaded() else { return false }
        do {
            try await client.rpc("delete_my_account").execute()
        } catch {
            print("Delete account failed: \(error)")
            return false
        }
        defaults.removeObject(forKey: "restored:\(userId)")
        store.clearHealthData()
        store.setOwner(nil)
        store.cloudBackup = true
        await MedReminders.reschedule()
        await auth.signOut()
        return true
    }

    // MARK: Backup

    private struct ScheduleRow: Encodable { let id, name, dosage: String; let hour, minute: Int }
    private struct DoseRow: Encodable { let id, medication, dosage: String; let taken_at: Date; let schedule_id: String? }
    private struct CheckInRow: Encodable { let id, feeling: String; let recorded_at: Date }
    private struct SessionRow: Encodable { let object_path: String; let mode: String?; let started_at: Date? }

    private func syncNow() async -> Bool {
        guard !deleting, let client = auth.client, let owner = auth.email, let userId = auth.userId,
              store.cloudBackup, store.isAvailable, store.owner == userId
        else { return false }
        // Bring the account's data back first, so this phone never overwrites it.
        guard await restoreIfNeeded() else { return false }
        do {
            if let profile = store.personalProfile(owner: owner), store.profileUploadPending {
                try await client.from("profiles").upsert(profile).execute()
                store.profileUploadPending = false
            }

            // Dose times: send the phone's, and remove only those deleted on this phone.
            let schedule = store.schedule
            if !schedule.isEmpty {
                try await client.from("medication_schedule")
                    .upsert(schedule.map { ScheduleRow(id: $0.id, name: $0.name, dosage: $0.dosage, hour: $0.hour, minute: $0.minute) }, onConflict: "id")
                    .execute()
            }
            let deleted = store.deletedScheduleIds
            if !deleted.isEmpty {
                try await client.from("medication_schedule").delete().in("id", values: deleted).execute()
                store.clearDeletedScheduleIds(deleted)
            }

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
                if deleting { return false }
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
