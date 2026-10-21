import Foundation
import UserNotifications

/// Daily dose reminders for the medication schedule, with a "Mark taken" button on the
/// notification (works from the lock screen without opening the app).
enum MedReminders {
    static let category = "DOSE_REMINDER"
    static let markTakenAction = "MARK_TAKEN"

    static func registerCategory() {
        let markTaken = UNNotificationAction(identifier: markTakenAction, title: L("reminder_taken"), options: [])
        let category = UNNotificationCategory(identifier: category, actions: [markTaken], intentIdentifiers: [])
        UNUserNotificationCenter.current().setNotificationCategories([category])
    }

    /// Asks for permission the first time a dose time is added.
    static func requestPermission() async {
        _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
    }

    /// Replaces all reminders with one repeating notification per scheduled dose.
    @MainActor
    static func reschedule() async {
        let center = UNUserNotificationCenter.current()
        center.removeAllPendingNotificationRequests()
        let store = LocalStore.shared
        guard store.medReminders else { return }
        for dose in store.schedule {
            let content = UNMutableNotificationContent()
            content.title = L("reminder_title")
            content.body = dose.dosage.isEmpty ? dose.name : "\(dose.name) · \(dose.dosage)"
            content.sound = .default
            content.categoryIdentifier = category
            content.userInfo = ["doseId": dose.id]
            let trigger = UNCalendarNotificationTrigger(dateMatching: DateComponents(hour: dose.hour, minute: dose.minute), repeats: true)
            try? await center.add(UNNotificationRequest(identifier: "dose-\(dose.id)", content: content, trigger: trigger))
        }
    }

    /// Logs a scheduled dose as taken today (from the Meds checklist or the notification button).
    @MainActor
    static func markTaken(doseId: String) {
        let store = LocalStore.shared
        guard let dose = store.schedule.first(where: { $0.id == doseId }), store.takenLog(dose, on: .now) == nil else { return }
        store.addMedication(MedicationLog(name: dose.name, dosage: dose.dosage, timestamp: .now, slotId: dose.id))
    }
}

/// Handles taps on reminder notifications.
final class NotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        guard response.actionIdentifier == MedReminders.markTakenAction,
              let id = response.notification.request.content.userInfo["doseId"] as? String else { return }
        await MedReminders.markTaken(doseId: id)
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        [.banner, .sound]
    }
}
