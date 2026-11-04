import SwiftUI

/// Cue modes and their rates, set on the phone and played by the Apple Watch app.
struct CuesView: View {
    @State private var store = LocalStore.shared
    @State private var selected = CueMode.all[0]
    @State private var rates: [String: Int] = [:]
    @State private var watch = WatchLink.shared
    /// The newest tremor check on this phone (from its session file).
    @State private var lastTremor: (result: TremorAnalyzer.Result, at: Date)?

    var body: some View {
        ScreenScroll {
            Text(L("cues_title")).font(DS.title).kerning(-0.6).foregroundStyle(DS.ink)
            Text(L("cues_lead")).font(DS.font(14)).foregroundStyle(DS.muted)

            // Mode picker
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                ForEach(CueMode.all) { mode in
                    let on = mode == selected
                    Button { selected = mode } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            // Fixed slot: SF Symbols differ in height (figure.walk is taller than wind), which made cards uneven.
                            Image(systemName: mode.icon).font(.system(size: 20)).foregroundStyle(on ? .white : DS.sageDeep)
                                .frame(height: 24)
                            Text(L(mode.labelKey)).font(DS.font(16, .semibold)).foregroundStyle(on ? .white : DS.ink)
                            Text(L(mode.descKey)).font(DS.font(12)).foregroundStyle(on ? DS.onSageDeep : DS.muted).lineLimit(2, reservesSpace: true)
                        }
                        .padding(16).frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                        .background(on ? DS.sageDeep : DS.surface, in: RoundedRectangle(cornerRadius: DS.radiusMedium))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }

            // Rate
            let rate = rates[selected.id] ?? store.cueRate(selected)
            let unit = L(selected.id == "walking" ? "unit_steps_min" : selected.isBreathing ? "unit_breaths_min" : "unit_pulses_min")
            SageCard {
                Text(L("cue_rate")).font(DS.section).foregroundStyle(DS.ink)
                Text(L("cue_rate_sub", unit, selected.step)).font(DS.font(13)).foregroundStyle(DS.muted).padding(.top, 2)
                HStack {
                    stepButton("minus", L("cue_rate_down", selected.step), enabled: rate > selected.range.lowerBound) { change(-selected.step) }
                    Spacer()
                    Text("\(rate)").font(DS.font(44, .medium)).foregroundStyle(DS.ink).contentTransition(.numericText())
                    Spacer()
                    stepButton("plus", L("cue_rate_up", selected.step), enabled: rate < selected.range.upperBound) { change(selected.step) }
                }
                .padding(.top, 14)
            }

            if watch.runningMode == selected.id {
                Text(L("cue_running")).font(DS.font(13, .semibold)).foregroundStyle(DS.sageDeep)
                Button { watch.stop() } label: { Label(L("cue_stop"), systemImage: "stop.fill") }
                    .buttonStyle(PillButtonStyle(dark: false))
            } else {
                Button { watch.start(mode: selected) } label: { Label(L("cue_start"), systemImage: "play.fill") }
                    .buttonStyle(PillButtonStyle()).disabled(!watch.isReady)
                if !watch.isReady {
                    Text(L("cue_watch_missing")).font(DS.font(13)).foregroundStyle(DS.muted)
                }
            }

            tremorCard

            // Automatic cues: detection isn't built yet, so they're shown as coming, never as on.
            HStack(spacing: 8) {
                Text(L("cue_auto_title")).font(DS.section).foregroundStyle(DS.ink)
                Text(L("coming_soon")).font(DS.font(12, .semibold)).foregroundStyle(DS.sageDeep)
                    .padding(.horizontal, 10).padding(.vertical, 4).background(DS.sageTint, in: Capsule())
            }
            .padding(.top, 4)
            Text(L("cue_auto_note")).font(DS.font(13)).foregroundStyle(DS.muted)
            SageCard(padding: .card()) {
                ListRow(title: L("cue_walking"), subtitle: L("cue_walking_sub"), icon: "figure.walk", showDivider: true)
                ListRow(title: L("cue_tremor"), subtitle: L("cue_tremor_sub"), icon: "hand.raised", showDivider: true)
                ListRow(title: L("cue_breathing"), subtitle: L("cue_breathing_sub"), icon: "wind")
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task(id: watch.filesReceived) { await loadLastTremor() }
    }

    /// Tremor check: not a cue mode; the watch records 20 s of the hand at rest and measures its rhythm.
    private var tremorCard: some View {
        SageCard {
            Label(L("tremor_check_title"), systemImage: "hand.raised").font(DS.section).foregroundStyle(DS.ink)
            Text(L("tremor_check_body")).font(DS.font(13)).foregroundStyle(DS.muted).padding(.top, 2)
            if let lastTremor {
                Text(L("tremor_check_last", lastTremor.result.summary, Fmt.dayTime(lastTremor.at)))
                    .font(DS.font(13, .semibold)).foregroundStyle(DS.ink).padding(.top, 10)
            }
            VStack(alignment: .leading, spacing: 8) {
                if watch.runningMode == TremorAnalyzer.mode {
                    Text(L("tremor_check_running")).font(DS.font(13, .semibold)).foregroundStyle(DS.sageDeep)
                    // Light pills vanish on the white card, so Stop uses the tinted background.
                    Button { watch.stop() } label: { Label(L("cue_stop"), systemImage: "stop.fill") }
                        .buttonStyle(PillButtonStyle(dark: false)).background(DS.sageTint, in: Capsule())
                } else {
                    Button { watch.startTremorCheck() } label: { Label(L("tremor_check_start"), systemImage: "hand.raised") }
                        .buttonStyle(PillButtonStyle()).disabled(!watch.isReady || watch.runningMode != nil)
                }
            }
            .padding(.top, 12)
            Text(L("tremor_check_note")).font(DS.font(12)).foregroundStyle(DS.muted).padding(.top, 8)
        }
    }

    private func loadLastTremor() async {
        let newest = LocalStore.sessionFiles()
            .filter { $0.lastPathComponent.hasPrefix("session_\(TremorAnalyzer.mode)_") }
            .max { $0.lastPathComponent < $1.lastPathComponent }
        guard let newest, let at = SessionFileName(newest.deletingPathExtension().lastPathComponent).startedAt,
              let result = await TremorAnalyzer.analyze(file: newest)
        else { lastTremor = nil; return }
        lastTremor = (result, at)
    }

    private func change(_ delta: Int) {
        let r = rates[selected.id] ?? store.cueRate(selected)
        let new = min(max(r + delta, selected.range.lowerBound), selected.range.upperBound)
        withAnimation { rates[selected.id] = new }
        store.setCueRate(selected, new)
        watch.sendRates()
    }

    private func stepButton(_ icon: String, _ label: String, enabled: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 20, weight: .semibold))
                .foregroundStyle(enabled ? DS.sageDeep : DS.sageLine)
                .frame(width: 56, height: 56).background(DS.sageTint, in: Circle())
        }
        .disabled(!enabled)
        .accessibilityLabel(label)
    }

}
