//
//  ChestStrapBLEManager.swift
//  DaedalusCompanion
//
//  BLE Central Manager — connects TO the Cycplus H2 Heart Rate chest strap.
//
//  Uses the standard Bluetooth Heart Rate Service (UUID: 0x180D) with the
//  Heart Rate Measurement characteristic (UUID: 0x2A37).
//
//  Parses the BLE Heart Rate Measurement format per Bluetooth SIG spec:
//  - Byte 0: Flags (bit 0 = RR interval present)
//  - Byte 1-2: Heart Rate (UINT8 or UINT16)
//  - Byte 3+: RR Interval (UINT16, in 1/1024 second units)
//

import Foundation
import CoreBluetooth

enum ChestStrapConnectionState: String {
    case disconnected = "Disconnected"
    case scanning = "Scanning..."
    case connecting = "Connecting..."
    case connected = "Connected"
    case error = "Error"
}

class ChestStrapBLEManager: NSObject, ObservableObject {
    
    private var centralManager: CBCentralManager?
    private var peripheral: CBPeripheral?
    private var hrCharacteristic: CBCharacteristic?
    
    @Published var connectionState: ChestStrapConnectionState = .disconnected
    @Published var ecgSamples: [EcgSample] = []
    @Published var latestHrBpm: Float?
    @Published var latestRrMs: Float?
    
    private var accumulatedSamples: [EcgSample] = []
    
    // Heart Rate Service UUIDs (Bluetooth SIG standard)
    private let hrServiceUUID = CBUUID(string: "180D")
    private let hrMeasurementUUID = CBUUID(string: "2A37")
    
    // Known device names to look for
    private let knownDeviceNames = ["Cycplus", "H2", "Heart Rate", "Polar", "HR Monitor"]
    
    override init() {
        super.init()
        centralManager = CBCentralManager(delegate: self, queue: nil)
    }
    
    deinit {
        disconnect()
    }
    
    /// Start scanning for the Cycplus H2 chest strap
    func scanAndConnect() {
        guard let central = centralManager, central.state == .poweredOn else {
            connectionState = .error
            return
        }
        
        connectionState = .scanning
        
        // Scan for Heart Rate Service
        central.scanForPeripherals(
            withServices: [hrServiceUUID],
            options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
        )
        
        // Auto-stop scan after 10 seconds
        DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak self] in
            self?.centralManager?.stopScan()
            if self?.connectionState == .scanning {
                self?.connectionState = .disconnected
            }
        }
    }
    
    /// Disconnect from the chest strap
    func disconnect() {
        centralManager?.stopScan()
        peripheral?.delegate = nil
        centralManager?.cancelPeripheralConnection(peripheral ?? CBPeripheral())
        peripheral = nil
        connectionState = .disconnected
    }
    
    /// Clear accumulated samples
    func clearSamples() {
        accumulatedSamples.removeAll()
        ecgSamples = []
    }
    
    /// Get accumulated samples for export
    func getSamples() -> [EcgSample] {
        return accumulatedSamples
    }
}

// MARK: - CBCentralManagerDelegate

extension ChestStrapBLEManager: CBCentralManagerDelegate {
    
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:
            // Bluetooth is ready, but don't auto-scan — wait for user action
            break
        case .poweredOff:
            connectionState = .disconnected
        case .unauthorized:
            connectionState = .error
        case .unsupported:
            connectionState = .error
        default:
            break
        }
    }
    
    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        // Check if this is a known heart rate device
        let name = peripheral.name ?? ""
        let isKnown = knownDeviceNames.contains { name.contains($0) }
        
        guard isKnown else { return }
        
        centralManager?.stopScan()
        self.peripheral = peripheral
        peripheral.delegate = self
        connectionState = .connecting
        
        central.connect(peripheral, options: nil)
    }
    
    func centralManager(
        _ central: CBCentralManager,
        didConnect peripheral: CBPeripheral
    ) {
        connectionState = .connected
        peripheral.discoverServices([hrServiceUUID])
    }
    
    func centralManager(
        _ central: CBCentralManager,
        didDisconnectPeripheral peripheral: CBPeripheral,
        error: (any Error)?
    ) {
        connectionState = .disconnected
        self.peripheral = nil
    }
    
    func centralManager(
        _ central: CBCentralManager,
        didFailToConnect peripheral: CBPeripheral,
        error: (any Error)?
    ) {
        connectionState = .error
    }
}

