import SwiftUI

/// After sign-in: makes sure the phone's data belongs to this account, restores the account's
/// data, then shows `content` once the account has its personal information (otherwise runs the
/// setup first). A profile already saved in Supabase (e.g. from another phone) is reused.
struct ProfileSetupGate<Content: View>: View {
    let email: String?
    @ViewBuilder var content: Content

    private enum Ownership { case checking, askGuest, askOtherAccount, ok }

    @State private var ownership: Ownership = .checking
    @State private var ready = false
    @State private var checkedServer = false

    var body: some View {
        Group {
            switch ownership {
            case .askGuest, .askOtherAccount:
                PhoneDataChoice(fromOtherAccount: ownership == .askOtherAccount) { ownership = .ok }
            case .checking:
                loading
            case .ok:
                if ready {
                    content
                } else if !checkedServer {
                    loading
                } else {
                    ProfileSetupView(email: email) { ready = true }
                }
            }
        }
        .task(id: email) { checkOwnership() }
        .task(id: ownership == .ok) {
            guard ownership == .ok else { return }
            let store = LocalStore.shared
            // Bring back this account's data (schedule, doses, check-ins, sessions) on a new phone.
            await CloudBackup.shared.restoreIfNeeded()
            ready = store.personalProfile(owner: email) != nil
            if !ready, case let .found(profile) = await AuthStore.shared.fetchProfile() {
                store.savePersonalProfile(profile, owner: email, uploaded: true)
                ready = true
            }
            checkedServer = true
        }
        // Back up anything recorded before sign-in or while offline.
        .task(id: ready) { if ready { CloudBackup.shared.syncSoon() } }
    }

    private var loading: some View {
        ProgressView().tint(DS.sage).frame(maxWidth: .infinity, maxHeight: .infinity).background(DS.ground)
    }

    /// Phone data belongs to one account. Guest data is only added to an account if the person
    /// agrees; another account's data is never mixed in.
    private func checkOwnership() {
        let store = LocalStore.shared
        let userId = AuthStore.shared.userId
        if store.owner == userId {
            ownership = .ok
        } else if store.owner == nil {
            if store.hasHealthData {
                ownership = .askGuest
            } else {
                store.setOwner(userId)
                ownership = .ok
            }
        } else {
            ownership = .askOtherAccount
        }
    }
}

/// Asks what to do with data already on this phone: guest data can be added to the account;
/// another account's data can only be deleted from the phone (or the person signs out).
private struct PhoneDataChoice: View {
    let fromOtherAccount: Bool
    let onDone: () -> Void
    @State private var confirmDelete = false

    var body: some View {
        AuthFrame(onBack: nil) {
            Image(systemName: "iphone").font(.system(size: 34)).foregroundStyle(DS.sage)
            Headline(title: L("keep_data_title"), subtitle: L(fromOtherAccount ? "keep_data_other" : "keep_data_guest"))
            Spacer(minLength: 24)
            if fromOtherAccount {
                Button(L("keep_data_delete")) { confirmDelete = true }.buttonStyle(PillButtonStyle())
                Button(L("account_sign_out")) { Task { await AuthStore.shared.signOut() } }
                    .buttonStyle(OutlinePillButtonStyle())
            } else {
                Button(L("keep_data_add")) {
                    LocalStore.shared.setOwner(AuthStore.shared.userId)
                    onDone()
                }
                .buttonStyle(PillButtonStyle())
                Button(L("keep_data_delete")) { confirmDelete = true }.buttonStyle(OutlinePillButtonStyle())
            }
        }
        .alert(L("keep_data_delete"), isPresented: $confirmDelete) {
            Button(L("data_delete_button"), role: .destructive) {
                LocalStore.shared.clearHealthData()
                LocalStore.shared.setOwner(AuthStore.shared.userId)
                Task { await MedReminders.reschedule() }
                onDone()
            }
            Button(L("cancel"), role: .cancel) {}
        } message: {
            Text(L("keep_data_delete_confirm"))
        }
    }
}

private struct ProfileSetupView: View {
    let email: String?
    let onDone: () -> Void

    private enum Step: Int, CaseIterable { case name, birth, sex, lifestyle, meds, symptoms }

    @State private var store = LocalStore.shared
    @State private var step: Step = .name
    @State private var firstName = ""
    @State private var lastName = ""
    @State private var birthDay = ""
    @State private var birthMonth: Int?
    @State private var birthYear = ""
    @State private var sex: Sex?
    @State private var lifestyle: Lifestyle?
    @State private var symptoms: Set<Symptom> = []
    @State private var showAddDose = false
    @State private var saving = false

