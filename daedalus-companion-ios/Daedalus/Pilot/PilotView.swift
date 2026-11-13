import SwiftUI

/// Profile → Home pilot: the study switch (off by default), the person's pilot code, seed and
/// enrolment day, the pocket reminder times, and "Export pilot files". Set by the physio at the
/// clinic visit; everything is sent to the watch over the existing link.
struct PilotView: View {
    @State private var pilot = PilotStore.shared
    /// 16: edits to the code, seed, start date and earbuds check stay here until Save.
    @State private var draft = PilotDraft(PilotSettings())
    @State private var messageKey: String?
    @State private var newReminder = Calendar.current.date(bySettingHour: 9, minute: 0, second: 0, of: .now) ?? .now
    @State private var files: [URL] = []

    private var settings: PilotSettings { pilot.settings }
    private var locked: Bool { settings.fieldsLocked }

    private var enrolmentDate: Binding<Date> {
        Binding(get: {
            Pilot.enrolmentComponents(draft.enrolment).flatMap { Calendar.current.date(from: $0) } ?? .now
        }, set: { draft.enrolment = Pilot.enrolmentText($0) })
    }

    var body: some View {
        ScreenScroll {
            Text(L("pilot_lead")).font(DS.font(14)).foregroundStyle(DS.muted)

            SageCard(padding: .card()) {
                ListRow(title: L("pilot_switch_title"), subtitle: L("pilot_switch_sub"), icon: "testtube.2") {
                    SageToggle(isOn: Binding(get: { settings.enabled }, set: { on in
                        // Study mode starts only from saved, valid settings.
                        var s = settings
                        s.enabled = on
                        if on && !s.isActive { messageKey = "pilot_switch_needs_save"; return }
                        messageKey = nil
                        pilot.settings = s
                    }))
                }
            }
            if let messageKey {
                Text(L(messageKey)).font(DS.font(13, .semibold)).foregroundStyle(DS.sageDeep)
            }

            SageCard {
                Text(L("pilot_participant")).font(DS.section).foregroundStyle(DS.ink)
                TextField(L("pilot_participant_hint"), text: $draft.participant)
                    .textInputAutocapitalization(.characters).autocorrectionDisabled()
                    .font(DS.font(17)).frame(minHeight: 44)
                Text(L("pilot_seed")).font(DS.section).foregroundStyle(DS.ink).padding(.top, 12)
                TextField(L("pilot_seed_hint"), text: $draft.seedText)
                    .keyboardType(.numberPad).font(DS.font(17)).frame(minHeight: 44)
                DatePicker(L("pilot_enrolment"), selection: enrolmentDate, displayedComponents: .date)
                    .font(DS.font(16)).frame(minHeight: 44).padding(.top, 12)
                // 8: iOS can't tell earbuds from a speaker; the physio checks the person's own earbuds.
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("pilot_earbuds_paired")).font(DS.font(16, .semibold)).foregroundStyle(DS.ink)
                        Text(L("pilot_earbuds_paired_sub")).font(DS.font(13)).foregroundStyle(DS.muted)
                    }
                    Spacer()
                    SageToggle(isOn: $draft.earbudsPaired)
                }
                .padding(.top, 12)
                if locked {
                    Text(L("pilot_locked")).font(DS.font(13)).foregroundStyle(DS.muted).padding(.top, 12)
                } else {
                    Button {
                        if let s = draft.applied(to: settings) {
                            pilot.settings = s
                            draft = PilotDraft(s)
                            messageKey = "pilot_saved"
                        } else {
                            messageKey = "pilot_invalid"
                        }
                    } label: { Text(L("pilot_save")) }
                        .buttonStyle(PillButtonStyle()).disabled(draft == PilotDraft(settings)).padding(.top, 12)
                }
            }
            .disabled(locked)

            SageCard {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(L("pilot_pocket_card")).font(DS.section).foregroundStyle(DS.ink)
                        Text(L("pilot_pocket_sub")).font(DS.font(13)).foregroundStyle(DS.muted)
                    }
                    Spacer()
                    SageToggle(isOn: Binding(get: { settings.pocketReminder }, set: { pilot.settings.pocketReminder = $0 }))
                }
                if settings.pocketReminder {
                    ForEach(settings.reminderMinutes.sorted(), id: \.self) { m in
                        HStack {
                            Text(Self.timeText(m)).font(DS.font(17)).foregroundStyle(DS.ink)
                            Spacer()
                            Button { pilot.settings.reminderMinutes.removeAll { $0 == m } } label: {
                                Image(systemName: "minus.circle").font(.system(size: 22)).foregroundStyle(DS.sageDeep)
                                    .frame(width: 44, height: 44)
                            }
                            .accessibilityLabel(L("pilot_reminder_remove", Self.timeText(m)))
                        }
                    }
                    HStack {
                        DatePicker(L("pilot_reminder_add"), selection: $newReminder, displayedComponents: .hourAndMinute)
                            .font(DS.font(16))
                        Button {
                            let c = Calendar.current.dateComponents([.hour, .minute], from: newReminder)
                            let m = (c.hour ?? 0) * 60 + (c.minute ?? 0)
                            if !settings.reminderMinutes.contains(m) { pilot.settings.reminderMinutes.append(m) }
                        } label: {
                            Image(systemName: "plus.circle.fill").font(.system(size: 26)).foregroundStyle(DS.sageDeep)
                                .frame(width: 44, height: 44)
                        }
                        .accessibilityLabel(L("pilot_reminder_add"))
                    }
                    .padding(.top, 8)
                }
            }

            Text(L("pilot_iphone_note")).font(DS.font(13)).foregroundStyle(DS.muted)

            SageCard {
                Text(L("pilot_files_title")).font(DS.section).foregroundStyle(DS.ink)
                Text(L("pilot_files_sub", files.count)).font(DS.font(13)).foregroundStyle(DS.muted).padding(.top, 2)
                ShareLink(items: files) { Label(L("pilot_export"), systemImage: "square.and.arrow.up") }
                    .buttonStyle(PillButtonStyle()).disabled(files.isEmpty).padding(.top, 12)
            }
        }
        .navigationTitle(L("pilot_title"))
        .onAppear {
            draft = PilotDraft(settings)
            if Pilot.enrolmentComponents(draft.enrolment) == nil { draft.enrolment = Pilot.enrolmentText(.now) }
            files = PilotStore.files()
        }
        .task(id: pilot.filesReceived) { files = PilotStore.files() }
    }

    static func timeText(_ minutes: Int) -> String {
        let d = Calendar.current.date(bySettingHour: minutes / 60, minute: minutes % 60, second: 0, of: .now) ?? .now
        return Fmt.time(d)
    }
}
