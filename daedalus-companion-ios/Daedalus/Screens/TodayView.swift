import SwiftUI

struct TodayView: View {
    let openMeds: () -> Void
    let openCues: () -> Void

    @State private var store = LocalStore.shared
    @State private var auth = AuthStore.shared
    @State private var vitals = HealthData.Latest()
    @State private var watch = WatchLink.shared

    private var greeting: String {
        let hour = Calendar.current.component(.hour, from: .now)
        let base = L(hour < 12 ? "greeting_morning" : hour < 18 ? "greeting_afternoon" : "greeting_evening")
        // "Good evening, Marie" once the signed-in account has a first name.
        if let name = store.personalProfile(owner: auth.email)?.firstName, auth.email != nil, !name.isEmpty {
            return L("greeting_with_name", base, name)
        }
        return base
    }

    private var dosesToday: [MedicationLog] { store.medications.filter { Calendar.current.isDateInToday($0.timestamp) } }
    private var checkInsToday: Int { store.checkIns.filter { Calendar.current.isDateInToday($0.timestamp) }.count }
    private var sessionsToday: Int {
        LocalStore.sessionFiles().filter { SessionFileName($0.deletingPathExtension().lastPathComponent).startedAt.map(Calendar.current.isDateInToday) ?? false }.count
    }

    var body: some View {
        ScreenScroll {
            VStack(alignment: .leading, spacing: 4) {
                Text(Fmt.fullDate(.now).capitalized(with: .current)).font(DS.font(13, .medium)).foregroundStyle(DS.muted)
                Text(greeting).font(DS.title).kerning(-0.6).foregroundStyle(DS.ink)
            }

            SageCard(padding: EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16), color: DS.sageTint) {
                Button(action: openCues) {
                    HStack(spacing: 14) {
                        Image(systemName: "applewatch").font(.system(size: 22)).foregroundStyle(DS.sageDeep)
                            .frame(width: 48, height: 48).background(DS.surface, in: Circle())
                        VStack(alignment: .leading, spacing: 2) {
                            Text(L(watch.isReady ? "watch_connected" : "watch_not_connected")).font(DS.font(16, .semibold)).foregroundStyle(DS.ink)
                            Text(watchSubtitle).font(DS.font(14)).foregroundStyle(DS.sageDeep)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(DS.sageDeep)
                    }
                }
                .buttonStyle(.plain)
            }

            SageCard {
                Text(L("today_title")).font(DS.section).foregroundStyle(DS.ink)
                HStack(alignment: .top) {
                    stat(dosesToday.count, "today_doses")
                    stat(sessionsToday, "today_sessions")
                    stat(checkInsToday, "today_checkins")
                }
                .padding(.top, 14)
            }

            SageCard(padding: EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)) {
                HStack(spacing: 14) {
                    IconBadge(systemName: "pills")
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("last_dose")).font(DS.font(12)).foregroundStyle(DS.muted)
                        if let last = dosesToday.first {
                            Text("\(last.name) · \(Fmt.time(last.timestamp))").font(DS.font(16, .semibold)).foregroundStyle(DS.ink)
                        } else {
                            Text(L("last_dose_none")).font(DS.font(16, .semibold)).foregroundStyle(DS.ink)
                        }
                    }
                    Spacer()
                    Button(L("log_dose"), action: openMeds).buttonStyle(PillButtonStyle(height: 44)).fixedSize()
                }
            }

            Text(L("vitals_title")).font(DS.section).foregroundStyle(DS.ink).padding(.top, 4)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 12) {
                tile("heart", "vitals_hr", vitals.heartRate.map { "\(Int($0.value.rounded()))" }, L("unit_bpm"), vitals.heartRate?.date)
                tile("wind", "vitals_breathing", vitals.breathing.map { "\(Int($0.value.rounded()))" }, L("unit_per_min"), vitals.breathing?.date)
                tile("drop", "vitals_spo2", vitals.spo2.map { "\(Int($0.value.rounded()))" }, L("unit_percent"), vitals.spo2?.date)
                tile("gauge.with.needle", "vitals_bp", nil, L("unit_mmhg"), nil)
            }
            Text(L("vitals_hint")).font(DS.caption).foregroundStyle(DS.muted)
        }
        .toolbar(.hidden, for: .navigationBar)
        .task {
            await HealthData.shared.requestAccess()
            vitals = await HealthData.shared.latest()
        }
        .refreshable { vitals = await HealthData.shared.latest() }
    }

    private var watchSubtitle: String {
        if !watch.isReady { return L("watch_sub_pair") }
        if let mode = CueMode.byId(watch.runningMode) { return L("watch_sub_cue_on", L(mode.labelKey)) }
        return L("watch_sub_ready")
    }

    private func stat(_ value: Int, _ key: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(value)").font(DS.font(34, .medium)).foregroundStyle(DS.ink)
            Text(L(key)).font(DS.font(13)).foregroundStyle(DS.muted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func tile(_ icon: String, _ labelKey: String, _ value: String?, _ unit: String, _ date: Date?) -> some View {
        SageCard(padding: EdgeInsets(top: 14, leading: 14, bottom: 14, trailing: 14), radius: DS.radiusMedium) {
            Label(L(labelKey), systemImage: icon).font(DS.font(13)).foregroundStyle(DS.muted).labelStyle(.titleAndIcon)
            HStack(alignment: .firstTextBaseline, spacing: 4) {
                Text(value ?? L("value_none")).font(DS.font(30, .medium)).foregroundStyle(DS.ink)
                Text(unit).font(DS.font(14)).foregroundStyle(DS.muted)
            }
            .padding(.top, 8)
            if let date {
                Text(Calendar.current.isDateInToday(date) ? L("vitals_at_today", Fmt.time(date)) : L("vitals_at_day", date.formatted(date: .numeric, time: .omitted), Fmt.time(date)))
                    .font(DS.font(12)).foregroundStyle(DS.muted).padding(.top, 2)
            }
        }
    }
}