    private var birthDate: Date? {
        guard let d = Int(birthDay), let m = birthMonth, let y = Int(birthYear), y >= 1900 else { return nil }
        var parts = DateComponents(); parts.year = y; parts.month = m; parts.day = d
        let cal = Calendar(identifier: .gregorian)
        guard parts.isValidDate(in: cal), let date = cal.date(from: parts), date <= .now else { return nil }
        return date
    }
    private var birthComplete: Bool { !birthDay.isEmpty && birthMonth != nil && birthYear.count == 4 }

    var body: some View {
        AuthFrame(
            onBack: step == .name ? nil : { back() },
            busy: saving,
            progress: Double(step.rawValue + 1) / Double(Step.allCases.count)
        ) {
            switch step {
            case .name:
                Headline(title: L("setup_name_title"), subtitle: L("setup_name_sub"))
                FieldBox { TextField(L("setup_first_name"), text: $firstName).textContentType(.givenName) }
                FieldBox { TextField(L("setup_last_name"), text: $lastName).textContentType(.familyName) }
                Spacer(minLength: 24)
                Button(L("login_continue")) { next() }.buttonStyle(PillButtonStyle())
                    .disabled(firstName.trimmingCharacters(in: .whitespaces).isEmpty)

            case .birth:
                Headline(title: L("setup_birth_title"), subtitle: L("setup_birth_sub"))
                BirthDateFields(day: $birthDay, month: $birthMonth, year: $birthYear)
                if birthComplete, birthDate == nil {
                    Text(L("setup_birth_invalid")).font(DS.font(14)).foregroundStyle(DS.alert)
                }
                Spacer(minLength: 24)
                Button(L("login_continue")) { next() }.buttonStyle(PillButtonStyle()).disabled(birthDate == nil)
                SkipButton { birthDay = ""; birthMonth = nil; birthYear = ""; next() }

            case .sex:
                Headline(title: L("setup_sex_title"), subtitle: L("setup_sex_sub"))
                ForEach([(Sex.female, "setup_sex_female"), (.male, "setup_sex_male"), (.intersex, "setup_sex_intersex"), (.undisclosed, "setup_sex_undisclosed")], id: \.0) { value, key in
                    ChoiceCard(title: L(key), selected: sex == value) { sex = value }
                }
                Spacer(minLength: 24)
                Button(L("login_continue")) { next() }.buttonStyle(PillButtonStyle()).disabled(sex == nil)

            case .lifestyle:
                Headline(title: L("setup_life_title"), subtitle: L("setup_life_sub"))
                ForEach([
                    (Lifestyle.sedentary, "setup_life_sedentary"), (.light, "setup_life_light"),
                    (.active, "setup_life_active"), (.veryActive, "setup_life_very"),
                ], id: \.0) { value, key in
                    ChoiceCard(title: L(key), subtitle: L(key + "_sub"), selected: lifestyle == value) { lifestyle = value }
                }
                Spacer(minLength: 24)
                Button(L("login_continue")) { next() }.buttonStyle(PillButtonStyle()).disabled(lifestyle == nil)
                SkipButton { lifestyle = nil; next() }

            case .meds:
                Headline(title: L("setup_meds_title"), subtitle: L("setup_meds_sub"))
                if !store.schedule.isEmpty {
                    SageCard(padding: .card()) {
                        ForEach(Array(store.schedule.enumerated()), id: \.element.id) { i, dose in
                            DoseRow(dose: dose, showDivider: i < store.schedule.count - 1)
                        }
                    }
                }
                Button { showAddDose = true } label: { Label(L("schedule_add"), systemImage: "plus") }
                    .buttonStyle(OutlinePillButtonStyle())
                Spacer(minLength: 24)
                Button(L(store.schedule.isEmpty ? "setup_meds_none" : "login_continue")) { next() }.buttonStyle(PillButtonStyle())

            case .symptoms:
                Headline(title: L("setup_symptoms_title"), subtitle: L("setup_symptoms_sub"))
                SageCard(padding: .card(h: 8, v: 6)) {
                    ForEach(Symptom.allCases, id: \.self) { symptom in
                        CheckRow(title: L(symptom.labelKey), checked: symptoms.contains(symptom)) {
                            if symptoms.contains(symptom) { symptoms.remove(symptom) } else { symptoms.insert(symptom) }
                        }
                    }
                }
                Spacer(minLength: 24)
                Button(L("setup_finish")) { finish() }.buttonStyle(PillButtonStyle()).disabled(saving)
            }
        }
        .sheet(isPresented: $showAddDose) {
            AddDoseSheet(initialName: store.schedule.last?.name ?? "", initialDosage: store.schedule.last?.dosage ?? "")
        }
    }

