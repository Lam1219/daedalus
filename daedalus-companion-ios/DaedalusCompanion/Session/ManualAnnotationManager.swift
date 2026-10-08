//
//  ManualAnnotationManager.swift
//  DaedalusCompanion
//
//  Manages manual annotations during a session.
//  Replaces video recording by letting the user log events through the UI.
//  Output: manual_annotations.csv matching daedalus_watch_kit schema.
//

import Foundation

@MainActor
class ManualAnnotationManager: ObservableObject {
    
    static let shared = ManualAnnotationManager()
    
    private var sessionDirectory: URL?
    private var annotations: [String] = []  // CSV rows
    private var isRecording = false
    
    private let csvHeader = "start_epoch_ms,end_epoch_ms,activity,tremor_present,fog_present,stress_level,signal_quality,annotator_id"
    
    /// Initialize the annotation file for a new session
    func startSession(sessionDir: URL) {
        sessionDirectory = sessionDir
        annotations = [csvHeader]
        isRecording = true
    }
    
    /// Log a protocol block start/end event
    func logProtocolBlock(
        block: ProtocolBlock,
        startTimeMs: Int64,
        endTimeMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000),
        annotatorId: String = "user",
        tremorPresent: Bool = false,
        fogPresent: Bool = false,
        stressLevel: Float = 0,
        signalQuality: Float = 1
    ) {
        guard isRecording else { return }
        
        let activityLabel = block.id.replacingOccurrences(of: "block_", with: "").replacingOccurrences(of: "_", with: "")
        
        let row = [
            String(startTimeMs),
            String(endTimeMs),
            activityLabel,
            tremorPresent ? "true" : "false",
            fogPresent ? "true" : "false",
            String(stressLevel),
            String(signalQuality),
            annotatorId,
        ].joined(separator: ",")
        
        annotations.append(row)
    }
    
    /// Log a detailed manual annotation with all fields
    func logAnnotation(
        startEpochMs: Int64,
        endEpochMs: Int64,
        activity: String,
        tremorPresent: Bool = false,
        fogPresent: Bool = false,
        stressLevel: Float = 0,
        signalQuality: Float = 1,
        annotatorId: String = "user"
    ) {
        guard isRecording else { return }
        
        let row = [
            String(startEpochMs),
            String(endEpochMs),
            activity,
            tremorPresent ? "true" : "false",
            fogPresent ? "true" : "false",
            String(stressLevel),
            String(signalQuality),
            annotatorId,
        ].joined(separator: ",")
        
        annotations.append(row)
    }
    
    /// End annotation recording and write to file
    func endSession() {
        guard let sessionDir = sessionDirectory, isRecording else { return }
        
        let annotationsDir = sessionDir.appendingPathComponent("annotations")
        try? FileManager.default.createDirectory(at: annotationsDir, withIntermediateDirectories: true)
        
        let fileURL = annotationsDir.appendingPathComponent("manual_annotations.csv")
        let content = annotations.joined(separator: "\n") + "\n"
        
        try? content.write(to: fileURL, atomically: true, encoding: .utf8)
        
        isRecording = false
        annotations = []
    }
}
