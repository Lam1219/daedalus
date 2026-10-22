import Foundation
import Observation
import UIKit

/// On-device store. Health records live in one JSON file protected by iOS data protection
/// (readable once the phone has been unlocked after a restart); settings live in UserDefaults.
/// The records belong to one account (`owner`, its user ID) or to a guest (nil); see CloudBackup.
@Observable
final class LocalStore {
    static let shared = LocalStore()

    private struct Records: Codable {
        var medications: [MedicationLog] = []
        var checkIns: [FeelingCheckIn] = []
        var schedule: [ScheduledDose] = []
        var profile: PersonalProfile?
        var profileOwner: String?
        var profileUploadPending = false
        /// The account these records belong to (Supabase user ID), or nil for guest use.
        var owner: String?
        /// Dose times removed on this phone that still have to be removed from the account.
        var deletedScheduleIds: [String] = []
        /// Doses and check-ins removed on this phone (undo or delete), likewise. Optional so files
        /// saved before they existed still decode.
        var deletedDoseIds: [String]?
        var deletedCheckInIds: [String]?
    }

    private var records = Records()
    private let defaults = UserDefaults.standard
    private let fileURL: URL

    /// Called after every change to health records (CloudBackup sends it to the account).
    @ObservationIgnored var onChange: (() -> Void)?

