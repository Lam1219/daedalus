import Charts
import SwiftUI

/// Journal: a day's heart rate (Apple Health) with dose markers, and a timeline of doses,
/// check-ins and cue sessions. Days are picked from the last week.
struct JournalView: View {
    @State private var store = LocalStore.shared
    @State private var day = Calendar.current.startOfDay(for: .now)
    @State private var heartRate: [HealthData.Reading] = []
    @State private var loading = true

    private struct Entry: Identifiable { let id = UUID(); let date: Date; let icon: String; let title: String; let subtitle: String? }

    private var entries: [Entry] {
        let cal = Calendar.current
        var out: [Entry] = []
        for log in store.medications where cal.isDate(log.timestamp, inSameDayAs: day) {
            out.append(Entry(date: log.timestamp, icon: "pills", title: L("journal_dose"), subtitle: log.dosage.isEmpty ? log.name : "\(log.name) · \(log.dosage)"))
        }
        for c in store.checkIns where cal.isDate(c.timestamp, inSameDayAs: day) {
            out.append(Entry(date: c.timestamp, icon: "face.smiling", title: L("journal_checkin"), subtitle: L(c.feeling.labelKey)))
        }
        for file in LocalStore.sessionFiles() {
            let info = SessionFileName(file.deletingPathExtension().lastPathComponent)
            if let start = info.startedAt, cal.isDate(start, inSameDayAs: day) {
                out.append(Entry(date: start, icon: "waveform", title: L("journal_session", L(CueMode.byId(info.mode)?.labelKey ?? "mode_other")), subtitle: nil))
            }
        }
        return out.sorted { $0.date > $1.date }
    }

    var body: some View {
        ScreenScroll {
            Text(L("journal_title")).font(DS.title).kerning(-0.6).foregroundStyle(DS.ink)
            dayPicker
            hrCard
            SageCard(padding: .card()) {
                if entries.isEmpty {
                    Text(L("journal_empty")).font(DS.font(14)).foregroundStyle(DS.muted).padding(.vertical, 16)
                }
                ForEach(Array(entries.enumerated()), id: \.element.id) { i, e in
                    ListRow(title: "\(Fmt.time(e.date)) · \(e.title)", subtitle: e.subtitle, icon: e.icon, showDivider: i < entries.count - 1)
                }
            }
            ShareLink(item: shareText, subject: Text(L("journal_share_subject", Fmt.fullDate(day)))) {
                Label(L("journal_share"), systemImage: "square.and.arrow.up")
            }
            .buttonStyle(OutlinePillButtonStyle())
        }
        .toolbar(.hidden, for: .navigationBar)
        .task(id: day) {
            loading = true
            heartRate = await HealthData.shared.heartRate(on: day)
            loading = false
        }
    }

    private var dayPicker: some View {
        let cal = Calendar.current
        let today = cal.startOfDay(for: .now)
        return HStack(spacing: 6) {
            ForEach((0..<7).reversed(), id: \.self) { back in
                let d = cal.date(byAdding: .day, value: -back, to: today)!
                let on = cal.isDate(d, inSameDayAs: day)
                Button { day = d } label: {
                    VStack(spacing: 4) {
                        Text(d.formatted(.dateTime.weekday(.narrow))).font(DS.font(12)).foregroundStyle(on ? DS.onSageDeep : DS.muted)
                        Text(d.formatted(.dateTime.day())).font(DS.font(16, .semibold)).foregroundStyle(on ? .white : DS.ink)
                    }
                    .frame(maxWidth: .infinity, minHeight: 56)
                    .background(on ? DS.sageDeep : DS.surface, in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Fmt.fullDate(d))
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
    }

    private var hrCard: some View {
        SageCard {
            HStack {
                Text(L("journal_hr_title")).font(DS.section).foregroundStyle(DS.ink)
                Spacer()
                if let lo = heartRate.map(\.value).min(), let hi = heartRate.map(\.value).max() {
                    Text(L("journal_hr_range", Int(lo), Int(hi))).font(DS.font(13)).foregroundStyle(DS.muted)
                }
            }
            if loading {
                Text(L("journal_hr_loading")).font(DS.font(14)).foregroundStyle(DS.muted).padding(.vertical, 24)
            } else if heartRate.isEmpty {
                Text(L("journal_hr_empty")).font(DS.font(14)).foregroundStyle(DS.muted).padding(.top, 10)
            } else {
                let doses = store.medications.filter { Calendar.current.isDate($0.timestamp, inSameDayAs: day) }
                Chart {
                    ForEach(Array(heartRate.enumerated()), id: \.offset) { _, r in
                        LineMark(x: .value("Time", r.date), y: .value("bpm", r.value)).foregroundStyle(DS.sageDeep).interpolationMethod(.monotone)
                    }
                    ForEach(Array(doses.enumerated()), id: \.offset) { _, d in
                        RuleMark(x: .value("Dose", d.timestamp)).foregroundStyle(DS.sageLine).lineStyle(StrokeStyle(lineWidth: 1, dash: [3, 3]))
                            .annotation(position: .top, alignment: .leading) { Text(L("journal_hr_dose")).font(DS.font(10)).foregroundStyle(DS.muted) }
                    }
                }
                .chartYScale(domain: .automatic(includesZero: false))
                .frame(height: 160)
                .padding(.top, 12)
                .accessibilityLabel(L("journal_hr_title"))
            }
        }
    }

    private var shareText: String {
        var lines = ["Daedalus · \(Fmt.fullDate(day))"]
        for e in entries.reversed() { lines.append("\(Fmt.time(e.date))  \(e.title)\(e.subtitle.map { " — \($0)" } ?? "")") }
        return lines.joined(separator: "\n")
    }
}
