import SwiftUI

/// Profile → Medication schedule: the daily dose times that drive the Meds checklist and reminders.
struct MedScheduleView: View {
    @State private var store = LocalStore.shared
    @State private var showAdd = false

    var body: some View {
        ScreenScroll {
            Text(L("schedule_lead")).font(DS.font(14)).foregroundStyle(DS.muted)
            SageCard(padding: .card()) {
                if store.schedule.isEmpty {
                    Text(L("schedule_empty")).font(DS.caption).foregroundStyle(DS.muted).padding(.vertical, 16)
                }
                ForEach(Array(store.schedule.enumerated()), id: \.element.id) { i, dose in
                    DoseRow(dose: dose, showDivider: i < store.schedule.count - 1)
                }
            }
            Button { showAdd = true } label: { Label(L("schedule_add"), systemImage: "plus") }
                .buttonStyle(PillButtonStyle(height: 48))
        }
        .navigationTitle(L("schedule_title"))
        .sheet(isPresented: $showAdd) {
            AddDoseSheet(initialName: store.schedule.last?.name ?? "", initialDosage: store.schedule.last?.dosage ?? "")
        }
    }
}

/// One dose time with a delete button.
struct DoseRow: View {
    let dose: ScheduledDose
    var showDivider = false
    var body: some View {
        ListRow(title: "\(Fmt.time(dose.time(on: .now))) · \(dose.name)", subtitle: dose.dosage.isEmpty ? nil : dose.dosage, icon: "clock", showDivider: showDivider) {
            Button {
                LocalStore.shared.removeScheduledDose(id: dose.id)
                Task { await MedReminders.reschedule() }
            } label: {
                Image(systemName: "trash").foregroundStyle(DS.muted).frame(width: 44, height: 44)
            }
            .accessibilityLabel(L("schedule_delete_cd", dose.name))
        }
    }
}

/// Sheet for one daily dose time: time, medication name and dosage. Asks for notification permission.
struct AddDoseSheet: View {
    let initialName: String
    let initialDosage: String
    @Environment(\.dismiss) private var dismiss
    @State private var time = Calendar.current.date(bySettingHour: 8, minute: 0, second: 0, of: .now) ?? .now
    @State private var name = ""
    @State private var dosage = ""

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 14) {
                DatePicker("", selection: $time, displayedComponents: .hourAndMinute)
                    .datePickerStyle(.wheel).labelsHidden().frame(maxWidth: .infinity)
                FieldBox { TextField(L("meds_name"), text: $name) }
                FieldBox { TextField(L("meds_dosage"), text: $dosage) }
                Spacer()
                Button(L("meds_save")) {
                    let parts = Calendar.current.dateComponents([.hour, .minute], from: time)
                    LocalStore.shared.addScheduledDose(
                        name: name.trimmingCharacters(in: .whitespaces), dosage: dosage.trimmingCharacters(in: .whitespaces),
                        hour: parts.hour ?? 8, minute: parts.minute ?? 0
                    )
                    Task {
                        // Reminders need notification permission.
                        await MedReminders.requestPermission()
                        await MedReminders.reschedule()
                    }
                    dismiss()
                }
                .buttonStyle(PillButtonStyle()).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(20)
            .background(DS.ground)
            .navigationTitle(L("schedule_add"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(L("cancel")) { dismiss() } } }
        }
        .onAppear { name = initialName; dosage = initialDosage }
        .presentationDetents([.large])
    }
}