    /// False while the records file exists but can't be read (the app was woken before the phone
    /// was first unlocked after a restart). Nothing is saved or backed up until it's readable,
    /// so an empty in-memory copy can never overwrite or delete real data.
    private(set) var isAvailable = true

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("records.json")
        load()
        NotificationCenter.default.addObserver(
            forName: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil, queue: .main
        ) { [weak self] _ in
            guard let self, !self.isAvailable else { return }
            self.load()
            if self.isAvailable { self.onChange?() }
        }
    }

    private func load() {
        guard FileManager.default.fileExists(atPath: fileURL.path) else { isAvailable = true; return }
        guard let data = try? Data(contentsOf: fileURL) else { isAvailable = false; return }
        records = (try? JSONDecoder().decode(Records.self, from: data)) ?? Records()
        isAvailable = true
    }

    private func save(notify: Bool = true) {
        guard isAvailable, let data = try? JSONEncoder().encode(records) else { return }
        do {
            try data.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        } catch {
            print("Couldn't save records: \(error)")
            return
        }
        if notify { onChange?() }
    }

    // MARK: Doses and check-ins

    var medications: [MedicationLog] { records.medications.sorted { $0.timestamp > $1.timestamp } }

    func addMedication(_ log: MedicationLog) {
        guard isAvailable else { return }
        records.medications.append(log)
        records.deletedDoseIds?.removeAll { $0 == log.id } // undoing a delete brings it back
        save()
    }

    /// Removes a logged dose (undo, or delete from the list); backup deletes it from the account too.
    func removeMedication(_ log: MedicationLog) {
        guard isAvailable else { return }
        records.medications.removeAll { $0.id == log.id }
        if !(records.deletedDoseIds ?? []).contains(log.id) { records.deletedDoseIds = (records.deletedDoseIds ?? []) + [log.id] }
        save()
    }

    /// Doses removed here that may still be in the account.
    var deletedDoseIds: [String] { records.deletedDoseIds ?? [] }

    func clearDeletedDoseIds(_ ids: [String]) {
        records.deletedDoseIds?.removeAll { ids.contains($0) }; save(notify: false)
    }

    var checkIns: [FeelingCheckIn] { records.checkIns.sorted { $0.timestamp > $1.timestamp } }

    func addCheckIn(_ checkIn: FeelingCheckIn) {
        guard isAvailable else { return }
        records.checkIns.append(checkIn)
        records.deletedCheckInIds?.removeAll { $0 == checkIn.id }
        save()
    }

    /// Removes a check-in (undo); backup deletes it from the account too.
    func removeCheckIn(_ checkIn: FeelingCheckIn) {
        guard isAvailable else { return }
        records.checkIns.removeAll { $0.id == checkIn.id }
        if !(records.deletedCheckInIds ?? []).contains(checkIn.id) { records.deletedCheckInIds = (records.deletedCheckInIds ?? []) + [checkIn.id] }
        save()
    }

    /// Check-ins removed here that may still be in the account.
    var deletedCheckInIds: [String] { records.deletedCheckInIds ?? [] }

    func clearDeletedCheckInIds(_ ids: [String]) {
        records.deletedCheckInIds?.removeAll { ids.contains($0) }; save(notify: false)
    }

    // MARK: Medication schedule

    var schedule: [ScheduledDose] { records.schedule.sorted { $0.minutesOfDay < $1.minutesOfDay } }

    @discardableResult
    func addScheduledDose(name: String, dosage: String, hour: Int, minute: Int) -> ScheduledDose {
        let dose = ScheduledDose(name: name, dosage: dosage, hour: hour, minute: minute)
        guard isAvailable else { return dose }
        records.schedule.append(dose); save()
        return dose
    }

    func removeScheduledDose(id: String) {
        guard isAvailable else { return }
        records.schedule.removeAll { $0.id == id }
        records.deletedScheduleIds.append(id)
        save()
    }

    /// Dose times removed here that the account still has.
    var deletedScheduleIds: [String] { records.deletedScheduleIds }

    func clearDeletedScheduleIds(_ ids: [String]) {
        records.deletedScheduleIds.removeAll { ids.contains($0) }; save(notify: false)
    }

    /// The log that marks `dose` as taken on `day`, if any.
    func takenLog(_ dose: ScheduledDose, on day: Date) -> MedicationLog? {
        records.medications.first { $0.slotId == dose.id && Calendar.current.isDate($0.timestamp, inSameDayAs: day) }
    }

    // MARK: Personal profile

    /// The profile saved for `owner` (the account email), or nil if setup hasn't been done here.
    func personalProfile(owner: String?) -> PersonalProfile? {
        records.profileOwner == owner ? records.profile : nil
    }

    func savePersonalProfile(_ profile: PersonalProfile, owner: String?, uploaded: Bool) {
        records.profile = profile
        records.profileOwner = owner
        records.profileUploadPending = !uploaded
        save(notify: false)
    }

    var profileUploadPending: Bool {
        get { records.profileUploadPending }
        set { records.profileUploadPending = newValue; save(notify: false) }
    }

    /// Deletes the health history on this phone: doses, check-ins, schedule, profile and session
    /// files. Settings stay.
    func clearHealthData() {
        guard isAvailable else { return }
        records = Records(); save(notify: false)
        for file in Self.sessionFiles() { try? FileManager.default.removeItem(at: file) }
    }

    // MARK: Account ownership

    /// The account these records belong to (Supabase user ID), or nil for guest use.
    var owner: String? { records.owner }

    func setOwner(_ owner: String?) {
        records.owner = owner; save(notify: false)
    }

    /// Whether there's any health data on this phone (records or session files).
    var hasHealthData: Bool {
        !records.medications.isEmpty || !records.checkIns.isEmpty || !records.schedule.isEmpty || !Self.sessionFiles().isEmpty
    }

    /// Adds records restored from the account, skipping any already on this phone or removed from it.
    func mergeFromAccount(schedule: [ScheduledDose], medications: [MedicationLog], checkIns: [FeelingCheckIn]) {
        guard isAvailable else { return }
        let scheduleIds = Set(records.schedule.map(\.id)).union(records.deletedScheduleIds)
        records.schedule += schedule.filter { !scheduleIds.contains($0.id) }
        let doseIds = Set(records.medications.map(\.id)).union(deletedDoseIds)
        records.medications += medications.filter { !doseIds.contains($0.id) }
        let checkInIds = Set(records.checkIns.map(\.id)).union(deletedCheckInIds)
        records.checkIns += checkIns.filter { !checkInIds.contains($0.id) }
        save(notify: false)
    }

    // MARK: Settings

    /// Whether data is backed up to the signed-in account. On by default.
    var cloudBackup: Bool {
        get { access(keyPath: \.cloudBackup); return defaults.object(forKey: "cloud_backup") as? Bool ?? true }
        set { withMutation(keyPath: \.cloudBackup) { defaults.set(newValue, forKey: "cloud_backup") } }
    }

    var medReminders: Bool {
        get { access(keyPath: \.medReminders); return defaults.object(forKey: "med_reminders") as? Bool ?? true }
        set { withMutation(keyPath: \.medReminders) { defaults.set(newValue, forKey: "med_reminders") } }
    }

    func cueRate(_ mode: CueMode) -> Int {
        let saved = defaults.object(forKey: "cue_bpm_\(mode.id)") as? Int ?? mode.defaultRate
        return min(max(saved, mode.range.lowerBound), mode.range.upperBound)
    }

    func setCueRate(_ mode: CueMode, _ rate: Int) { defaults.set(rate, forKey: "cue_bpm_\(mode.id)") }

    func autoCue(_ key: String) -> Bool { defaults.object(forKey: key) as? Bool ?? true }
    func setAutoCue(_ key: String, _ on: Bool) { defaults.set(on, forKey: key) }

    // MARK: Session files (from the Apple Watch app)

    /// Where session CSVs from the watch are kept.
    static var sessionsDir: URL {
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("sessions")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    static func sessionFiles() -> [URL] {
        let files = (try? FileManager.default.contentsOfDirectory(at: sessionsDir, includingPropertiesForKeys: [.fileSizeKey, .contentModificationDateKey])) ?? []
        return files.filter { $0.pathExtension == "csv" }
    }
}
