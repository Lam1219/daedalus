import CryptoKit
import Foundation

/// A logged dose. `slotId` links it to a scheduled dose time (nil = unscheduled / extra dose).
struct MedicationLog: Codable, Hashable {
    var name: String
    var dosage: String
    var timestamp: Date
    var slotId: String?

    /// Stable ID for backup, the same as the Android app's for the same log.
    var id: String { stableUUID("dose|\(timestamp.epochMillis)|\(name)") }
}

/// A daily dose time (Profile → Medication schedule).
struct ScheduledDose: Codable, Hashable, Identifiable {
    var id: String = UUID().uuidString.lowercased()
    var name: String
    var dosage: String
    var hour: Int
    var minute: Int
    /// Days before this don't count toward adherence.
    var createdAt: Date = .now

    var minutesOfDay: Int { hour * 60 + minute }

    func time(on day: Date) -> Date {
        Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
    }
}

enum Feeling: String, Codable, CaseIterable {
    case good = "GOOD", wearingOff = "WEARING_OFF", unsure = "UNSURE"

    var labelKey: String {
        switch self {
        case .good: "feel_good"
        case .wearingOff: "feel_wearing"
        case .unsure: "feel_unsure"
        }
    }
}

struct FeelingCheckIn: Codable, Hashable {
    var feeling: Feeling
    var timestamp: Date

    /// Stable ID for backup, the same as the Android app's for the same check-in.
    var id: String { stableUUID("checkin|\(timestamp.epochMillis)|\(feeling.rawValue)") }
}

// MARK: - Personal information (first-sign-in setup)

enum Sex: String, Codable, CaseIterable { case female, male, intersex, undisclosed }

enum Lifestyle: String, Codable, CaseIterable { case sedentary, light, active, veryActive = "very_active" }

/// Parkinson's symptoms (motor first, then non-motor). Raw values match the Supabase check.
enum Symptom: String, Codable, CaseIterable {
    case tremor, slowness, stiffness, freezing, balance, dyskinesia, wearingOff = "wearing_off", handwriting, voice, swallowing
    case sleep, fatigue, mood, memory, constipation, smell, dizziness, pain

    var labelKey: String { "symptom_\(rawValue)" }
}

/// Row of the Supabase `profiles` table. `birthDate` is ISO yyyy-MM-dd.
struct PersonalProfile: Codable, Hashable {
    var firstName: String
    var lastName: String = ""
    var birthDate: String?
    var sex: Sex?
    var lifestyle: Lifestyle?
    var symptoms: [Symptom] = []

    enum CodingKeys: String, CodingKey {
        case firstName = "first_name", lastName = "last_name", birthDate = "birth_date", sex, lifestyle, symptoms
    }

    // Writes nulls explicitly, so a skipped answer also clears it in Supabase.
    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(firstName, forKey: .firstName)
        try c.encode(lastName, forKey: .lastName)
        try c.encode(birthDate, forKey: .birthDate)
        try c.encode(sex, forKey: .sex)
        try c.encode(lifestyle, forKey: .lifestyle)
        try c.encode(symptoms, forKey: .symptoms)
    }
}

// MARK: - Cue modes

struct CueMode: Identifiable, Hashable {
    let id: String
    let labelKey: String
    let descKey: String
    let icon: String
    let defaultRate: Int

    /// Breathe is a breathing pacer (breaths per minute); the others are pulses per minute.
    var isBreathing: Bool { id == "rest" }
    var range: ClosedRange<Int> { isBreathing ? 6...20 : 40...140 }
    var step: Int { isBreathing ? 1 : 10 }

    static let all = [
        CueMode(id: "walking", labelKey: "mode_walk", descKey: "mode_walk_desc", icon: "figure.walk", defaultRate: 80),
        CueMode(id: "rest", labelKey: "mode_rest", descKey: "mode_rest_desc", icon: "wind", defaultRate: 6),
        CueMode(id: "writing", labelKey: "mode_fine", descKey: "mode_fine_desc", icon: "pencil", defaultRate: 90),
        CueMode(id: "stress", labelKey: "mode_calm", descKey: "mode_calm_desc", icon: "water.waves", defaultRate: 60),
    ]
    static func byId(_ id: String?) -> CueMode? { all.first { $0.id == id } }

    /// The label for a session's mode: a cue mode, the tremor check, or "Cue".
    static func sessionLabel(_ id: String?) -> String {
        if id == TremorAnalyzer.mode { return L("tremor_check_title") }
        return L(byId(id)?.labelKey ?? "mode_other")
    }
}

// MARK: - Tremor check

extension TremorAnalyzer.Result {
    /// One line for the Cues card and the Journal, e.g. "Rhythm 5.2 Hz · Moderate".
    var summary: String {
        switch outcome {
        case .tremor:
            let hz = (frequencyHz ?? 0).formatted(.number.precision(.fractionLength(1)))
            return L("tremor_summary_rhythm", hz, L("tremor_strength_\(strength?.rawValue ?? "light")"))
        case .none: return L("tremor_none")
        case .moved: return L("tremor_moved")
        case .incomplete: return L("tremor_incomplete")
        }
    }
}

extension TremorAnalyzer {
    /// Analyses a session file off the main actor (nil if it can't be read).
    static func analyze(file: URL) async -> Result? {
        await Task.detached(priority: .utility) {
            (try? String(contentsOf: file, encoding: .utf8)).map { analyze(csv: $0) }
        }.value
    }
}

// MARK: - Helpers

extension Date {
    var epochMillis: Int64 { Int64((timeIntervalSince1970 * 1000).rounded()) }
    init(epochMillis: Int64) { self.init(timeIntervalSince1970: Double(epochMillis) / 1000) }
}

/// Name-based UUID (version 3, MD5) — the same as Java's UUID.nameUUIDFromBytes, so a record
/// gets the same ID on iPhone and Android.
func stableUUID(_ name: String) -> String {
    var bytes = Array(Insecure.MD5.hash(data: Data(name.utf8)))
    bytes[6] = (bytes[6] & 0x0F) | 0x30
    bytes[8] = (bytes[8] & 0x3F) | 0x80
    let hex = bytes.map { String(format: "%02x", $0) }.joined()
    let i = hex.startIndex
    func part(_ a: Int, _ b: Int) -> Substring { hex[hex.index(i, offsetBy: a)..<hex.index(i, offsetBy: b)] }
    return "\(part(0, 8))-\(part(8, 12))-\(part(12, 16))-\(part(16, 20))-\(part(20, 32))"
}
