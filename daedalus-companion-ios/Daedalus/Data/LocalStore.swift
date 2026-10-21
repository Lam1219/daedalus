import Foundation
import Observation

/// On-device store. Health records live in one JSON file protected by iOS data protection
/// (unreadable while the phone is locked); settings live in UserDefaults.
/// The personal profile is also kept in Supabase for signed-in accounts (see CloudBackup).
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
    }

    private var records = Records()
    private let defaults = UserDefaults.standard
    private let fileURL: URL

    /// Called after every change to health records (CloudBackup sends it to the account).
    @ObservationIgnored var onChange: (() -> Void)?

    private init() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        fileURL = dir.appendingPathComponent("records.json")
        if let data = try? Data(contentsOf: fileURL), let saved = try? JSONDecoder().decode(Records.self, from: data) {
            records = saved
        }
    }

    private func save(notify: Bool = true) {
        guard let data = try? JSONEncoder().encode(records) else { return }
        try? data.write(to: fileURL, options: [.atomic, .completeFileProtection])
        if notify { onChange?() }
    }

    // MARK: Doses and check-ins

    var medications: [MedicationLog] { records.medications.sorted { $0.timestamp > $1.timestamp } }

    func addMedication(_ log: MedicationLog) {
        records.medications.append(log); save()
    }

    var checkIns: [FeelingCheckIn] { records.checkIns.sorted { $0.timestamp > $1.timestamp } }

    func addCheckIn(_ checkIn: FeelingCheckIn) {
        records.checkIns.append(checkIn); save()
    }

    // MARK: Medication schedule

    var schedule: [ScheduledDose] { records.schedule.sorted { $0.minutesOfDay < $1.minutesOfDay } }

    @discardableResult
    func addScheduledDose(name: String, dosage: String, hour: Int, minute: Int) -> ScheduledDose {
        let dose = ScheduledDose(name: name, dosage: dosage, hour: hour, minute: minute)
        records.schedule.append(dose); save()
        return dose
    }

    func removeScheduledDose(id: String) {
        records.schedule.removeAll { $0.id == id }; save()
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

    /// Deletes the health history on this phone: doses, check-ins, schedule and profile. Settings stay.
    func clearHealthData() {
        records = Records(); save(notify: false)
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
