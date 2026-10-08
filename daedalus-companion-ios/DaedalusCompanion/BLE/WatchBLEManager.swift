//
//  WatchBLEManager.swift
//  DaedalusCompanion
//
//  BLE Peripheral Manager — receives sensor data FROM the Samsung Galaxy Watch5 Pro.
//
//  The watch acts as a BLE Central and sends IMU + HR data to this peripheral.
//  Uses custom GATT service with characteristics for IMU, HR, haptics, and events.
//

import Foundation
import CoreBluetooth

/// Custom service UUIDs for Daedalus watch-to-phone communication
enum DaedalusService {
    static let serviceUUID = CBUUID(string: "A0000000-0000-0000-0000-000000000001")
    static let imuCharacteristicUUID = CBUUID(string: "A0000001-0000-0000-0000-000000000001")
    static let hrCharacteristicUUID = CBUUID(string: "A0000002-0000-0000-0000-000000000001")
    static let hapticsCharacteristicUUID = CBUUID(string: "A0000003-0000-0000-0000-000000000001")
    static let eventCharacteristicUUID = CBUUID(string: "A0000004-0000-0000-0000-000000000001")
    static let controlCharacteristicUUID = CBUUID(string: "A0000005-0000-0000-0000-000000000001")
}

/// Manages the BLE peripheral that receives data from the Samsung watch
class WatchBLEManager: NSObject, ObservableObject {
    
    private var peripheralManager: CBPeripheralManager?
    private var characteristics: [CBUUID: CBMutableCharacteristic] = [:]
    
    @Published var isAdvertising = false
    @Published var connectedCentralCount = 0
    @Published var lastImuSample: ImuSample?
    @Published var lastHrSample: HrSample?
    @Published var connectionStatus: String = "Not started"
    
    // Callbacks for received data
    var onImuSample: ((ImuSample) -> Void)?
    var onHrSample: ((HrSample) -> Void)?
    var onEvent: ((EventEntry) -> Void)?
    var onSessionStart: ((String, String) -> Void)?  // subjectId, sessionId
    var onSessionEnd: (() -> Void)?
    
    override init() {
        super.init()
        peripheralManager = CBPeripheralManager(delegate: self, queue: nil)
    }
    
    deinit {
        peripheralManager?.stopAdvertising()
    }
    