    private func next() { if let s = Step(rawValue: step.rawValue + 1) { withAnimation { step = s } } }
    private func back() { if let s = Step(rawValue: step.rawValue - 1) { withAnimation { step = s } } }

    private func finish() {
        let iso = birthDate.map { d -> String in
            let c = Calendar(identifier: .gregorian).dateComponents([.year, .month, .day], from: d)
            return String(format: "%04d-%02d-%02d", c.year!, c.month!, c.day!)
        }
        let profile = PersonalProfile(
            firstName: firstName.trimmingCharacters(in: .whitespaces), lastName: lastName.trimmingCharacters(in: .whitespaces),
            birthDate: iso, sex: sex, lifestyle: lifestyle,
            symptoms: Symptom.allCases.filter(symptoms.contains)
        )
        saving = true
        Task {
            // Saved on the phone right away; if Supabase can't be reached it's sent on a later start.
            let uploaded = store.cloudBackup ? await AuthStore.shared.saveProfile(profile) : false
            store.savePersonalProfile(profile, owner: email, uploaded: uploaded)
            saving = false
            onDone()
        }
    }
}

/// Day / month (by name) / year: clearer than a calendar or a typed date pattern.
private struct BirthDateFields: View {
    @Binding var day: String
    @Binding var month: Int?
    @Binding var year: String

    var body: some View {
        HStack(spacing: 10) {
            FieldBox {
                TextField(L("setup_birth_day"), text: $day).keyboardType(.numberPad)
                    .onChange(of: day) { _, v in day = String(v.filter(\.isNumber).prefix(2)) }
            }
            .frame(width: 80)
            Menu {
                ForEach(1...12, id: \.self) { m in
                    Button(Calendar.current.standaloneMonthSymbols[m - 1].capitalized) { month = m }
                }
            } label: {
                FieldBox {
                    HStack {
                        Text(month.map { Calendar.current.standaloneMonthSymbols[$0 - 1].capitalized } ?? L("setup_birth_month"))
                            .foregroundStyle(month == nil ? DS.muted.opacity(0.7) : DS.ink)
                        Spacer()
                        Image(systemName: "chevron.down").font(.system(size: 13)).foregroundStyle(DS.muted)
                    }
                }
            }
            FieldBox {
                TextField(L("setup_birth_year"), text: $year).keyboardType(.numberPad)
                    .onChange(of: year) { _, v in year = String(v.filter(\.isNumber).prefix(4)) }
            }
            .frame(width: 96)
        }
    }
}

/// A single-choice option: white card, sage border and check when selected.
struct ChoiceCard: View {
    let title: String
    var subtitle: String? = nil
    let selected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(DS.font(16, .semibold)).foregroundStyle(DS.ink)
                    if let subtitle { Text(subtitle).font(DS.font(13)).foregroundStyle(DS.muted) }
                }
                Spacer()
                ZStack {
                    Circle().fill(selected ? DS.sageDeep : DS.surface)
                    Circle().stroke(selected ? DS.sageDeep : DS.sageLine, lineWidth: 1.5)
                    if selected { Image(systemName: "checkmark").font(.system(size: 11, weight: .bold)).foregroundStyle(.white) }
                }
                .frame(width: 22, height: 22)
            }
            .padding(.horizontal, 18).padding(.vertical, 16)
            .background(selected ? DS.sageTint : DS.surface, in: RoundedRectangle(cornerRadius: 16))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(selected ? DS.sageDeep : DS.switchOff, lineWidth: selected ? 1.5 : 1))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct CheckRow: View {
    let title: String
    let checked: Bool
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: checked ? "checkmark.square.fill" : "square")
                    .font(.system(size: 22)).foregroundStyle(checked ? DS.sageDeep : DS.sageLine)
                Text(title).font(DS.font(15)).foregroundStyle(DS.ink).multilineTextAlignment(.leading)
                Spacer()
            }
            .padding(.horizontal, 12).padding(.vertical, 11)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(checked ? .isSelected : [])
    }
}

private struct SkipButton: View {
    let action: () -> Void
    var body: some View {
        Button(L("setup_skip"), action: action)
            .font(DS.font(14)).foregroundStyle(DS.muted).frame(maxWidth: .infinity)
    }
}
