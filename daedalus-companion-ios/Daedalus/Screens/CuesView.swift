import SwiftUI

/// Cue modes and their rates, set on the phone. The Apple Watch app (phase 2) plays them;
/// until then choices are saved and the start button explains the watch app is coming.
struct CuesView: View {
    @State private var store = LocalStore.shared
    @State private var selected = CueMode.all[0]
    @State private var rates: [String: Int] = [:]
    @State private var auto: [String: Bool] = [:]
    @State private var watch = WatchLink.shared

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
                            Image(systemName: mode.icon).font(.system(size: 20)).foregroundStyle(on ? .white : DS.sageDeep)
                            Text(L(mode.labelKey)).font(DS.font(16, .semibold)).foregroundStyle(on ? .white : DS.ink)
                            Text(L(mode.descKey)).font(DS.font(12)).foregroundStyle(on ? DS.onSageDeep : DS.muted).lineLimit(2, reservesSpace: true)
                        }
                        .padding(16).frame(maxWidth: .infinity, alignment: .leading)
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

            // Automatic cues
            Text(L("cue_auto_title")).font(DS.section).foregroundStyle(DS.ink).padding(.top, 4)
            SageCard(padding: .card()) {
                autoRow("cue_walking", "cue_walking_sub", "figure.walk", key: "cue_walking", divider: true)
                autoRow("cue_tremor", "cue_tremor_sub", "hand.raised", key: "cue_tremor", divider: true)
                autoRow("cue_breathing", "cue_breathing_sub", "wind", key: "cue_breathing", divider: true)
                autoRow("cue_stand", "cue_stand_sub", "figure.stand", key: "cue_stand", divider: false)
            }
            Text(L("cue_auto_note")).font(DS.caption).foregroundStyle(DS.muted)
        }
        .toolbar(.hidden, for: .navigationBar)
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

    private func autoRow(_ title: String, _ sub: String, _ icon: String, key: String, divider: Bool) -> some View {
        ListRow(title: L(title), subtitle: L(sub), icon: icon, showDivider: divider) {
            SageToggle(isOn: Binding(
                get: { auto[key] ?? store.autoCue(key) },
                set: { auto[key] = $0; store.setAutoCue(key, $0) }
            ))
        }
    }
}
