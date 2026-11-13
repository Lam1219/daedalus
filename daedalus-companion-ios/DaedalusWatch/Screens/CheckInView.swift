import SwiftUI

/// Home pilot (B5): after a study beat stops, one tap: four large answers, scrollable with the
/// Digital Crown. Closes itself after `PilotController.checkinTimeout` with no answer (null).
struct CheckInView: View {
    private let answers: [(Pilot.CheckIn, String)] = [
        (.helped, "hand.thumbsup"), (.notHelped, "hand.thumbsdown"),
        (.alreadyMoving, "figure.walk"), (.notFelt, "questionmark"),
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 6) {
                Text(L("pilot_checkin_title")).font(.system(size: 15, weight: .semibold)).foregroundStyle(WS.ink)
                    .multilineTextAlignment(.center)
                ForEach(answers, id: \.0) { answer, icon in
                    Button { PilotController.shared.answer(answer) } label: {
                        Label(L("pilot_checkin_\(answer.rawValue)"), systemImage: icon)
                            .font(.system(size: 15, weight: .semibold)).foregroundStyle(WS.ground)
                            .lineLimit(2).minimumScaleFactor(0.8)
                            .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                            .padding(.horizontal, 12)
                            .background(WS.sage, in: RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                }
                // "I'm stuck" always works: a new press closes this check-in unanswered.
                Button(L("im_stuck")) { CueController.shared.start(mode: "walking", press: true) }
                    .font(.system(size: 15, weight: .semibold)).foregroundStyle(WS.ink)
                    .buttonStyle(.plain).frame(maxWidth: .infinity, minHeight: 48).background(WS.raised, in: Capsule())
                    .padding(.top, 4)
            }
        }
    }
}