// MARK: - CBPeripheralDelegate

extension ChestStrapBLEManager: CBPeripheralDelegate {
    
    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverServices error: (any Error)?
    ) {
        guard let services = peripheral.services else { return }
        
        for service in services where service.uuid == hrServiceUUID {
            peripheral.discoverCharacteristics([hrMeasurementUUID], for: service)
        }
    }
    
    func peripheral(
        _ peripheral: CBPeripheral,
        didDiscoverCharacteristicsFor service: CBService,
        error: (any Error)?
    ) {
        guard service.uuid == hrServiceUUID,
              let characteristics = service.characteristics
        else { return }
        
        for characteristic in characteristics where characteristic.uuid == hrMeasurementUUID {
            hrCharacteristic = characteristic
            peripheral.setNotifyValue(true, for: characteristic)
        }
    }
    
    func peripheral(
        _ peripheral: CBPeripheral,
        didUpdateValueFor characteristic: CBCharacteristic,
        error: (any Error)?
    ) {
        guard characteristic.uuid == hrMeasurementUUID,
              let data = characteristic.value
        else { return }
        
        parseHeartRateMeasurement(data)
    }
    
    /// Parse BLE Heart Rate Measurement characteristic value
    private func parseHeartRateMeasurement(_ data: Data) {
        guard !data.isEmpty else { return }
        
        let flags = data[0]
        let rrPresent = (flags & 0x01) == 1
        let hrFormatUint16 = (flags & 0x02) != 0
        
        var offset = 1
        
        // Parse heart rate
        var hrBpm: Float = 0
        if hrFormatUint16 {
            guard offset + 1 < data.count else { return }
            let hr = UInt16(data[offset]) | (UInt16(data[offset + 1]) << 8)
            hrBpm = Float(hr)
            offset += 2
        } else {
            guard offset < data.count else { return }
            hrBpm = Float(data[offset])
            offset += 1
        }
        
        DispatchQueue.main.async {
            self.latestHrBpm = hrBpm
        }
        
        // Skip energy expended (bit 3)
        if (flags & 0x08) != 0 { offset += 2 }
        
        // Skip sensor contact (bit 4)
        if (flags & 0x10) != 0 { offset += 1 }
        
        // Parse RR intervals
        if rrPresent {
            while offset + 1 < data.count {
                let rrRaw = UInt16(data[offset]) | (UInt16(data[offset + 1]) << 8)
                offset += 2
                
                let rrMs = Float(rrRaw) / 1024.0  // Convert from 1/1024s to ms
                
                if rrMs > 0 {
                    let sample = EcgSample(
                        epochMs: Int64(Date().timeIntervalSince1970 * 1000),
                        rrMs: rrMs,
                        hrBpm: hrBpm,
                        beatQuality: 1.0
                    )
                    
                    accumulatedSamples.append(sample)
                    
                    DispatchQueue.main.async {
                        self.latestRrMs = rrMs
                        self.ecgSamples = Array(self.accumulatedSamples.suffix(10))
                    }
                }
            }
        } else {
            // No RR interval — just log HR
            let sample = EcgSample(
                epochMs: Int64(Date().timeIntervalSince1970 * 1000),
                rrMs: nil,
                hrBpm: hrBpm,
                beatQuality: 0.5
            )
            accumulatedSamples.append(sample)
            
            DispatchQueue.main.async {
                self.ecgSamples = Array(self.accumulatedSamples.suffix(10))
            }
        }
    }
}
