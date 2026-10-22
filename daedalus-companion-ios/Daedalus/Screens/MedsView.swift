import SwiftUI

/// Meds: how you feel, then either today's dose checklist (with a schedule) or a simple dose log.
struct MedsView: View {
    @State private var store = LocalStore.shared
    @State private var showAdd = false
    @State private var undo: UndoItem?

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
        .undoBar($undo)
        .sheet(isPresented: $showAdd) {
            LogDoseSheet { log in
                LocalStore.shared.addMedication(log)
                undo = UndoItem(message: L("meds_logged")) { LocalStore.shared.removeMedication(log) }
            }
        }
    }

    private func subtitle(_ state: DoseState, taken: MedicationLog?, at: Date) -> String {
        switch state {
        case .taken: L("meds_taken_at", Fmt.time(taken?.timestamp ?? at))
        case .due: L("meds_due_now")
        case .missed: L("meds_missed", Fmt.time(at))
        case .upcoming: L("meds_upcoming")
        }
    }

    private func delete(_ log: MedicationLog) {
        store.removeMedication(log)
        undo = UndoItem(message: L("meds_deleted")) { LocalStore.shared.addMedication(log) }
    }

    /// Trash button for a logged dose (taken by mistake).
    private func deleteButton(_ log: MedicationLog, title: String) -> some View {
        Button { delete(log) } label: {
            Image(systemName: "trash").font(.system(size: 17)).foregroundStyle(DS.muted).frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L("meds_delete_cd", title))
    }

    private var feelingCard: some View {
        SageCard {
            Text(L("feel_title")).font(DS.section).foregroundStyle(DS.ink)
            HStack(spacing: 8) {
                ForEach(Feeling.allCases, id: \.self) { feeling in
                    let on = lastCheckInToday?.feeling == feeling
                    Button {
                        let checkIn = FeelingCheckIn(feeling: feeling, timestamp: .now)
                        store.addCheckIn(checkIn)
                        undo = UndoItem(message: L("feel_saved_short")) { LocalStore.shared.removeCheckIn(checkIn) }
                    } label: {
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
                let title = log.dosage.isEmpty ? log.name : "\(log.name) · \(log.dosage)"
                ListRow(title: title, subtitle: L("meds_taken_at", Fmt.time(log.timestamp)),
                        icon: "checkmark.circle", showDivider: i < dosesToday.count - 1) {
                    deleteButton(log, title: title)
                }
            }
        }
    }

    private var scheduledDoses: some View {
        SageCard(padding: .card()) {
            Text(L("meds_today")).font(DS.section).foregroundStyle(DS.ink).padding(.top, 14)
            let now = Date.now
            ForEach(Array(store.schedule.enumerated()), id: \.element.id) { i, dose in
                let taken = store.takenLog(dose, on: now)
                let at = dose.time(on: now)
                let state = DoseState(now: now, at: at, taken: taken != nil)
                let title = "\(Fmt.time(at)) · \(dose.name)"
                ListRow(
                    title: title,
                    subtitle: subtitle(state, taken: taken, at: at),
                    icon: state == .taken ? "checkmark.circle.fill" : state == .missed ? "exclamationmark.circle" : "circle",
                    showDivider: i < store.schedule.count - 1,
                    subtitleColor: state == .due ? DS.sage : state == .missed ? DS.alert : DS.muted
                ) {
                    if let taken {
                        deleteButton(taken, title: title)
                    } else if now >= at.addingTimeInterval(-DoseState.takeEarly) {
                        // From 30 minutes early, and late for a missed dose.
                        Button(L("meds_take")) {
                            if let log = MedReminders.markTaken(doseId: dose.id) {
                                undo = UndoItem(message: L("meds_logged")) { LocalStore.shared.removeMedication(log) }
                            }
                        }
                        .buttonStyle(PillButtonStyle(height: 44)).fixedSize()
                    }
                }
            }
            // Extra doses not tied to a dose time.
            ForEach(Array(dosesToday.filter { $0.slotId == nil }.enumerated()), id: \.offset) { _, log in
                ListRow(title: log.name, subtitle: L("meds_extra_at", Fmt.time(log.timestamp)), icon: "plus.circle") {
                    deleteButton(log, title: log.name)
                }
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
    let onSave: (MedicationLog) -> Void
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
                    onSave(MedicationLog(name: name.trimmingCharacters(in: .whitespaces), dosage: dosage.trimmingCharacters(in: .whitespaces), timestamp: .now))
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
