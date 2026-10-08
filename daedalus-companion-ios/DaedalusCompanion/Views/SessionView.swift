//
//  SessionView.swift
//  DaedalusCompanion
//
//  Main SwiftUI view for Phase 0 — minimal session controls.
//

import SwiftUI

struct SessionView: View {
    @StateObject private var watchBLE = WatchBLEManager()
    @StateObject private var chestStrapBLE = ChestStrapBLEManager()
    @StateObject private var sessionManager = SessionManager.shared
    
    @State private var showingExportSheet = false
    @State private var showingLabeling = false
    @State private var exportURL: URL?
    
    var body: some View {
        NavigationView {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // Header
                    Text("app_title")
                        .font(.title2)
                        .fontWeight(.bold)
                    
                    // Watch connection status
                    VStack(alignment: .leading, spacing: 4) {
                        Text(watchBLE.isAdvertising ? "watch_advertising" : "watch_not_advertising")
                            .font(.caption)
                        Text(chestStrapBLE.connectionState.rawValue)
                            .font(.caption)
                            .foregroundColor(connectionStateColor)
                    }
                    
                    // Connect buttons
                    HStack(spacing: 12) {
                        Button(action: { watchBLE.startAdvertising() }) {
                            Label("btn_advertise_watch", systemImage: "applewatch")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .disabled(watchBLE.isAdvertising)
                        
                        Button(action: { chestStrapBLE.scanAndConnect() }) {
                            Label("btn_connect_chest", systemImage: "heart.circle")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .disabled(chestStrapBLE.connectionState == .connected)
                    }
                    
                    // HR display
                    if let hr = chestStrapBLE.latestHrBpm {
                        let rrText = chestStrapBLE.latestRrMs.map { String(format: NSLocalizedString("rr_format", comment: ""), $0) } ?? ""
                        Text(String(format: NSLocalizedString("hr_format", comment: ""), Int(hr)) + rrText)
                            .font(.title3)
                            .monospacedDigit()
                    }
                    
                    if let watchHr = watchBLE.lastHrSample?.hrBpm {
                        Text(String(format: NSLocalizedString("watch_hr_format", comment: ""), Int(watchHr)))
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    
                    // Session status
                    if sessionManager.isRunning {
                        Text(String(format: "Session: %@/%@", sessionManager.currentSubjectId, sessionManager.currentSessionId))
                            .font(.caption)
                        Text("State: ACTIVE")
                            .font(.caption)
                            .foregroundColor(.green)
                    } else {
                        Text("session_idle")
                            .font(.caption)
                    }
                    
                    // Session controls
                    HStack(spacing: 12) {
                        Button(action: startSession) {
                            Label("btn_start", systemImage: "play.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(sessionManager.isRunning)
                        
                        Button(action: endSession) {
                            Label("btn_stop", systemImage: "stop.fill")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        .tint(.red)
                        .disabled(!sessionManager.isRunning)
                    }
                    
                    // Export button
                    Button(action: {
                        exportURL = sessionManager.exportSession()
                        showingExportSheet = true
                    }) {
                        Label("btn_export", systemImage: "square.and.arrow.up")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .disabled(!sessionManager.isRunning && sessionManager.currentSessionId.isEmpty)

                    // Protocol labeling
                    NavigationLink(destination: ProtocolLabelingView()) {
                        Label("Protocol Labeling", systemImage: "list.bullet.clipboard")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)

                    // Event markers
                    Text("label_events")
                        .font(.headline)
                    
                    VStack(spacing: 8) {
                        Button(action: { logEvent(.restStart) }) {
                            Label("btn_rest", systemImage: "figure.mind.and.body")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        
                        Button(action: { logEvent(.stressTaskStart) }) {
                            Label("btn_stress", systemImage: "brain.head.profile")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                        
                        Button(action: { logEvent(.syncClaps) }) {
                            Label("btn_sync", systemImage: "hands.clap")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.bordered)
                    }
                    
                    // Stats
                    if !sessionManager.isRunning && !sessionManager.currentSessionId.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Session Stats:")
                                .font(.caption)
                                .fontWeight(.medium)
                            Text("IMU samples: \(sessionManager.currentSessionId)")
                            // We'll add a proper stats display later
                        }
                        .font(.caption)
                    }
                }
                .padding()
            }
            .navigationTitle("Daedalus")
            .sheet(isPresented: $showingExportSheet, content: {
                if let url = exportURL {
                    ActivityViewController(activityItems: [url])
                }
            })
        }
        .onAppear {
            // Set up data flow callbacks
            setupDataFlow()
        }
    }
    
    // MARK: - Helpers
    
    private var connectionStateColor: Color {
        switch chestStrapBLE.connectionState {
        case .connected: return .green
        case .connecting, .scanning: return .yellow
        case .error: return .red
        case .disconnected: return .secondary
        }
    }
    
    private func startSession() {
        let subjectId = "sub-001"
        let sessionId = "sess-\(Int(Date().timeIntervalSince1970) % 1000)"
        sessionManager.startSession(subjectId: subjectId, sessionId: sessionId)
        
        // Tell watch to start (via BLE)
        let controlMessage = "session_start|\(subjectId)|\(sessionId)"
        // This would be sent via BLE to the watch
    }
    
    private func endSession() {
        sessionManager.endSession()
        
        // Tell watch to stop (via BLE)
        let controlMessage = "session_end"
        // This would be sent via BLE to the watch
    }
    
    private func logEvent(_ event: EventLabels) {
        let entry = EventEntry(
            epochMs: Int64(Date().timeIntervalSince1970 * 1000),
            eventName: event.rawValue,
            source: "manual"
        )
        sessionManager.logEvent(entry)
        
        // Also send to watch via BLE
        // let eventMessage = "\(event.rawValue)|manual|"
    }
    
    private func setupDataFlow() {
        // Watch IMU data → session manager
        watchBLE.onImuSample = { sample in
            Task { @MainActor in
                sessionManager.logImuSample(sample)
            }
        }
        
        // Watch HR data → session manager
        watchBLE.onHrSample = { sample in
            Task { @MainActor in
                sessionManager.logHrSample(sample)
            }
        }
        
        // Watch events → session manager
        watchBLE.onEvent = { event in
            Task { @MainActor in
                sessionManager.logEvent(event)
            }
        }
        
        // Watch session control
        watchBLE.onSessionStart = { subjectId, sessionId in
            Task { @MainActor in
                sessionManager.startSession(subjectId: subjectId, sessionId: sessionId)
            }
        }
        
        watchBLE.onSessionEnd = {
            Task { @MainActor in
                sessionManager.endSession()
            }
        }
        
        // Chest strap ECG data → session manager
        // We observe the published array
        Task {
            for await samples in chestStrapBLE.$ecgSamples.values {
                if let latest = samples.last {
                    Task { @MainActor in
                        sessionManager.logEcgSample(latest)
                    }
                }
            }
        }
    }
}

// MARK: - Activity View Controller for sharing

struct ActivityViewController: UIViewControllerRepresentable {
    let activityItems: [Any]
    let applicationActivities: [UIActivity]? = nil
    
    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(
            activityItems: activityItems,
            applicationActivities: applicationActivities
        )
        return controller
    }
    
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// MARK: - Preview

#Preview {
    SessionView()
}
