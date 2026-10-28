import HealthKit
import SwiftUI

/// Home: one big "I'm stuck" button that starts the walking cue at the person's own rhythm,
/// with the latest heart rate and breathing rate underneath (each only when there's a value).
/// Text uses text styles, so it follows the watch's text size.
struct HomeView: View {
    @State private var link = PhoneLink.shared
    @State private var cue = CueController.shared
    @State private var heartRate: Int?
    @State private var breathing: Int?
    /// Workouts not allowed in Health: cues then stop when the wrist goes down.
    @State private var workoutsDenied = false

    var body: some View {
        ScrollView {
        VStack(spacing: 6) {
            // Says in words whether the iPhone is connected; "I'm stuck" works either way.
            HStack(spacing: 5) {
                Circle().fill(link.phoneReachable ? WS.sage : WS.muted).frame(width: 7, height: 7)
                Text(L(link.phoneReachable ? "status_ready" : "status_phone_missing"))
                    .font(.footnote).foregroundStyle(link.phoneReachable ? WS.sage : WS.muted)
                    .lineLimit(1).minimumScaleFactor(0.8)
            }
            .accessibilityElement(children: .combine)
            Button { CueController.shared.start(mode: "walking") } label: {
                VStack(spacing: 2) {
                    Image(systemName: "figure.walk").font(.title2.weight(.semibold))
                    Text(L("im_stuck")).font(.headline).multilineTextAlignment(.center)
                        .lineLimit(2).minimumScaleFactor(0.7)
                }
                .foregroundStyle(WS.ground)
                .padding(10)
                // Always this big: it's the one thing to hit during a freeze.
                .frame(width: 116, height: 116)
                .background(WS.sage, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("im_stuck"))
            if heartRate != nil || breathing != nil {
                HStack(spacing: 12) {
                    if let heartRate { metric("heart.fill", heartRate, L("unit_bpm"), L("cd_heart_rate")) }
                    if let breathing { metric("wind", breathing, L("unit_per_min"), L("cd_breathing")) }
                }
            }
            // Here rather than on the cue screens, which stay as simple as possible.
            if workoutsDenied || cue.workoutUnavailable {
                Text(L("workout_needed")).font(.caption2).foregroundStyle(WS.muted)
                    .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 4)
            }
        }
        .frame(maxWidth: .infinity)
        }
        .task {
            workoutsDenied = HKHealthStore().authorizationStatus(for: .workoutType()) == .sharingDenied
            await loadLatest()
        }
    }

    private func metric(_ icon: String, _ value: Int, _ unit: String, _ label: String) -> some View {
        HStack(spacing: 3) {
            Image(systemName: icon).font(.caption2).foregroundStyle(WS.sage)
            Text("\(value)").font(.body.weight(.semibold)).foregroundStyle(WS.ink)
            Text(unit).font(.caption2).foregroundStyle(WS.muted)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label) \(value) \(unit)")
    }

    /// Latest readings from Apple Health (heart rate from the watch, breathing rate from sleep).
    private func loadLatest() async {
        let store = HKHealthStore()
        let bpm = HKUnit.count().unitDivided(by: .minute())
        func latest(_ id: HKQuantityTypeIdentifier) async -> Int? {
            let descriptor = HKSampleQueryDescriptor(
                predicates: [.quantitySample(type: HKQuantityType(id))],
                sortDescriptors: [SortDescriptor(\.startDate, order: .reverse)], limit: 1
            )
            return (try? await descriptor.result(for: store).first).map { Int($0.quantity.doubleValue(for: bpm).rounded()) }
        }
        heartRate = await latest(.heartRate)
        breathing = await latest(.respiratoryRate)
    }
}

/// Walking cue: a ring that pulses with each step, the rate, and a full-width Stop below it.
struct WalkView: View {
    @State private var cue = CueController.shared
    @State private var pulse = false

