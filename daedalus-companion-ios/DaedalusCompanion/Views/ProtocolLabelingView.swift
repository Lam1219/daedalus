//
//  ProtocolLabelingView.swift
//  DaedalusCompanion
//
//  Comprehensive event labeling UI for all protocol blocks.
//  Replaces video recording by letting the user tap Start/Stop for each block.
//

import SwiftUI

struct ProtocolLabelingView: View {
    @ObservedObject var sessionManager = SessionManager.shared
    @ObservedObject var annotationManager = ManualAnnotationManager.shared
    
    @State private var activeBlockId: String? = nil
    @State private var activeBlockStartTime: Date? = nil
    @State private var showingToast: String? = nil
    
    var body: some View {
        NavigationView {
            VStack(spacing: 0) {
                // Session status bar
                HStack {
                    if sessionManager.isRunning {
                        Text("Session: \(sessionManager.currentSubjectId)/\(sessionManager.currentSessionId)")
                            .font(.caption)
                            .foregroundColor(.white)
                    } else {
                        Text("No active session")
                            .font(.caption)
                            .foregroundColor(.white.opacity(0.7))
                    }
                    Spacer()
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 8)
                .background(Color.blue)
                
                // Tab view for categories
                TabView {
                    ForEach(BlockCategory.allCases, id: \.self) { category in
                        CategoryView(
                            category: category,
                            blocks: ProtocolBlocks.getByCategory(category),
                            activeBlockId: activeBlockId,
                            activeBlockStartTime: activeBlockStartTime,
                            onStart: startBlock,
                            onStop: stopBlock
                        )
                        .tabItem {
                            Text(category.rawValue)
                        }
                        .tag(category)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
            }
            .navigationTitle("Protocol Labeling")
            .navigationBarTitleDisplayMode(.inline)
        }
        .onAppear {
            // Initialize annotation file when session starts
            if let sessionDir = sessionManager.sessionDirectory {
                annotationManager.startSession(sessionDir: sessionDir)
            }
        }
        .alert(item: Binding(get: { showingToast.map { ToastMessage(text: $0) } }, set: { _ in showingToast = nil })) { toast in
            Alert(title: Text(toast.text))
        }
    }
    
    private func startBlock(_ block: ProtocolBlock) {
        guard activeBlockId == nil else { return }
        
        activeBlockId = block.id
        activeBlockStartTime = Date()
        
        // Log start event
        sessionManager.logEvent(EventEntry(
            epochMs: Int64(Date().timeIntervalSince1970 * 1000),
            eventName: block.startEvent,
            source: "manual",
            notes: "\(block.displayName) - \(block.description)"
        ))
        
        // Log annotation
        annotationManager.logProtocolBlock(block: block, startTimeMs: Int64(Date().timeIntervalSince1970 * 1000))
        
        showingToast = "\(block.displayName) started"
    }
    
    private func stopBlock(_ block: ProtocolBlock) {
        guard activeBlockId == block.id else { return }
        
        let endTime = Date()
        let durationSec = Int(endTime.timeIntervalSince(activeBlockStartTime ?? endTime))
        
        // Log end event
        sessionManager.logEvent(EventEntry(
            epochMs: Int64(endTime.timeIntervalSince1970 * 1000),
            eventName: block.endEvent,
            source: "manual",
            notes: "\(block.displayName) ended"
        ))
        
        // Log annotation with actual duration
        if let startTime = activeBlockStartTime {
            annotationManager.logProtocolBlock(
                block: block,
                startTimeMs: Int64(startTime.timeIntervalSince1970 * 1000),
                endTimeMs: Int64(endTime.timeIntervalSince1970 * 1000)
            )
        }
        
        showingToast = "\(block.displayName) stopped (\(durationSec)s)"
        activeBlockId = nil
        activeBlockStartTime = nil
    }
}

// MARK: - Category View

struct CategoryView: View {
    let category: BlockCategory
    let blocks: [ProtocolBlock]
    let activeBlockId: String?
    let activeBlockStartTime: Date?
    let onStart: (ProtocolBlock) -> Void
    let onStop: (ProtocolBlock) -> Void
    
    var body: some View {
        ScrollView {
            LazyVStack(spacing: 8) {
                ForEach(blocks) { block in
                    ProtocolBlockCard(
                        block: block,
                        isActive: activeBlockId == block.id,
                        anyBlockActive: activeBlockId != nil,
                        activeBlockStartTime: activeBlockStartTime,
                        onStart: { onStart(block) },
                        onStop: { onStop(block) }
                    )
                }
            }
            .padding()
        }
    }
}

// MARK: - Protocol Block Card

struct ProtocolBlockCard: View {
    let block: ProtocolBlock
    let isActive: Bool
    let anyBlockActive: Bool
    let activeBlockStartTime: Date?
    let onStart: () -> Void
    let onStop: () -> Void
    
    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(block.displayName)
                .font(.headline)
            
            Text(block.description)
                .font(.subheadline)
                .foregroundColor(.secondary)
            
            Text(block.targetDurationText)
                .font(.caption)
                .foregroundColor(.blue)
            
            HStack(spacing: 12) {
                Button(action: onStart) {
                    Label("Start", systemImage: "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(isActive || anyBlockActive)
                
                Button(action: onStop) {
                    Label("Stop", systemImage: "stop.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .tint(.red)
                .disabled(!isActive)
            }
            
            if isActive, let startTime = activeBlockStartTime {
                Text("Started at \(startTime, style: .time)")
                    .font(.caption)
                    .foregroundColor(.green)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }
}

// MARK: - Toast helper

struct ToastMessage: Identifiable {
    let id = UUID()
    let text: String
}

// MARK: - Preview

#Preview {
    ProtocolLabelingView()
}
