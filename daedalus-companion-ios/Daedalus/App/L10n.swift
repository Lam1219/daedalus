import Foundation

/// Looks up a string from Localizable.xcstrings by its key (the Android string name).
func L(_ key: String) -> String {
    NSLocalizedString(key, comment: "")
}

/// Formatted string, e.g. L("greeting_with_name", greeting, name). Plural keys pick the right form.
func L(_ key: String, _ args: CVarArg...) -> String {
    String(format: NSLocalizedString(key, comment: ""), locale: .current, arguments: args)
}

enum Fmt {
    static func time(_ date: Date) -> String { date.formatted(date: .omitted, time: .shortened) }
    static func dayTime(_ date: Date) -> String { date.formatted(date: .abbreviated, time: .shortened) }
    static func fullDate(_ date: Date) -> String { date.formatted(date: .complete, time: .omitted) }
    static func fileSize(_ bytes: Int64) -> String { ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) }
}
