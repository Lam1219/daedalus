import SwiftUI

/// Meds: how you feel, then either today's dose checklist (with a schedule) or a simple dose log.
struct MedsView: View {
    @State private var store = LocalStore.shared
    @State private var showAdd = false

    private var lastCheckInToday: FeelingCheckIn? { store.checkIns.first.flatMap { Calendar.current.isDateInToday($0.timestamp) ? $0 : nil } }
    private var dosesToday: [MedicationLog] { store.medications.filter { Calendar.current.isDateInToday($0.timestamp) } }

    var body: some View {
        ScreenScroll {
            HStack {
                Text(L("meds_title")).font(DS.title).kerning(-0.6).foregroundStyle(DS.ink)
                Spacer()
                Button { showAdd = true } label: {
                    Image(systemName: "plus").font(.system(size: 18, weight: .semibold)).foregroundStyle(.white)
                        .frame(width: 44, height: 44).background(DS.sageDeep, in: Circle())
                }
                .accessibilityLabel(L("meds_add_cd"))
            }

            feelingCard

            if store.schedule.isEmpty {
                loggedDoses
            } else {
                scheduledDoses
                SageCard(padding: .card()) {
                    ListRow(title: L("meds_reminders"), subtitle: L("meds_reminders_sub"), icon: "bell") {
                        SageToggle(isOn: Binding(get: { store.medReminders }, set: { on in
                            store.medReminders = on
                            Task { if on { await MedReminders.requestPermission() }; await MedReminders.reschedule() }
                        }))
                    }
                }
                weekCard
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .sheet(isPresented: $showAdd) { LogDoseSheet() }
    }

    private var feelingCard: some View {
        SageCard {
            Text(L("feel_title")).font(DS.section).foregroundStyle(DS.ink)
            HStack(spacing: 8) {
                ForEach(Feeling.allCases, id: \.self) { feeling in
                    let on = lastCheckInToday?.feeling == feeling
                    Button { store.addCheckIn(FeelingCheckIn(feeling: feeling, timestamp: .now)) } label: {
                        Text(L(feeling.labelKey)).font(DS.font(13, .semibold)).multilineTextAlignment(.center)
                            .foregroundStyle(on ? .white : DS.sageDeep)
                            .frame(maxWidth: .infinity, minHeight: 44).padding(.horizontal, 4)
                            .background(on ? DS.sageDeep : DS.sageTint, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            .padding(.top, 12)
            if let last = lastCheckInToday {
                Text(L("feel_saved", Fmt.time(last.timestamp))).font(DS.caption).foregroundStyle(DS.muted).padding(.top, 8)
            }
        }
    }

    private var loggedDoses: some View {
        SageCard(padding: .card()) {
            Text(L("meds_today")).font(DS.section).foregroundStyle(DS.ink).padding(.top, 14)
            if dosesToday.isEmpty {
                Text(L("meds_none")).font(DS.font(14)).foregroundStyle(DS.muted).padding(.vertical, 14)
            }
            ForEach(Array(dosesToday.enumerated()), id: \.offset) { i, log in
                ListRow(title: log.dosage.isEmpty ? log.name : "\(log.name) · \(log.dosage)", subtitle: L("meds_taken_at", Fmt.time(log.timestamp)),
                        icon: "checkmark.circle", showDivider: i < dosesToday.count - 1)
            }
        }
    }

    private var scheduledDoses: some View {
        SageCard(padding: .card()) {
            Text(L("meds_today")).font(DS.section).foregroundStyle(DS.ink).padding(.top, 14)
            let now = Date.now
            ForEach(Array(store.schedule.enumerated()), id: \.element.id) { i, dose in
                let taken = store.takenLog(dose, on: now)
                let due = taken == nil && dose.time(on: now) <= now
                ListRow(
                    title: "\(Fmt.time(dose.time(on: now))) · \(dose.name)",
                    subtitle: taken.map { L("meds_taken_at", Fmt.time($0.timestamp)) } ?? (due ? L("meds_due_now") : L("meds_upcoming")),
                    icon: taken != nil ? "checkmark.circle.fill" : "circle",
                    showDivider: i < store.schedule.count - 1
                ) {
                    if taken == nil {
                        Button(L("meds_take")) { MedReminders.markTaken(doseId: dose.id) }
                            .buttonStyle(PillButtonStyle(dark: due, height: 36)).fixedSize()
                    }
                }
            }
            // Extra doses not tied to a dose time.
            ForEach(Array(dosesToday.filter { $0.slotId == nil }.enumerated()), id: \.offset) { _, log in
                ListRow(title: log.name, subtitle: L("meds_extra_at", Fmt.time(log.timestamp)), icon: "plus.circle")
            }
        }
    }

    private var weekCard: some View {
        let cal = Calendar.current
        let today = cal.startOfDay(for: .now)
        let days = (0..<7).compactMap { cal.date(byAdding: .day, value: -6 + $0, to: today) }
        let expected = days.reduce(0) { sum, day in
            sum + store.schedule.filter { cal.startOfDay(for: $0.createdAt) <= day && $0.time(on: day) <= .now }.count
        }
        let taken = days.reduce(0) { sum, day in sum + store.schedule.filter { store.takenLog($0, on: day) != nil }.count }
        return SageCard {
            Text(L("meds_week")).font(DS.section).foregroundStyle(DS.ink)
            Text(L("meds_week_of", taken, expected)).font(DS.font(14)).foregroundStyle(DS.muted).padding(.top, 4)
            ProgressView(value: expected == 0 ? 0 : Double(taken) / Double(expected)).tint(DS.sageDeep).padding(.top, 10)
        }
    }
}

/// Logs a dose now (any medication, not tied to the schedule).
private struct LogDoseSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var name = LocalStore.shared.medications.first?.name ?? LocalStore.shared.schedule.first?.name ?? ""
    @State private var dosage = LocalStore.shared.medications.first?.dosage ?? LocalStore.shared.schedule.first?.dosage ?? ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 14) {
                FieldBox { TextField(L("meds_name"), text: $name) }
                FieldBox { TextField(L("meds_dosage"), text: $dosage) }
                Spacer()
                Button(L("meds_save")) {
                    LocalStore.shared.addMedication(MedicationLog(name: name.trimmingCharacters(in: .whitespaces), dosage: dosage.trimmingCharacters(in: .whitespaces), timestamp: .now))
                    dismiss()
                }
                .buttonStyle(PillButtonStyle()).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(20).background(DS.ground)
            .navigationTitle(L("log_dose")).navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { dismiss() } } }
        }
        .presentationDetents([.medium])
    }
}