    /// Start advertising as a BLE peripheral (watch connects to this)
    func startAdvertising() {
        guard let manager = peripheralManager, manager.state == .poweredOn else {
            connectionStatus = "Bluetooth not ready"
            return
        }
        
        // Create characteristics
        let imuChar = CBMutableCharacteristic(
            type: DaedalusService.imuCharacteristicUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        
        let hrChar = CBMutableCharacteristic(
            type: DaedalusService.hrCharacteristicUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        
        let hapticsChar = CBMutableCharacteristic(
            type: DaedalusService.hapticsCharacteristicUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        
        let eventChar = CBMutableCharacteristic(
            type: DaedalusService.eventCharacteristicUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        
        let controlChar = CBMutableCharacteristic(
            type: DaedalusService.controlCharacteristicUUID,
            properties: [.write, .writeWithoutResponse],
            value: nil,
            permissions: [.writeable]
        )
        
        characteristics = [
            DaedalusService.imuCharacteristicUUID: imuChar,
            DaedalusService.hrCharacteristicUUID: hrChar,
            DaedalusService.hapticsCharacteristicUUID: hapticsChar,
            DaedalusService.eventCharacteristicUUID: eventChar,
            DaedalusService.controlCharacteristicUUID: controlChar,
        ]
        
        // Create service
        let service = CBMutableService(type: DaedalusService.serviceUUID, primary: true)
        service.characteristics = Array(characteristics.values)
        
        // Add service and start advertising
        manager.add(service)
        manager.startAdvertising([
            DaedalusService.serviceUUID,
            CBUUID(string: "180D")  // Heart Rate (for discovery)
        ])
        
        connectionStatus = "Advertising..."
    }
    
    /// Stop advertising
    func stopAdvertising() {
        peripheralManager?.stopAdvertising()
        isAdvertising = false
        connectionStatus = "Stopped"
    }
}

// MARK: - CBPeripheralManagerDelegate

extension WatchBLEManager: CBPeripheralManagerDelegate {
    
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        switch peripheral.state {
        case .poweredOn:
            connectionStatus = "Bluetooth ready"
            startAdvertising()
        case .poweredOff:
            connectionStatus = "Bluetooth off"
            stopAdvertising()
        case .unauthorized:
            connectionStatus = "Bluetooth unauthorized"
        case .unsupported:
            connectionStatus = "Bluetooth unsupported"
        default:
            connectionStatus = "Bluetooth state: \(peripheral.state.rawValue)"
        }
    }
    
    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        didReceiveWrite requests: [CBATTRequest]
    ) {
        for request in requests {
            guard let data = request.value,
                  let characteristic = request.characteristic as? CBMutableCharacteristic,
                  let uuid = characteristics.first(where: { $0.value === characteristic })?.key
            else { continue }
            
            let message = String(data: data, encoding: .utf8) ?? ""
            
            switch uuid {
            case DaedalusService.imuCharacteristicUUID:
                parseImuData(message)
            case DaedalusService.hrCharacteristicUUID:
                parseHrData(message)
            case DaedalusService.eventCharacteristicUUID:
                parseEventData(message)
            case DaedalusService.controlCharacteristicUUID:
                parseControlData(message)
            default:
                break
            }
        }
    }
    
    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeTo characteristic: CBCharacteristic
    ) {
        connectedCentralCount += 1
        connectionStatus = "Connected: \(connectedCentralCount) device(s)"
    }
    
    func peripheralManager(
        _ peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFrom characteristic: CBCharacteristic
    ) {
        connectedCentralCount = max(0, connectedCentralCount - 1)
        connectionStatus = "Connected: \(connectedCentralCount) device(s)"
    }
    
    // MARK: - Data Parsing
    
    private func parseImuData(_ message: String) {
        // Format: epoch_ms,elapsed_ms,ax,ay,az,gx,gy,gz,motion_intensity
        let parts = message.components(separatedBy: ",")
        guard parts.count >= 9,
              let epochMs = Int64(parts[0]),
              let elapsedMs = Int64(parts[1]),
              let ax = Float(parts[2]),
              let ay = Float(parts[3]),
              let az = Float(parts[4]),
              let gx = Float(parts[5]),
              let gy = Float(parts[6]),
              let gz = Float(parts[7]),
              let motionIntensity = Float(parts[8])
        else { return }
        
        let sample = ImuSample(
            epochMs: epochMs,
            elapsedMs: elapsedMs,
            ax: ax, ay: ay, az: az,
            gx: gx, gy: gy, gz: gz,
            motionIntensity: motionIntensity
        )
        
        DispatchQueue.main.async {
            self.lastImuSample = sample
        }
        onImuSample?(sample)
    }
    
    private func parseHrData(_ message: String) {
        // Format: epoch_ms,hr_bpm,hrv_rmssd_ms,hr_quality,source
        let parts = message.components(separatedBy: ",")
        guard parts.count >= 2,
              let epochMs = Int64(parts[0]),
              let hrBpm = Float(parts[1])
        else { return }
        
        let hrvRmssdMs = parts.count > 2 && !parts[2].isEmpty ? Float(parts[2]) : nil
        let hrQuality = parts.count > 3 && !parts[3].isEmpty ? Float(parts[3]) : nil
        let source = parts.count > 4 ? parts[4] : "sensor"
        
        let sample = HrSample(
            epochMs: epochMs,
            hrBpm: hrBpm,
            hrvRmssdMs: hrvRmssdMs,
            hrQuality: hrQuality,
            source: source
        )
        
        DispatchQueue.main.async {
            self.lastHrSample = sample
        }
        onHrSample?(sample)
    }
    
    private func parseEventData(_ message: String) {
        // Format: event_name|source|notes
        let parts = message.components(separatedBy: "|")
        guard let eventName = parts.first else { return }
        
        let entry = EventEntry(
            epochMs: Int64(Date().timeIntervalSince1970 * 1000),
            eventName: eventName,
            source: parts.count > 1 ? parts[1] : "watch",
            notes: parts.count > 2 ? parts[2] : ""
        )
        onEvent?(entry)
    }
    
    private func parseControlData(_ message: String) {
        // Format: command|param1|param2
        let parts = message.components(separatedBy: "|")
        guard let command = parts.first else { return }
        
        switch command {
        case "session_start":
            let subjectId = parts.count > 1 ? parts[1] : "sub-001"
            let sessionId = parts.count > 2 ? parts[2] : "sess-001"
            onSessionStart?(subjectId, sessionId)
        case "session_end":
            onSessionEnd?()
        default:
            break
        }
    }
}
