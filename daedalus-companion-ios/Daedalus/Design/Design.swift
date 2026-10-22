import SwiftUI

/// Sage palette and type from the Daedalus App Design canvas (same values as the Android app).
enum DS {
    static let ground = Color(hex: 0xF5F6F1)
    static let surface = Color.white
    static let sageTint = Color(hex: 0xE4EBDF)
    static let sageTrack = Color(hex: 0xECEFE9)
    static let sageLine = Color(hex: 0xA9BBA3)
    static let sage = Color(hex: 0x4E6447)
    static let sageDeep = Color(hex: 0x34452F)
    static let onSageDeep = Color(hex: 0xD7E2D1)
    static let ink = Color(hex: 0x1E2620)
    static let muted = Color(hex: 0x5F6B61)
    static let navInactive = Color(hex: 0x66726A)
    static let divider = Color(hex: 0xECEFE9)
    static let switchOff = Color(hex: 0xD5DCD1)
    static let alert = Color(hex: 0xA15C2E)

    static let radiusSmall: CGFloat = 12
    static let radiusMedium: CGFloat = 18
    static let radiusLarge: CGFloat = 20
    static let radiusXLarge: CGFloat = 24

    /// Manrope at a size that follows Dynamic Type (relative to body).
    static func font(_ size: CGFloat, _ weight: Font.Weight = .regular) -> Font {
        let name = switch weight {
        case .bold, .heavy, .black: "Manrope-Bold"
        case .semibold: "Manrope-SemiBold"
        case .medium: "Manrope-Medium"
        default: "Manrope-Regular"
        }
        return .custom(name, size: size, relativeTo: .body)
    }

    static let title = font(30, .semibold)
    static let section = font(16, .semibold)
    static let rowTitle = font(15, .semibold)
    static let caption = font(12)
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

// MARK: - Building blocks

/// Screen frame: sage ground, 20pt gutters, scrolling content.
struct ScreenScroll<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) { content }
                .padding(.horizontal, 20)
                .padding(.top, 12)
                .padding(.bottom, 24)
        }
        .background(DS.ground)
        .scrollDismissesKeyboard(.interactively)
    }
}

/// Borderless white card.
struct SageCard<Content: View>: View {
    var padding: EdgeInsets = EdgeInsets(top: 20, leading: 20, bottom: 20, trailing: 20)
    var color: Color = DS.surface
    var radius: CGFloat = DS.radiusXLarge
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(color, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

extension EdgeInsets {
    static func card(h: CGFloat = 20, v: CGFloat = 4) -> EdgeInsets { EdgeInsets(top: v, leading: h, bottom: v, trailing: h) }
}

/// Full-width pill button. Dark (sage) by default; light = white with sage text.
struct PillButtonStyle: ButtonStyle {
    var dark = true
    var height: CGFloat = 56
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(DS.font(16, .semibold))
            .padding(.horizontal, 18)
            .frame(maxWidth: .infinity, minHeight: height)
            .foregroundStyle(isEnabled ? (dark ? Color.white : DS.sageDeep) : DS.muted)
            .background(isEnabled ? (dark ? DS.sageDeep : DS.surface) : DS.switchOff, in: Capsule())
            .contentShape(Capsule())
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

/// Outlined pill (secondary action).
struct OutlinePillButtonStyle: ButtonStyle {
    var border: Color = DS.sageLine
    var foreground: Color = DS.sageDeep
    var background: Color = DS.surface
    var height: CGFloat = 52
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(DS.font(16, .semibold))
            .frame(maxWidth: .infinity, minHeight: height)
            .foregroundStyle(foreground)
            .background(background, in: Capsule())
            .overlay(Capsule().stroke(border, lineWidth: 1))
            // The whole pill is tappable, even with a clear background.
            .contentShape(Capsule())
            .opacity(configuration.isPressed ? 0.85 : 1)
    }
}

/// A list row inside a card: icon, title, subtitle, and a trailing control or chevron.
struct ListRow<Trailing: View>: View {
    let title: String
    var subtitle: String? = nil
    var icon: String? = nil
    var showDivider = false
    var action: (() -> Void)? = nil
    @ViewBuilder var trailing: Trailing

    var body: some View {
        VStack(spacing: 0) {
            let row = HStack(spacing: 14) {
                if let icon { Image(systemName: icon).font(.system(size: 18)).foregroundStyle(DS.sage).frame(width: 22) }
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(DS.rowTitle).foregroundStyle(DS.ink)
                    if let subtitle { Text(subtitle).font(DS.font(13)).foregroundStyle(DS.muted).fixedSize(horizontal: false, vertical: true) }
                }
                Spacer(minLength: 8)
                trailing
                if action != nil, Trailing.self == EmptyView.self {
                    Image(systemName: "chevron.right").font(.system(size: 13, weight: .semibold)).foregroundStyle(DS.navInactive)
                }
            }
            .padding(.vertical, 14)
            .contentShape(Rectangle())
            if let action { Button(action: action) { row }.buttonStyle(.plain) } else { row }
            if showDivider { Divider().overlay(DS.divider) }
        }
    }
}

extension ListRow where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil, icon: String? = nil, showDivider: Bool = false, action: (() -> Void)? = nil) {
        self.init(title: title, subtitle: subtitle, icon: icon, showDivider: showDivider, action: action) { EmptyView() }
    }
}

/// Round icon on a sage tint.
struct IconBadge: View {
    let systemName: String
    var size: CGFloat = 44
    var body: some View {
        Image(systemName: systemName)
            .font(.system(size: size * 0.42))
            .foregroundStyle(DS.sageDeep)
            .frame(width: size, height: size)
            .background(DS.sageTint, in: Circle())
    }
}

/// Sage switch.
struct SageToggle: View {
    @Binding var isOn: Bool
    var body: some View { Toggle("", isOn: $isOn).labelsHidden().tint(DS.sage) }
}

/// Header with a back arrow, for sub-screens.
struct BackHeader: View {
    let title: String
    let onBack: () -> Void
    var body: some View {
        HStack(spacing: 8) {
            Button(action: onBack) {
                Image(systemName: "arrow.left").font(.system(size: 20, weight: .medium)).foregroundStyle(DS.ink).frame(width: 44, height: 44)
            }
            .accessibilityLabel(Text("back"))
            .padding(.leading, -12)
            Text(title).font(DS.font(26, .semibold)).foregroundStyle(DS.ink)
        }
    }
}
