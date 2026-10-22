import Foundation
import HealthKit

/// Vital signs from Apple Health (any Apple Watch writes these): heart rate, blood oxygen and
/// breathing rate. Read-only; Daedalus never writes to Apple Health.
@MainActor
final class HealthData {
    static let shared = HealthData()
    private let store = HKHealthStore()

    struct Reading { let value: Double; let date: Date }
    struct Latest { var heartRate: Reading?; var spo2: Reading?; var breathing: Reading? }

    private let heartRate = HKQuantityType(.heartRate)
    private let spo2 = HKQuantityType(.oxygenSaturation)
    private let breathing = HKQuantityType(.respiratoryRate)

    var isAvailable: Bool { HKHealthStore.isHealthDataAvailable() }

    /// Shows Apple's permission sheet once; later calls return straight away.
    func requestAccess() async {
        guard isAvailable else { return }
        try? await store.requestAuthorization(toShare: [], read: [heartRate, spo2, breathing])
    }

    func latest() async -> Latest {
        guard isAvailable else { return Latest() }
        let bpm = HKUnit.count().unitDivided(by: .minute())
        async let hr = latestSample(heartRate, unit: bpm)
        async let ox = latestSample(spo2, unit: .percent())
        async let br = latestSample(breathing, unit: bpm)
        let oxygen = await ox
        return Latest(
            heartRate: await hr,
            spo2: oxygen.map { Reading(value: $0.value * 100, date: $0.date) },
            breathing: await br
        )
    }

    /// Heart rate samples for one day (Journal chart).
    func heartRate(on day: Date) async -> [Reading] {
        guard isAvailable else { return [] }
        let start = Calendar.current.startOfDay(for: day)
        let end = Calendar.current.date(byAdding: .day, value: 1, to: start)!
        let predicate = HKQuery.predicateForSamples(withStart: start, end: end)
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.quantitySample(type: heartRate, predicate: predicate)],
            sortDescriptors: [SortDescriptor(\.startDate)]
        )
        let bpm = HKUnit.count().unitDivided(by: .minute())
        let samples = (try? await descriptor.result(for: store)) ?? []
        return samples.map { Reading(value: $0.quantity.doubleValue(for: bpm), date: $0.startDate) }
    }

    private func latestSample(_ type: HKQuantityType, unit: HKUnit) async -> Reading? {
        let descriptor = HKSampleQueryDescriptor(
            predicates: [.quantitySample(type: type)],
            sortDescriptors: [SortDescriptor(\.startDate, order: .reverse)],
            limit: 1
        )
        guard let sample = try? await descriptor.result(for: store).first else { return nil }
        return Reading(value: sample.quantity.doubleValue(for: unit), date: sample.startDate)
    }
}
