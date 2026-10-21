import SwiftUI

/// The five tabs: Today, Cues, Journal, Meds, Profile.
struct MainTabView: View {
    enum Tab: Hashable { case today, cues, journal, meds, profile }
    @State private var tab: Tab = .today

    init() {
        // Manrope in navigation titles, sage tab bar.
        let title = UIFont(name: "Manrope-SemiBold", size: 17) ?? .systemFont(ofSize: 17, weight: .semibold)
        let large = UIFont(name: "Manrope-SemiBold", size: 30) ?? .systemFont(ofSize: 30, weight: .semibold)
        let ink = UIColor(DS.ink)
        let nav = UINavigationBarAppearance()
        nav.configureWithTransparentBackground()
        nav.backgroundColor = UIColor(DS.ground)
        nav.titleTextAttributes = [.font: title, .foregroundColor: ink]
        nav.largeTitleTextAttributes = [.font: large, .foregroundColor: ink, .kern: -0.6]
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        let item = UITabBarItemAppearance()
        let tabFont = UIFont(name: "Manrope-SemiBold", size: 11) ?? .systemFont(ofSize: 11)
        item.normal.titleTextAttributes = [.font: tabFont, .foregroundColor: UIColor(DS.navInactive)]
        item.normal.iconColor = UIColor(DS.navInactive)
        item.selected.titleTextAttributes = [.font: tabFont, .foregroundColor: UIColor(DS.sageDeep)]
        item.selected.iconColor = UIColor(DS.sageDeep)
        let bar = UITabBarAppearance()
        bar.configureWithOpaqueBackground()
        bar.backgroundColor = .white
        bar.stackedLayoutAppearance = item
        UITabBar.appearance().standardAppearance = bar
        UITabBar.appearance().scrollEdgeAppearance = bar
    }

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { TodayView(openMeds: { tab = .meds }, openCues: { tab = .cues }) }
                .tabItem { Label(L("nav_today"), systemImage: "house") }.tag(Tab.today)
            NavigationStack { CuesView() }
                .tabItem { Label(L("nav_cues"), systemImage: "waveform") }.tag(Tab.cues)
            NavigationStack { JournalView() }
                .tabItem { Label(L("nav_journal"), systemImage: "calendar") }.tag(Tab.journal)
            NavigationStack { MedsView() }
                .tabItem { Label(L("nav_meds"), systemImage: "pills") }.tag(Tab.meds)
            NavigationStack { ProfileView() }
                .tabItem { Label(L("nav_profile"), systemImage: "person") }.tag(Tab.profile)
        }
    }
}