    var body: some View {
        VStack(spacing: 2) {
            // The ring takes all the room left between the label and Stop.
            GeometryReader { geo in
                let d = min(geo.size.width, geo.size.height)
                ZStack {
                    Circle().stroke(WS.surface, lineWidth: 5)
                    Circle().stroke(WS.sage, style: StrokeStyle(lineWidth: 5, lineCap: .round))
                        .scaleEffect(pulse ? 1.0 : 0.94)
                        .opacity(pulse ? 1 : 0.55)
                    Text("\(cue.rate)").font(.system(.title, design: .rounded).weight(.semibold)).foregroundStyle(WS.ink)
                        .lineLimit(1).minimumScaleFactor(0.5).frame(width: d * 0.7)
                }
                .frame(width: d, height: d)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            Text(L("unit_steps_min")).font(.caption2).foregroundStyle(WS.muted).lineLimit(1).minimumScaleFactor(0.7)
            StopButton()
        }
        .cueScreen(title: L("mode_walk"))
        .onChange(of: cue.beat) { _, _ in
            pulse = true
            withAnimation(.easeOut(duration: min(0.35, 30.0 / Double(cue.rate)))) { pulse = false }
        }
    }
}

/// Breathing pacer: the circle grows while breathing in and shrinks while breathing out.
struct BreatheView: View {
    @State private var cue = CueController.shared

    var body: some View {
        let rhythm = cue.breathRhythm
        let inhale = cue.breathPhase == .inhale
        VStack(spacing: 4) {
            GeometryReader { geo in
                let d = min(geo.size.width, geo.size.height)
                ZStack {
                    Circle().fill(WS.sage.opacity(0.18))
                    Circle().fill(WS.sage.opacity(0.85))
                        .scaleEffect(inhale ? 1.0 : 0.45)
                        .animation(.easeInOut(duration: Double(inhale ? rhythm.inhale : rhythm.exhale)), value: cue.breathPhase)
                }
                .frame(width: d, height: d)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            // Under the circle, so it stays readable while the circle grows and shrinks.
            Text(L(inhale ? "breathe_in" : "breathe_out")).font(.headline).foregroundStyle(WS.ink)
                .lineLimit(1).minimumScaleFactor(0.7)
            Text(L("breathe_rhythm", rhythm.inhale, rhythm.exhale)).font(.footnote).foregroundStyle(WS.ink)
                .lineLimit(1).minimumScaleFactor(0.7)
            WideButton(title: L("done"), systemImage: nil) { cue.stop() }
        }
        .cueScreen(title: L("mode_rest"))
    }
}

/// Fine motor and calm: steady pulses, with pause and stop.
struct PulseView: View {
    let mode: String
    @State private var cue = CueController.shared

    var body: some View {
        VStack(spacing: 4) {
            Image(systemName: mode == "writing" ? "pencil" : "water.waves").font(.title3).foregroundStyle(WS.sage)
            Text(cue.paused ? L("paused") : L(mode == "writing" ? "cue_fine_sub" : "cue_calm_sub"))
                .font(.footnote).foregroundStyle(WS.muted).lineLimit(1).minimumScaleFactor(0.7)
            Text(L("unit_pulses_min", cue.rate)).font(.footnote).foregroundStyle(WS.soft)
                .lineLimit(1).minimumScaleFactor(0.7)
            Spacer(minLength: 2)
            HStack(spacing: 10) {
                Button { cue.togglePause() } label: {
                    Image(systemName: cue.paused ? "play.fill" : "pause.fill")
                        .font(.title3).foregroundStyle(WS.ground)
                        .frame(maxWidth: .infinity, minHeight: 52).background(WS.sage, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L(cue.paused ? "cd_resume" : "cd_pause"))
                Button { cue.stop() } label: {
                    Image(systemName: "stop.fill")
                        .font(.title3).foregroundStyle(WS.ink)
                        .frame(maxWidth: .infinity, minHeight: 52).background(WS.raised, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L("cd_stop"))
            }
        }
        .cueScreen(title: L(mode == "writing" ? "mode_fine" : mode == "stress" ? "mode_calm" : "mode_other"))
    }
}

extension View {
    /// A cue screen: the mode name sits in the title area next to the clock, and the content
    /// uses the full height down to the bottom edge, so the ring and buttons can be big.
    func cueScreen(title: String) -> some View {
        self
            .ignoresSafeArea(.container, edges: .bottom)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
    }
}

/// Full-width pill, 48pt tall (S3: at least 44pt).
private struct WideButton: View {
    let title: String
    let systemImage: String?
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 6) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title).lineLimit(1).minimumScaleFactor(0.7)
            }
            .font(.headline).foregroundStyle(WS.ink)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(WS.raised, in: Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }
}

private struct StopButton: View {
    var body: some View {
        WideButton(title: L("stop"), systemImage: "stop.fill") { CueController.shared.stop() }
            .accessibilityLabel(L("cd_stop"))
    }
}
