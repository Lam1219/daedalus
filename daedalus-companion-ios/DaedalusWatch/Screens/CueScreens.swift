import HealthKit
import SwiftUI

/// Home: one big "I'm stuck" button that starts the walking cue at the person's own rhythm,
/// with the latest heart rate and breathing rate underneath.
struct HomeView: View {
    @State private var link = PhoneLink.shared
    @State private var heartRate: Int?
    @State private var breathing: Int?

    var body: some View {
        VStack(spacing: 8) {
            HStack(spacing: 5) {
                Circle().fill(link.phoneReachable ? WS.sage : WS.muted).frame(width: 6, height: 6)
                Text(L("status_cues_on")).font(.system(size: 13)).foregroundStyle(WS.muted)
            }
            Button { CueController.shared.start(mode: "walking") } label: {
                VStack(spacing: 4) {
                    Image(systemName: "figure.walk").font(.system(size: 28, weight: .semibold))
                    Text(L("im_stuck")).font(.system(size: 17, weight: .semibold))
                }
                .foregroundStyle(WS.ground)
                .frame(width: 112, height: 112)
                .background(WS.sage, in: Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L("im_stuck"))
            HStack(spacing: 14) {
                metric("heart.fill", heartRate, L("unit_bpm"), L("cd_heart_rate"))
                metric("wind", breathing, L("unit_per_min"), L("cd_breathing"))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task { await loadLatest() }
    }

    private func metric(_ icon: String, _ value: Int?, _ unit: String, _ label: String) -> some View {
        HStack(spacing: 3) {
            Image(systemName: icon).font(.system(size: 11)).foregroundStyle(WS.sage)
            Text(value.map(String.init) ?? L("value_none")).font(.system(size: 15, weight: .semibold)).foregroundStyle(WS.ink)
            Text(unit).font(.system(size: 11)).foregroundStyle(WS.muted)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label) \(value.map(String.init) ?? L("value_none")) \(unit)")
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

/// Walking cue: a ring that pulses with each step, the rate, and Stop.
struct WalkView: View {
    @State private var cue = CueController.shared
    @State private var pulse = false

    var body: some View {
        ZStack {
            Circle().stroke(WS.surface, lineWidth: 6)
            Circle().stroke(WS.sage, style: StrokeStyle(lineWidth: 6, lineCap: .round))
                .scaleEffect(pulse ? 1.0 : 0.94)
                .opacity(pulse ? 1 : 0.55)
            VStack(spacing: 1) {
                Label(L("walk_label"), systemImage: "figure.walk").font(.system(size: 12)).foregroundStyle(WS.sage)
                Text("\(cue.rate)").font(.system(size: 40, weight: .semibold, design: .rounded)).foregroundStyle(WS.ink)
                Text(L("unit_steps_min")).font(.system(size: 11)).foregroundStyle(WS.muted)
                Text(L("hint_step")).font(.system(size: 11)).foregroundStyle(WS.soft)
            }
            .padding(.bottom, 26)
            VStack {
                Spacer()
                StopButton()
            }
        }
        .padding(4)
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
        VStack(spacing: 6) {
            Text(L("breathe_label")).font(.system(size: 12)).foregroundStyle(WS.sage)
            ZStack {
                Circle().fill(WS.sage.opacity(0.18))
                Circle().fill(WS.sage.opacity(0.85))
                    .scaleEffect(inhale ? 1.0 : 0.45)
                    .animation(.easeInOut(duration: Double(inhale ? rhythm.inhale : rhythm.exhale)), value: cue.breathPhase)
                Text(L(inhale ? "breathe_in" : "breathe_out")).font(.system(size: 15, weight: .semibold)).foregroundStyle(WS.ground)
            }
            .frame(width: 96, height: 96)
            Text(L("breathe_rhythm", rhythm.inhale, rhythm.exhale)).font(.system(size: 12)).foregroundStyle(WS.ink)
            if let hr = cue.heartRate {
                Text(L("breathe_hr", hr)).font(.system(size: 11)).foregroundStyle(WS.muted)
            }
            Button(L("done")) { cue.stop() }
                .font(.system(size: 14, weight: .semibold)).foregroundStyle(WS.ink)
                .buttonStyle(.plain).padding(.horizontal, 18).padding(.vertical, 6).background(WS.raised, in: Capsule())
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// Fine motor and calm: steady pulses, with pause and stop.
struct PulseView: View {
    let mode: String
    @State private var cue = CueController.shared

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: mode == "writing" ? "pencil" : "water.waves").font(.system(size: 22)).foregroundStyle(WS.sage)
            Text(L(mode == "writing" ? "mode_fine" : mode == "stress" ? "mode_calm" : "mode_other"))
                .font(.system(size: 17, weight: .semibold)).foregroundStyle(WS.ink)
            Text(cue.paused ? L("paused") : L(mode == "writing" ? "cue_fine_sub" : "cue_calm_sub"))
                .font(.system(size: 12)).foregroundStyle(WS.muted)
            Text(L("unit_pulses_min", cue.rate)).font(.system(size: 13)).foregroundStyle(WS.soft)
            HStack(spacing: 10) {
                Button { cue.togglePause() } label: {
                    Image(systemName: cue.paused ? "play.fill" : "pause.fill")
                        .font(.system(size: 18)).foregroundStyle(WS.ground)
                        .frame(width: 52, height: 52).background(WS.sage, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L(cue.paused ? "cd_resume" : "cd_pause"))
                Button { cue.stop() } label: {
                    Image(systemName: "stop.fill")
                        .font(.system(size: 18)).foregroundStyle(WS.ink)
                        .frame(width: 52, height: 52).background(WS.raised, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L("cd_stop"))
            }
            .padding(.top, 4)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct StopButton: View {
    var body: some View {
        Button { CueController.shared.stop() } label: {
            Label(L("stop"), systemImage: "stop.fill").font(.system(size: 13, weight: .semibold)).foregroundStyle(WS.ink)
                .padding(.horizontal, 14).padding(.vertical, 6).background(WS.raised, in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("cd_stop"))
    }
}
