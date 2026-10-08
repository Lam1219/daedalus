//
//  SessionManager.swift
//  DaedalusCompanion
//
//  Manages session lifecycle on the iPhone side.
//  Stores sensor data, events, and exports to CSV/JSON matching daedalus_watch_kit schema.
//

import Foundation
import UniformTypeIdentifiers

@MainActor
class SessionManager: ObservableObject {
    
    static let shared = SessionManager()
    
    @Published var isRunning = false
    @Published var currentSubjectId: String = ""
    @Published var currentSessionId: String = ""
    @Published var sessionDirectory: URL?
    
    private var metadata: SessionMetadata?
    
    // In-memory data buffers
    private var imuSamples: [ImuSample] = []
    private var hrSamples: [HrSample] = []
    private var ecgSamples: [EcgSample] = []
    private var events: [EventEntry] = []
    
    private let fileManager = FileManager.default
    
    /// Start a new session
    func startSession(subjectId: String, sessionId: String) {
        guard !isRunning else { return }
        
        currentSubjectId = subjectId
        currentSessionId = sessionId
        
        metadata = SessionMetadata(
            subjectId: subjectId,
            sessionId: sessionId,
            sessionStartEpochMs: Int64(Date().timeIntervalSince1970 * 1000)
        )
        
        // Create session directory
        let docsDir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let sessionDir = docsDir.appendingPathComponent("daedalus/raw/\(subjectId)/\(sessionId)")
        try? FileManager.default.createDirectory(at: sessionDir, withIntermediateDirectories: true)
        sessionDirectory = sessionDir
        
        // Initialize annotation manager
        ManualAnnotationManager.shared.startSession(sessionDir: sessionDir)
        
        // Clear buffers
        imuSamples = []
        hrSamples = []
        ecgSamples = []
        events = []
        
        // Log start event
        logEvent(EventEntry(
            epochMs: Int64(Date().timeIntervalSince1970 * 1000),
            eventName: EventLabels.sessionStart.rawValue,
            source: "auto"
        ))
        
        isRunning = true
    }
    
    /// Log an event
    func logEvent(_ event: EventEntry) {
        events.append(event)
    }
    
    /// Log an IMU sample from the watch
    func logImuSample(_ sample: ImuSample) {
        guard isRunning else { return }
        imuSamples.append(sample)
    }
    
    /// Log an HR sample from the watch
    func logHrSample(_ sample: HrSample) {
        guard isRunning else { return }
        hrSamples.append(sample)
    }
    
    /// Log an ECG/RR sample from the chest strap
    func logEcgSample(_ sample: EcgSample) {
        guard isRunning else { return }
        ecgSamples.append(sample)
    }

    /// Log an event
    func logEvent(_ event: EventEntry) {
        events.append(event)
    }
    
    /// End the session and prepare for export
    func endSession() {
        guard isRunning else { return }
        
        let endTime = Int64(Date().timeIntervalSince1970 * 1000)
        metadata = metadata?.copyingWith(sessionEndEpochMs: endTime)
        
        // Log end event
        logEvent(EventEntry(
            epochMs: endTime,
            eventName: EventLabels.sessionEnd.rawValue,
            source: "auto"
        ))
        
        // Close annotation file
        ManualAnnotationManager.shared.endSession()
        
        isRunning = false
        sessionDirectory = nil
    }
    
    /// Export session as a zip file for transfer to laptop
    func exportSession() -> URL? {
        guard !imuSamples.isEmpty || !hrSamples.isEmpty || !ecgSamples.isEmpty || !events.isEmpty else {
            return nil
        }
        
        // Create temporary directory for session files
        let tempDir = FileManager.default.temporaryDirectory.appendingPathComponent(
            "daedalus_\(currentSubjectId)_\(currentSessionId)"
        )
        
        // Clean up if exists
        try? FileManager.default.removeItem(at: tempDir)
        try? FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        
        // Write session_metadata.json
        if let json = metadata?.toJson() {
            try? json.write(to: tempDir.appendingPathComponent("session_metadata.json"),
                           atomically: true, encoding: .utf8)
        }
        
        // Write annotations directory (copy from session dir if exists)
        if let sessionDir = sessionDirectory {
            let annotationsSource = sessionDir.appendingPathComponent("annotations")
            let annotationsDest = tempDir.appendingPathComponent("annotations")
            if FileManager.default.fileExists(atPath: annotationsSource.path) {
                try? FileManager.default.copyItem(at: annotationsSource, to: annotationsDest)
            }
        }
        
        // Write watch_imu.csv
        let imuURL = tempDir.appendingPathComponent("watch_imu.csv")
        var imuContent = ImuSample.csvHeader + "\n"
        imuContent += imuSamples.map { $0.csvRow }.joined(separator: "\n") + "\n"
        try? imuContent.write(to: imuURL, atomically: true, encoding: .utf8)
        
        // Write watch_hr.csv
        let hrURL = tempDir.appendingPathComponent("watch_hr.csv")
        var hrContent = HrSample.csvHeader + "\n"
        hrContent += hrSamples.map { $0.csvRow }.joined(separator: "\n") + "\n"
        try? hrContent.write(to: hrURL, atomically: true, encoding: .utf8)
        
        // Write ecg_reference.csv
        let ecgDir = tempDir.appendingPathComponent("ecg")
        try? FileManager.default.createDirectory(at: ecgDir, withIntermediateDirectories: true)
        let ecgURL = ecgDir.appendingPathComponent("ecg_reference.csv")
        var ecgContent = EcgSample.csvHeader + "\n"
        ecgContent += ecgSamples.map { $0.csvRow }.joined(separator: "\n") + "\n"
        try? ecgContent.write(to: ecgURL, atomically: true, encoding: .utf8)
        
        // Write phone_events.csv
        let eventsURL = tempDir.appendingPathComponent("phone_events.csv")
        var eventsContent = EventEntry.csvHeader + "\n"
        eventsContent += events.map { $0.csvRow }.joined(separator: "\n") + "\n"
        try? eventsContent.write(to: eventsURL, atomically: true, encoding: .utf8)
        
        // Create zip file
        let timestamp = ISO8601DateFormatter().string(from: Date())
            .replacingOccurrences(of: ":", with: "-")
        let zipURL = FileManager.default.temporaryDirectory.appendingPathComponent(
            "daedalus_\(currentSubjectId)_\(currentSessionId)_\(timestamp).zip"
        )
        
        // Zip the directory
        createZip(source: tempDir, destination: zipURL)
        
        return zipURL
    }
    
    /// Create a zip file from a directory
    private func createZip(source: URL, destination: URL) {
        // Use the system 'zip' command via Process
        let process = Process()
        process.executableURL = URL(fileURLWithPath: "/usr/bin/zip")
        process.arguments = [
            "-r",
            "-j",  // Junk paths (flat zip)
            destination.path,
            source.path + "/"
        ]
        
        try? process.run()
        process.waitUntilExit()
    }
    
    /// Share the session zip via iOS share sheet
    func getExportActivityItem() -> URL? {
        return exportSession()
    }
}

// MARK: - SessionMetadata extension

extension SessionMetadata {
    func copyingWith(sessionEndEpochMs: Int64) -> SessionMetadata {
        var copy = self
        copy.sessionEndEpochMs = sessionEndEpochMs
        return copy
    }
}
