import Foundation
import CoreBluetooth
import CryptoKit

@objc(BleMesh)
class BleMesh: RCTEventEmitter {

    // MARK: - Constants

    #if DEBUG
    static let serviceUUID = CBUUID(string: "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5A")
    #else
    static let serviceUUID = CBUUID(string: "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C")
    #endif
    static let characteristicUUID = CBUUID(string: "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D")

    private let messageTTL: UInt8 = 7

    // MARK: - BLE Objects

    private var centralManager: CBCentralManager?
    private var peripheralManager: CBPeripheralManager?
    private var characteristic: CBMutableCharacteristic?

    // MARK: - State

    private var isRunning = false
    private var myNickname: String = "anon"
    private var myPeerID: String = ""
    private var myPeerIDData: Data = Data()

    // Peer tracking
    private struct PeerInfo {
        let peerId: String
        var nickname: String
        var isConnected: Bool
        var rssi: Int?
        var lastSeen: Date
        var noisePublicKey: Data?
        var isVerified: Bool
    }
    private var peers: [String: PeerInfo] = [:]
    private var peripherals: [String: CBPeripheral] = [:]
    private var peripheralToPeer: [String: String] = [:]
    private var subscribedCentrals: [CBCentral] = []

    // Encryption
    private var privateKey: Curve25519.KeyAgreement.PrivateKey?
    private var signingKey: Curve25519.Signing.PrivateKey?
    private var sessions: [String: Data] = [:]

    // Message deduplication
    private var processedMessages: Set<String> = []
    
    // File transfer tracking
    private struct FileTransfer {
        let id: String
        let fileName: String
        let fileSize: Int
        let mimeType: String
        let senderPeerId: String
        let totalChunks: Int
        var receivedChunks: Set<Int> = []
    }
    private var activeFileTransfers: [String: FileTransfer] = [:]
    private var fileTransferFragments: [String: [Int: Data]] = [:]
    private let fileFragmentSize = 180 // Max payload size for fragments

    // Queues
    private let bleQueue = DispatchQueue(label: "mesh.bluetooth", qos: .userInitiated)
    private let messageQueue = DispatchQueue(label: "mesh.message", attributes: .concurrent)

    // MARK: - RCTEventEmitter

    override init() {
        super.init()
        generateIdentity()
    }

    override static func moduleName() -> String! {
        return "BleMesh"
    }

    override func supportedEvents() -> [String]! {
        return [
            "onPeerListUpdated",
            "onMessageReceived",
            "onFileReceived",
            "onTransactionReceived",
            "onTransactionResponse",
            "onConnectionStateChanged",
            "onReadReceipt",
            "onDeliveryAck",
            "onError"
        ]
    }

    override static func requiresMainQueueSetup() -> Bool {
        return false
    }

    // MARK: - Identity

    private func generateIdentity() {
        // Generate or load keys
        if let savedPrivateKey = loadKey(forKey: "mesh.privateKey") {
            privateKey = try? Curve25519.KeyAgreement.PrivateKey(rawRepresentation: savedPrivateKey)
        }
        if privateKey == nil {
            privateKey = Curve25519.KeyAgreement.PrivateKey()
            if let pk = privateKey {
                saveKey(pk.rawRepresentation, forKey: "mesh.privateKey")
            }
        }

        if let savedSigningKey = loadKey(forKey: "mesh.signingKey") {
            signingKey = try? Curve25519.Signing.PrivateKey(rawRepresentation: savedSigningKey)
        }
        if signingKey == nil {
            signingKey = Curve25519.Signing.PrivateKey()
            if let sk = signingKey {
                saveKey(sk.rawRepresentation, forKey: "mesh.signingKey")
            }
        }

        // Generate peer ID from public key fingerprint (first 16 hex chars of SHA256)
        if let pk = privateKey {
            let fingerprint = SHA256.hash(data: pk.publicKey.rawRepresentation)
            myPeerID = fingerprint.prefix(8).map { String(format: "%02x", $0) }.joined()
            myPeerIDData = Data(fingerprint.prefix(8))
        }
    }

    private func loadKey(forKey key: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.blemesh",
            kSecAttrAccount as String: key,
            kSecReturnData as String: true
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        if status == errSecSuccess {
            return result as? Data
        }
        return nil
    }

    private func saveKey(_ data: Data, forKey key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "com.blemesh",
            kSecAttrAccount as String: key,
            kSecValueData as String: data
        ]

        SecItemDelete(query as CFDictionary)
        SecItemAdd(query as CFDictionary, nil)
    }

    // MARK: - React Native API

    @objc(requestPermissions:rejecter:)
    func requestPermissions(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        // On iOS, BLE permissions are requested when CBCentralManager is initialized
        // We need to check the current state
        let tempManager = CBCentralManager(delegate: nil, queue: nil, options: [CBCentralManagerOptionShowPowerAlertKey: false])

        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            let bluetoothGranted = tempManager.state != .unauthorized
            resolve([
                "bluetooth": bluetoothGranted,
                "location": true // iOS doesn't require location for BLE
            ])
        }
    }

    @objc(checkPermissions:rejecter:)
    func checkPermissions(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        let state = centralManager?.state ?? .unknown
        let bluetoothGranted = state != .unauthorized

        resolve([
            "bluetooth": bluetoothGranted,
            "location": true
        ])
    }

    @objc(start:resolver:rejecter:)
    func start(_ nickname: String, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        myNickname = nickname

        bleQueue.async { [weak self] in
            guard let self = self else { return }

            self.centralManager = CBCentralManager(delegate: self, queue: self.bleQueue)
            self.peripheralManager = CBPeripheralManager(delegate: self, queue: self.bleQueue)

            self.isRunning = true

            DispatchQueue.main.async {
                resolve(nil)
            }
        }
    }

    @objc(stop:rejecter:)
    func stop(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        bleQueue.async { [weak self] in
            guard let self = self else { return }

            // Send leave announcement
            self.sendLeaveAnnouncement()

            // Stop scanning and advertising
            self.centralManager?.stopScan()
            self.peripheralManager?.stopAdvertising()

            // Disconnect all peripherals
            for (_, peripheral) in self.peripherals {
                self.centralManager?.cancelPeripheralConnection(peripheral)
            }

            self.isRunning = false
            self.peers.removeAll()
            self.peripherals.removeAll()
            self.peripheralToPeer.removeAll()
            self.subscribedCentrals.removeAll()

            DispatchQueue.main.async {
                resolve(nil)
            }
        }
    }

    @objc(setNickname:resolver:rejecter:)
    func setNickname(_ nickname: String, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        myNickname = nickname
        sendAnnounce()
        resolve(nil)
    }

    @objc(getMyPeerId:rejecter:)
    func getMyPeerId(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(myPeerID)
    }

    @objc(getMyNickname:rejecter:)
    func getMyNickname(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(myNickname)
    }

    @objc(getPeers:rejecter:)
    func getPeers(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        let peerList = peers.values.map { peer in
            return [
                "peerId": peer.peerId,
                "nickname": peer.nickname,
                "isConnected": peer.isConnected,
                "rssi": peer.rssi ?? NSNull(),
                "lastSeen": Int(peer.lastSeen.timeIntervalSince1970 * 1000),
                "isVerified": peer.isVerified
            ] as [String: Any]
        }
        resolve(peerList)
    }

    @objc(sendMessage:channel:resolver:rejecter:)
    func sendMessage(_ content: String, channel: String?, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        let messageId = UUID().uuidString

        messageQueue.async { [weak self] in
            guard let self = self else { return }

            let packet = self.createPacket(
                type: MessageType.message.rawValue,
                payload: Data(content.utf8),
                recipientID: nil
            )

            self.broadcastPacket(packet)

            DispatchQueue.main.async {
                resolve(messageId)
            }
        }
    }

    @objc(sendPrivateMessage:recipientPeerId:resolver:rejecter:)
    func sendPrivateMessage(_ content: String, recipientPeerId: String, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        let messageId = UUID().uuidString

        messageQueue.async { [weak self] in
            guard let self = self else { return }

            // Check if we have an encryption session
            if self.sessions[recipientPeerId] != nil {
                // Encrypt the message
                if let encrypted = self.encryptMessage(content, for: recipientPeerId) {
                    let packet = self.createPacket(
                        type: MessageType.noiseEncrypted.rawValue,
                        payload: encrypted,
                        recipientID: Data(hexString: recipientPeerId)
                    )
                    self.broadcastPacket(packet)
                }
            } else {
                // Initiate handshake first
                self.initiateHandshakeInternal(with: recipientPeerId)
            }

            DispatchQueue.main.async {
                resolve(messageId)
            }
        }
    }

    @objc(sendFile:recipientPeerId:channel:resolver:rejecter:)
    func sendFile(_ filePath: String, recipientPeerId: String?, channel: String?, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        let transferId = UUID().uuidString

        guard let fileData = FileManager.default.contents(atPath: filePath) else {
            reject("FILE_ERROR", "Could not read file at path: \(filePath)", nil)
            return
        }

        let fileName = (filePath as NSString).lastPathComponent
        let mimeType = getMimeType(for: fileName)
        let totalChunks = (fileData.count + fileFragmentSize - 1) / fileFragmentSize

        messageQueue.async { [weak self] in
            guard let self = self else { return }
            
            // Build and send metadata packet
            let metadataPayload = self.buildFileTransferMetadata(
                transferId: transferId,
                fileName: fileName,
                fileSize: fileData.count,
                mimeType: mimeType,
                totalChunks: totalChunks
            )
            
            let metadataPacket = self.createPacket(
                type: MessageType.fileTransfer.rawValue,
                payload: metadataPayload,
                recipientID: recipientPeerId != nil ? Data(hexString: recipientPeerId!) : nil
            )
            self.broadcastPacket(metadataPacket)
            
            // Send file fragments
            for chunkIndex in 0..<totalChunks {
                let start = chunkIndex * self.fileFragmentSize
                let end = min(start + self.fileFragmentSize, fileData.count)
                let chunkData = fileData.subdata(in: start..<end)
                
                let fragmentPayload = self.buildFileFragment(
                    transferId: transferId,
                    chunkIndex: chunkIndex,
                    totalChunks: totalChunks,
                    chunkData: chunkData
                )
                
                let fragmentPacket = self.createPacket(
                    type: MessageType.fragment.rawValue,
                    payload: fragmentPayload,
                    recipientID: recipientPeerId != nil ? Data(hexString: recipientPeerId!) : nil
                )
                self.broadcastPacket(fragmentPacket)
                
                // Small delay to avoid overwhelming the BLE stack
                Thread.sleep(forTimeInterval: 0.05)
            }
            
            DispatchQueue.main.async {
                resolve(transferId)
            }
        }
    }
    
    private func buildFileTransferMetadata(transferId: String, fileName: String, fileSize: Int, mimeType: String, totalChunks: Int) -> Data {
        var payload = Data()
        
        // Transfer ID TLV (tag 0x01)
        let transferIdData = Data(transferId.utf8)
        payload.append(0x01)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(transferIdData.count).bigEndian) { Array($0) })
        payload.append(transferIdData)
        
        // File name TLV (tag 0x02)
        let fileNameData = Data(fileName.utf8)
        payload.append(0x02)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(fileNameData.count).bigEndian) { Array($0) })
        payload.append(fileNameData)
        
        // File size TLV (tag 0x03) - 4 bytes
        payload.append(0x03)
        payload.append(0x00)
        payload.append(0x04)
        payload.append(contentsOf: withUnsafeBytes(of: UInt32(fileSize).bigEndian) { Array($0) })
        
        // MIME type TLV (tag 0x04)
        let mimeTypeData = Data(mimeType.utf8)
        payload.append(0x04)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(mimeTypeData.count).bigEndian) { Array($0) })
        payload.append(mimeTypeData)
        
        // Total chunks TLV (tag 0x05) - 4 bytes
        payload.append(0x05)
        payload.append(0x00)
        payload.append(0x04)
        payload.append(contentsOf: withUnsafeBytes(of: UInt32(totalChunks).bigEndian) { Array($0) })
        
        return payload
    }
    
    private func buildFileFragment(transferId: String, chunkIndex: Int, totalChunks: Int, chunkData: Data) -> Data {
        var payload = Data()
        
        // Transfer ID TLV (tag 0x01)
        let transferIdData = Data(transferId.utf8)
        payload.append(0x01)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(transferIdData.count).bigEndian) { Array($0) })
        payload.append(transferIdData)
        
        // Chunk index TLV (tag 0x02) - 4 bytes
        payload.append(0x02)
        payload.append(0x00)
        payload.append(0x04)
        payload.append(contentsOf: withUnsafeBytes(of: UInt32(chunkIndex).bigEndian) { Array($0) })
        
        // Total chunks TLV (tag 0x03) - 4 bytes
        payload.append(0x03)
        payload.append(0x00)
        payload.append(0x04)
        payload.append(contentsOf: withUnsafeBytes(of: UInt32(totalChunks).bigEndian) { Array($0) })
        
        // Chunk data TLV (tag 0x04)
        payload.append(0x04)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(chunkData.count).bigEndian) { Array($0) })
        payload.append(chunkData)
        
        return payload
    }
    
    @objc(sendTransaction:serializedTransaction:recipientPeerId:firstSignerPublicKey:secondSignerPublicKey:description:resolver:rejecter:)
    func sendTransaction(
        _ txId: String,
        serializedTransaction: String,
        recipientPeerId: String,
        firstSignerPublicKey: String,
        secondSignerPublicKey: String,
        description: String?,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        messageQueue.async { [weak self] in
            guard let self = self else { return }
            
            // Build TLV payload for Solana transaction
            let payload = self.buildSolanaTransactionPayload(
                txId: txId,
                serializedTransaction: serializedTransaction,
                firstSignerPublicKey: firstSignerPublicKey,
                secondSignerPublicKey: secondSignerPublicKey,
                description: description
            )
            
            // Send as encrypted message to recipient
            if self.sessions[recipientPeerId] != nil {
                var encryptedPayload = Data([NoisePayloadType.solanaTransaction.rawValue])
                encryptedPayload.append(payload)
                
                if let encrypted = self.encryptPayload(encryptedPayload, for: recipientPeerId) {
                    let packet = self.createPacket(
                        type: MessageType.noiseEncrypted.rawValue,
                        payload: encrypted,
                        recipientID: Data(hexString: recipientPeerId)
                    )
                    self.broadcastPacket(packet)
                }
            } else {
                // No session, initiate handshake first
                self.initiateHandshakeInternal(with: recipientPeerId)
            }
            
            DispatchQueue.main.async {
                resolve(txId)
            }
        }
    }
    
    private func buildSolanaTransactionPayload(
        txId: String,
        serializedTransaction: String,
        firstSignerPublicKey: String,
        secondSignerPublicKey: String,
        description: String?
    ) -> Data {
        var payload = Data()
        
        // Transaction ID TLV (tag 0x01)
        let txIdData = Data(txId.utf8)
        payload.append(0x01)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(txIdData.count).bigEndian) { Array($0) })
        payload.append(txIdData)
        
        // Serialized transaction TLV (tag 0x02)
        let txData = Data(serializedTransaction.utf8)
        payload.append(0x02)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(txData.count).bigEndian) { Array($0) })
        payload.append(txData)
        
        // First signer public key TLV (tag 0x03)
        let firstSignerData = Data(firstSignerPublicKey.utf8)
        payload.append(0x03)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(firstSignerData.count).bigEndian) { Array($0) })
        payload.append(firstSignerData)
        
        // Second signer public key TLV (tag 0x04)
        let secondSignerData = Data(secondSignerPublicKey.utf8)
        payload.append(0x04)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(secondSignerData.count).bigEndian) { Array($0) })
        payload.append(secondSignerData)
        
        // Description TLV (tag 0x05) - optional
        if let desc = description, !desc.isEmpty {
            let descData = Data(desc.utf8)
            payload.append(0x05)
            payload.append(contentsOf: withUnsafeBytes(of: UInt16(descData.count).bigEndian) { Array($0) })
            payload.append(descData)
        }
        
        return payload
    }
    
    @objc(respondToTransaction:recipientPeerId:signedTransaction:error:resolver:rejecter:)
    func respondToTransaction(
        _ transactionId: String,
        recipientPeerId: String,
        signedTransaction: String?,
        error: String?,
        resolver resolve: @escaping RCTPromiseResolveBlock,
        rejecter reject: @escaping RCTPromiseRejectBlock
    ) {
        messageQueue.async { [weak self] in
            guard let self = self else { return }
            
            // Build TLV payload for response
            var payload = Data()
            
            // Transaction ID TLV (tag 0x01)
            let txIdData = Data(transactionId.utf8)
            payload.append(0x01)
            payload.append(contentsOf: withUnsafeBytes(of: UInt16(txIdData.count).bigEndian) { Array($0) })
            payload.append(txIdData)
            
            // Signed transaction TLV (tag 0x02) - optional
            if let signedTx = signedTransaction, !signedTx.isEmpty {
                let signedTxData = Data(signedTx.utf8)
                payload.append(0x02)
                payload.append(contentsOf: withUnsafeBytes(of: UInt16(signedTxData.count).bigEndian) { Array($0) })
                payload.append(signedTxData)
            }
            
            // Error TLV (tag 0x03) - optional
            if let err = error, !err.isEmpty {
                let errData = Data(err.utf8)
                payload.append(0x03)
                payload.append(contentsOf: withUnsafeBytes(of: UInt16(errData.count).bigEndian) { Array($0) })
                payload.append(errData)
            }
            
            var encryptedPayload = Data([NoisePayloadType.transactionResponse.rawValue])
            encryptedPayload.append(payload)
            
            // Send encrypted response
            if let encrypted = self.encryptPayload(encryptedPayload, for: recipientPeerId) {
                let packet = self.createPacket(
                    type: MessageType.noiseEncrypted.rawValue,
                    payload: encrypted,
                    recipientID: Data(hexString: recipientPeerId)
                )
                self.broadcastPacket(packet)
            }
            
            DispatchQueue.main.async {
                resolve(nil)
            }
        }
    }

    @objc(sendReadReceipt:recipientPeerId:resolver:rejecter:)
    func sendReadReceipt(_ messageId: String, recipientPeerId: String, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        messageQueue.async { [weak self] in
            guard let self = self else { return }

            // Create read receipt payload
            var payload = Data([NoisePayloadType.readReceipt.rawValue])
            payload.append(contentsOf: messageId.utf8)

            if let encrypted = self.encryptPayload(payload, for: recipientPeerId) {
                let packet = self.createPacket(
                    type: MessageType.noiseEncrypted.rawValue,
                    payload: encrypted,
                    recipientID: Data(hexString: recipientPeerId)
                )
                self.broadcastPacket(packet)
            }

            DispatchQueue.main.async {
                resolve(nil)
            }
        }
    }

    @objc(hasEncryptedSession:resolver:rejecter:)
    func hasEncryptedSession(_ peerId: String, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        resolve(sessions[peerId] != nil)
    }

    @objc(initiateHandshake:resolver:rejecter:)
    func initiateHandshake(_ peerId: String, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        initiateHandshakeInternal(with: peerId)
        resolve(nil)
    }

    @objc(getIdentityFingerprint:rejecter:)
    func getIdentityFingerprint(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        if let pk = privateKey {
            let fingerprint = SHA256.hash(data: pk.publicKey.rawRepresentation)
            let fingerprintHex = fingerprint.map { String(format: "%02x", $0) }.joined()
            resolve(fingerprintHex)
        } else {
            resolve(nil)
        }
    }

    @objc(getPeerFingerprint:resolver:rejecter:)
    func getPeerFingerprint(_ peerId: String, resolver resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        if let peer = peers[peerId], let publicKey = peer.noisePublicKey {
            let fingerprint = SHA256.hash(data: publicKey)
            let fingerprintHex = fingerprint.map { String(format: "%02x", $0) }.joined()
            resolve(fingerprintHex)
        } else {
            resolve(nil)
        }
    }

    @objc(broadcastAnnounce:rejecter:)
    func broadcastAnnounce(_ resolve: @escaping RCTPromiseResolveBlock, rejecter reject: @escaping RCTPromiseRejectBlock) {
        sendAnnounce()
        resolve(nil)
    }

    // MARK: - Private Methods

    private func sendAnnounce() {
        guard let publicKey = privateKey?.publicKey.rawRepresentation,
              let signingPublicKey = signingKey?.publicKey.rawRepresentation else { return }

        // TLV-encoded announcement
        var payload = Data()

        // Nickname TLV (tag 0x01)
        let nicknameData = Data(myNickname.utf8)
        payload.append(0x01)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(nicknameData.count).bigEndian) { Array($0) })
        payload.append(nicknameData)

        // Noise public key TLV (tag 0x02)
        payload.append(0x02)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(publicKey.count).bigEndian) { Array($0) })
        payload.append(publicKey)

        // Signing public key TLV (tag 0x03)
        payload.append(0x03)
        payload.append(contentsOf: withUnsafeBytes(of: UInt16(signingPublicKey.count).bigEndian) { Array($0) })
        payload.append(signingPublicKey)

        let packet = createPacket(type: MessageType.announce.rawValue, payload: payload, recipientID: nil)
        broadcastPacket(packet)
    }

    private func sendLeaveAnnouncement() {
        let packet = createPacket(type: MessageType.leave.rawValue, payload: Data(), recipientID: nil)
        broadcastPacket(packet)
    }

    private func initiateHandshakeInternal(with peerId: String) {
        guard let publicKey = privateKey?.publicKey.rawRepresentation else { return }

        let packet = createPacket(
            type: MessageType.noiseHandshake.rawValue,
            payload: publicKey,
            recipientID: Data(hexString: peerId)
        )
        broadcastPacket(packet)
    }

    private func createPacket(type: UInt8, payload: Data, recipientID: Data?) -> BitchatPacket {
        var packet = BitchatPacket(
            version: 1,
            type: type,
            senderID: myPeerIDData,
            recipientID: recipientID,
            timestamp: UInt64(Date().timeIntervalSince1970 * 1000),
            payload: payload,
            signature: nil,
            ttl: messageTTL
        )

        // Sign the packet
        if let sk = signingKey {
            let dataToSign = packet.dataForSigning()
            if let signature = try? sk.signature(for: dataToSign) {
                packet.signature = signature
            }
        }

        return packet
    }

    private func broadcastPacket(_ packet: BitchatPacket) {
        guard let data = packet.encode() else { return }

        // Send to all connected peripherals
        for (_, peripheral) in peripherals {
            if let services = peripheral.services,
               let service = services.first(where: { $0.uuid == BleMesh.serviceUUID }),
               let char = service.characteristics?.first(where: { $0.uuid == BleMesh.characteristicUUID }) {
                peripheral.writeValue(data, for: char, type: .withoutResponse)
            }
        }

        // Notify all subscribed centrals
        if let char = characteristic {
            peripheralManager?.updateValue(data, for: char, onSubscribedCentrals: nil)
        }
    }

    private func handleReceivedPacket(_ data: Data, from peripheralUUID: String?) {
        guard let packet = BitchatPacket.decode(from: data) else { return }

        let senderID = packet.senderID.map { String(format: "%02x", $0) }.joined()

        // Skip our own packets
        if senderID == myPeerID { return }

        // Deduplication
        let messageID = "\(senderID)-\(packet.timestamp)-\(packet.type)"
        if processedMessages.contains(messageID) { return }
        processedMessages.insert(messageID)

        // Handle by type
        switch MessageType(rawValue: packet.type) {
        case .announce:
            handleAnnounce(packet, from: senderID)
        case .message:
            handleMessage(packet, from: senderID)
        case .noiseHandshake:
            handleNoiseHandshake(packet, from: senderID)
        case .noiseEncrypted:
            handleNoiseEncrypted(packet, from: senderID)
        case .leave:
            handleLeave(from: senderID)
        case .fileTransfer:
            handleFileTransfer(packet, from: senderID)
        case .fragment:
            handleFileFragment(packet, from: senderID)
        default:
            break
        }

        // Relay if TTL > 0
        if packet.ttl > 0 {
            var relayPacket = packet
            relayPacket.ttl -= 1
            if let data = relayPacket.encode() {
                bleQueue.asyncAfter(deadline: .now() + Double.random(in: 0.01...0.1)) { [weak self] in
                    // Relay to all except source
                    self?.relayPacket(data, excluding: peripheralUUID)
                }
            }
        }
    }

    private func relayPacket(_ data: Data, excluding peripheralUUID: String?) {
        for (uuid, peripheral) in peripherals where uuid != peripheralUUID {
            if let services = peripheral.services,
               let service = services.first(where: { $0.uuid == BleMesh.serviceUUID }),
               let char = service.characteristics?.first(where: { $0.uuid == BleMesh.characteristicUUID }) {
                peripheral.writeValue(data, for: char, type: .withoutResponse)
            }
        }

        if let char = characteristic {
            peripheralManager?.updateValue(data, for: char, onSubscribedCentrals: nil)
        }
    }

    private func handleAnnounce(_ packet: BitchatPacket, from senderID: String) {
        // Parse TLV payload
        var nickname = senderID
        var noisePublicKey: Data?
        var signingPublicKey: Data?

        var offset = 0
        while offset < packet.payload.count {
            guard offset + 3 <= packet.payload.count else { break }

            let tag = packet.payload[offset]
            let length = Int(packet.payload[offset + 1]) << 8 | Int(packet.payload[offset + 2])
            offset += 3

            guard offset + length <= packet.payload.count else { break }
            let value = packet.payload.subdata(in: offset..<(offset + length))
            offset += length

            switch tag {
            case 0x01:
                nickname = String(data: value, encoding: .utf8) ?? senderID
            case 0x02:
                noisePublicKey = value
            case 0x03:
                signingPublicKey = value
            default:
                break
            }
        }

        // Update peer info
        peers[senderID] = PeerInfo(
            peerId: senderID,
            nickname: nickname,
            isConnected: true,
            rssi: nil,
            lastSeen: Date(),
            noisePublicKey: noisePublicKey,
            isVerified: false
        )

        notifyPeerListUpdated()
    }

    private func handleMessage(_ packet: BitchatPacket, from senderID: String) {
        guard let content = String(data: packet.payload, encoding: .utf8) else { return }

        let nickname = peers[senderID]?.nickname ?? senderID

        let message: [String: Any] = [
            "id": UUID().uuidString,
            "content": content,
            "senderPeerId": senderID,
            "senderNickname": nickname,
            "timestamp": Int(packet.timestamp),
            "isPrivate": false
        ]

        sendEvent(withName: "onMessageReceived", body: ["message": message])
    }

    private func handleNoiseHandshake(_ packet: BitchatPacket, from senderID: String) {
        // Store peer's public key and derive shared secret
        guard packet.payload.count == 32,
              let peerPublicKey = try? Curve25519.KeyAgreement.PublicKey(rawRepresentation: packet.payload),
              let pk = privateKey else { return }

        do {
            let sharedSecret = try pk.sharedSecretFromKeyAgreement(with: peerPublicKey)
            let symmetricKey = sharedSecret.hkdfDerivedSymmetricKey(
                using: SHA256.self,
                salt: Data(),
                sharedInfo: Data("mesh-encryption".utf8),
                outputByteCount: 32
            )
            sessions[senderID] = symmetricKey.withUnsafeBytes { Data($0) }

            // Update peer's noise public key
            if var peer = peers[senderID] {
                peer.noisePublicKey = packet.payload
                peers[senderID] = peer
            }

            // Send response handshake if this is an incoming request
            if packet.recipientID == nil || packet.recipientID == myPeerIDData {
                initiateHandshakeInternal(with: senderID)
            }
        } catch {
            sendEvent(withName: "onError", body: ["code": "HANDSHAKE_ERROR", "message": error.localizedDescription])
        }
    }

    private func handleNoiseEncrypted(_ packet: BitchatPacket, from senderID: String) {
        // Check if message is for us
        if let recipientID = packet.recipientID,
           recipientID != myPeerIDData {
            return
        }

        guard let sessionKey = sessions[senderID] else { return }

        // Decrypt
        guard let decrypted = decryptPayload(packet.payload, with: sessionKey) else { return }

        // Parse payload type
        guard decrypted.count > 0 else { return }
        let payloadType = decrypted[0]
        let payloadData = decrypted.dropFirst()

        switch NoisePayloadType(rawValue: payloadType) {
        case .privateMessage:
            handlePrivateMessage(Data(payloadData), from: senderID)
        case .readReceipt:
            if let messageId = String(data: payloadData, encoding: .utf8) {
                sendEvent(withName: "onReadReceipt", body: ["messageId": messageId, "fromPeerId": senderID])
            }
        case .deliveryAck:
            if let messageId = String(data: payloadData, encoding: .utf8) {
                sendEvent(withName: "onDeliveryAck", body: ["messageId": messageId, "fromPeerId": senderID])
            }
        case .solanaTransaction:
            handleSolanaTransaction(Data(payloadData), from: senderID)
        case .transactionResponse:
            handleTransactionResponse(Data(payloadData), from: senderID)
        default:
            break
        }
    }

    private func handlePrivateMessage(_ data: Data, from senderID: String) {
        // Parse TLV private message
        var messageId: String?
        var content: String?

        var offset = 0
        while offset < data.count {
            guard offset + 3 <= data.count else { break }
            let tag = data[offset]
            let length = Int(data[offset + 1]) << 8 | Int(data[offset + 2])
            offset += 3

            guard offset + length <= data.count else { break }
            let value = data.subdata(in: offset..<(offset + length))
            offset += length

            switch tag {
            case 0x01:
                messageId = String(data: value, encoding: .utf8)
            case 0x02:
                content = String(data: value, encoding: .utf8)
            default:
                break
            }
        }

        guard let id = messageId, let text = content else { return }
        let nickname = peers[senderID]?.nickname ?? senderID

        let message: [String: Any] = [
            "id": id,
            "content": text,
            "senderPeerId": senderID,
            "senderNickname": nickname,
            "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            "isPrivate": true
        ]

        sendEvent(withName: "onMessageReceived", body: ["message": message])
    }
    
    private func handleSolanaTransaction(_ data: Data, from senderID: String) {
        // Parse TLV Solana transaction
        var txId: String?
        var serializedTransaction: String?
        var firstSignerPublicKey: String?
        var secondSignerPublicKey: String?
        var description: String?
        
        var offset = 0
        while offset < data.count {
            guard offset + 3 <= data.count else { break }
            
            let tag = data[offset]
            let length = Int(data[offset + 1]) << 8 | Int(data[offset + 2])
            offset += 3
            
            guard offset + length <= data.count else { break }
            let value = data.subdata(in: offset..<(offset + length))
            offset += length
            
            switch tag {
            case 0x01:
                txId = String(data: value, encoding: .utf8)
            case 0x02:
                serializedTransaction = String(data: value, encoding: .utf8)
            case 0x03:
                firstSignerPublicKey = String(data: value, encoding: .utf8)
            case 0x04:
                secondSignerPublicKey = String(data: value, encoding: .utf8)
            case 0x05:
                description = String(data: value, encoding: .utf8)
            default:
                break
            }
        }
        
        guard let id = txId, let tx = serializedTransaction, let firstSigner = firstSignerPublicKey, let secondSigner = secondSignerPublicKey else {
            print("Invalid Solana transaction data")
            return
        }
        
        var txDict: [String: Any] = [
            "id": id,
            "serializedTransaction": tx,
            "senderPeerId": senderID,
            "firstSignerPublicKey": firstSigner,
            "secondSignerPublicKey": secondSigner,
            "timestamp": Int(Date().timeIntervalSince1970 * 1000),
            "requiresSecondSigner": true
        ]
        
        if let desc = description {
            txDict["description"] = desc
        }
        
        sendEvent(withName: "onTransactionReceived", body: ["transaction": txDict])
        print("Received Solana transaction \(id) from \(senderID)")
    }
    
    private func handleTransactionResponse(_ data: Data, from senderID: String) {
        // Parse TLV transaction response
        var txId: String?
        var signedTransaction: String?
        var error: String?
        
        var offset = 0
        while offset < data.count {
            guard offset + 3 <= data.count else { break }
            
            let tag = data[offset]
            let length = Int(data[offset + 1]) << 8 | Int(data[offset + 2])
            offset += 3
            
            guard offset + length <= data.count else { break }
            let value = data.subdata(in: offset..<(offset + length))
            offset += length
            
            switch tag {
            case 0x01:
                txId = String(data: value, encoding: .utf8)
            case 0x02:
                signedTransaction = String(data: value, encoding: .utf8)
            case 0x03:
                error = String(data: value, encoding: .utf8)
            default:
                break
            }
        }
        
        guard let id = txId else {
            print("Invalid transaction response")
            return
        }
        
        var responseDict: [String: Any] = [
            "id": id,
            "responderPeerId": senderID,
            "timestamp": Int(Date().timeIntervalSince1970 * 1000)
        ]
        
        if let signedTx = signedTransaction {
            responseDict["signedTransaction"] = signedTx
        }
        if let err = error {
            responseDict["error"] = err
        }
        
        sendEvent(withName: "onTransactionResponse", body: ["response": responseDict])
        print("Received transaction response for \(id) from \(senderID)")
    }

    private func handleLeave(from senderID: String) {
        peers.removeValue(forKey: senderID)
        sessions.removeValue(forKey: senderID)
        notifyPeerListUpdated()
    }

    private func encryptMessage(_ content: String, for peerId: String) -> Data? {
        // Create private message TLV
        let messageId = UUID().uuidString
        var tlvData = Data()

        // Message ID TLV (tag 0x01)
        let idData = Data(messageId.utf8)
        tlvData.append(0x01)
        tlvData.append(contentsOf: withUnsafeBytes(of: UInt16(idData.count).bigEndian) { Array($0) })
        tlvData.append(idData)

        // Content TLV (tag 0x02)
        let contentData = Data(content.utf8)
        tlvData.append(0x02)
        tlvData.append(contentsOf: withUnsafeBytes(of: UInt16(contentData.count).bigEndian) { Array($0) })
        tlvData.append(contentData)

        // Add payload type prefix
        var payload = Data([NoisePayloadType.privateMessage.rawValue])
        payload.append(tlvData)

        return encryptPayload(payload, for: peerId)
    }

    private func encryptPayload(_ payload: Data, for peerId: String) -> Data? {
        guard let sessionKey = sessions[peerId] else { return nil }

        do {
            let key = SymmetricKey(data: sessionKey)
            let nonce = AES.GCM.Nonce()
            let sealedBox = try AES.GCM.seal(payload, using: key, nonce: nonce)
            return sealedBox.combined
        } catch {
            return nil
        }
    }

    private func decryptPayload(_ encrypted: Data, with sessionKey: Data) -> Data? {
        do {
            let key = SymmetricKey(data: sessionKey)
            let sealedBox = try AES.GCM.SealedBox(combined: encrypted)
            return try AES.GCM.open(sealedBox, using: key)
        } catch {
            return nil
        }
    }

    private func notifyPeerListUpdated() {
        let peerList = peers.values.map { peer in
            return [
                "peerId": peer.peerId,
                "nickname": peer.nickname,
                "isConnected": peer.isConnected,
                "rssi": peer.rssi ?? NSNull(),
                "lastSeen": Int(peer.lastSeen.timeIntervalSince1970 * 1000),
                "isVerified": peer.isVerified
            ] as [String: Any]
        }

        sendEvent(withName: "onPeerListUpdated", body: ["peers": peerList])
    }

    private func getMimeType(for fileName: String) -> String {
        let ext = (fileName as NSString).pathExtension.lowercased()
        switch ext {
        case "jpg", "jpeg": return "image/jpeg"
        case "png": return "image/png"
        case "gif": return "image/gif"
        case "pdf": return "application/pdf"
        case "txt": return "text/plain"
        case "mp4": return "video/mp4"
        case "mp3": return "audio/mpeg"
        default: return "application/octet-stream"
        }
    }
    
    private func handleFileTransfer(_ packet: BitchatPacket, from senderID: String) {
        // Parse file transfer metadata
        var transferId: String?
        var fileName: String?
        var fileSize: Int?
        var mimeType: String?
        var totalChunks: Int?
        
        var offset = 0
        while offset < packet.payload.count {
            guard offset + 3 <= packet.payload.count else { break }
            
            let tag = packet.payload[offset]
            let length = Int(packet.payload[offset + 1]) << 8 | Int(packet.payload[offset + 2])
            offset += 3
            
            guard offset + length <= packet.payload.count else { break }
            let value = packet.payload.subdata(in: offset..<(offset + length))
            offset += length
            
            switch tag {
            case 0x01:
                transferId = String(data: value, encoding: .utf8)
            case 0x02:
                fileName = String(data: value, encoding: .utf8)
            case 0x03 where value.count == 4:
                fileSize = value.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            case 0x04:
                mimeType = String(data: value, encoding: .utf8)
            case 0x05 where value.count == 4:
                totalChunks = value.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            default:
                break
            }
        }
        
        guard let id = transferId, let name = fileName, let size = fileSize, let mime = mimeType, let chunks = totalChunks else {
            print("Invalid file transfer metadata")
            return
        }
        
        // Store transfer info
        activeFileTransfers[id] = FileTransfer(
            id: id,
            fileName: name,
            fileSize: size,
            mimeType: mime,
            senderPeerId: senderID,
            totalChunks: chunks,
            receivedChunks: []
        )
        fileTransferFragments[id] = [:]
        
        print("Started receiving file: \(name) (\(size) bytes, \(chunks) chunks)")
    }
    
    private func handleFileFragment(_ packet: BitchatPacket, from senderID: String) {
        // Parse file fragment
        var transferId: String?
        var chunkIndex: Int?
        var totalChunks: Int?
        var chunkData: Data?
        
        var offset = 0
        while offset < packet.payload.count {
            guard offset + 3 <= packet.payload.count else { break }
            
            let tag = packet.payload[offset]
            let length = Int(packet.payload[offset + 1]) << 8 | Int(packet.payload[offset + 2])
            offset += 3
            
            guard offset + length <= packet.payload.count else { break }
            let value = packet.payload.subdata(in: offset..<(offset + length))
            offset += length
            
            switch tag {
            case 0x01:
                transferId = String(data: value, encoding: .utf8)
            case 0x02 where value.count == 4:
                chunkIndex = value.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            case 0x03 where value.count == 4:
                totalChunks = value.withUnsafeBytes { $0.load(as: UInt32.self).bigEndian }
            case 0x04:
                chunkData = value
            default:
                break
            }
        }
        
        guard let id = transferId, let index = chunkIndex, let chunks = totalChunks, let data = chunkData else {
            print("Invalid file fragment")
            return
        }
        
        guard var transfer = activeFileTransfers[id] else { return }
        
        // Store the fragment
        fileTransferFragments[id]?[index] = data
        transfer.receivedChunks.insert(index)
        activeFileTransfers[id] = transfer
        
        print("Received chunk \(index)/\(chunks) for transfer \(id)")
        
        // Check if we have all chunks
        if transfer.receivedChunks.count == transfer.totalChunks {
            // Reassemble file
            var reassembledData = Data()
            for i in 0..<transfer.totalChunks {
                if let chunk = fileTransferFragments[id]?[i] {
                    reassembledData.append(chunk)
                }
            }
            
            // Convert to base64
            let base64Data = reassembledData.base64EncodedString()
            
            // Emit file received event
            let fileDict: [String: Any] = [
                "id": transfer.id,
                "fileName": transfer.fileName,
                "fileSize": transfer.fileSize,
                "mimeType": transfer.mimeType,
                "data": base64Data,
                "senderPeerId": transfer.senderPeerId,
                "timestamp": Int(Date().timeIntervalSince1970 * 1000)
            ]
            
            sendEvent(withName: "onFileReceived", body: ["file": fileDict])
            
            // Clean up
            activeFileTransfers.removeValue(forKey: id)
            fileTransferFragments.removeValue(forKey: id)
            
            print("File transfer complete: \(transfer.fileName)")
        }
    }
}

// MARK: - CBCentralManagerDelegate

extension BleMesh: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state == .poweredOn && isRunning {
            central.scanForPeripherals(
                withServices: [BleMesh.serviceUUID],
                options: [CBCentralManagerScanOptionAllowDuplicatesKey: false]
            )
        }

        let state: String
        switch central.state {
        case .poweredOn: state = "connected"
        case .poweredOff, .unauthorized: state = "disconnected"
        default: state = "connecting"
        }

        sendEvent(withName: "onConnectionStateChanged", body: [
            "state": state,
            "peerCount": peers.count
        ])
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral, advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let uuid = peripheral.identifier.uuidString

        if peripherals[uuid] == nil {
            peripherals[uuid] = peripheral
            peripheral.delegate = self
            central.connect(peripheral, options: nil)
        }
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.discoverServices([BleMesh.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        let uuid = peripheral.identifier.uuidString

        if let peerId = peripheralToPeer[uuid] {
            if var peer = peers[peerId] {
                peer.isConnected = false
                peers[peerId] = peer
            }
            notifyPeerListUpdated()
        }

        // Reconnect
        if isRunning {
            central.connect(peripheral, options: nil)
        }
    }
}

// MARK: - CBPeripheralDelegate

extension BleMesh: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard let services = peripheral.services else { return }

        for service in services where service.uuid == BleMesh.serviceUUID {
            peripheral.discoverCharacteristics([BleMesh.characteristicUUID], for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let characteristics = service.characteristics else { return }

        for char in characteristics where char.uuid == BleMesh.characteristicUUID {
            peripheral.setNotifyValue(true, for: char)

            // Send announce to new peer
            sendAnnounce()
        }
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard let data = characteristic.value else { return }
        handleReceivedPacket(data, from: peripheral.identifier.uuidString)
    }
}

// MARK: - CBPeripheralManagerDelegate

extension BleMesh: CBPeripheralManagerDelegate {
    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn && isRunning {
            setupPeripheralService()
        }
    }

    private func setupPeripheralService() {
        let char = CBMutableCharacteristic(
            type: BleMesh.characteristicUUID,
            properties: [.read, .write, .writeWithoutResponse, .notify],
            value: nil,
            permissions: [.readable, .writeable]
        )

        let service = CBMutableService(type: BleMesh.serviceUUID, primary: true)
        service.characteristics = [char]

        peripheralManager?.add(service)
        characteristic = char

        peripheralManager?.startAdvertising([
            CBAdvertisementDataServiceUUIDsKey: [BleMesh.serviceUUID]
        ])
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        subscribedCentrals.append(central)
        sendAnnounce()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            if let data = request.value {
                handleReceivedPacket(data, from: nil)
            }
            peripheral.respond(to: request, withResult: .success)
        }
    }
}

// MARK: - Supporting Types

enum MessageType: UInt8 {
    case announce = 0x01
    case message = 0x02
    case leave = 0x03
    case noiseHandshake = 0x04
    case noiseEncrypted = 0x05
    case fileTransfer = 0x06
    case fragment = 0x07
    case requestSync = 0x08
    case solanaTransaction = 0x09
}

enum NoisePayloadType: UInt8 {
    case privateMessage = 0x01
    case readReceipt = 0x02
    case deliveryAck = 0x03
    case fileTransfer = 0x04
    case verifyChallenge = 0x05
    case verifyResponse = 0x06
    case solanaTransaction = 0x07
    case transactionResponse = 0x08
}

struct BitchatPacket {
    var version: UInt8 = 1
    var type: UInt8
    var senderID: Data
    var recipientID: Data?
    var timestamp: UInt64
    var payload: Data
    var signature: Data?
    var ttl: UInt8

    func dataForSigning() -> Data {
        var data = Data()
        data.append(version)
        data.append(type)
        data.append(senderID)
        if let recipientID = recipientID {
            data.append(recipientID)
        }
        data.append(contentsOf: withUnsafeBytes(of: timestamp.bigEndian) { Array($0) })
        data.append(payload)
        data.append(ttl)
        return data
    }

    func encode() -> Data? {
        var data = Data()

        // Version
        data.append(version)

        // Type
        data.append(type)

        // TTL
        data.append(ttl)

        // Sender ID (8 bytes)
        data.append(senderID.prefix(8))
        if senderID.count < 8 {
            data.append(Data(count: 8 - senderID.count))
        }

        // Recipient ID (8 bytes, or zeros for broadcast)
        if let recipientID = recipientID {
            data.append(recipientID.prefix(8))
            if recipientID.count < 8 {
                data.append(Data(count: 8 - recipientID.count))
            }
        } else {
            data.append(Data(count: 8))
        }

        // Timestamp (8 bytes)
        data.append(contentsOf: withUnsafeBytes(of: timestamp.bigEndian) { Array($0) })

        // Payload length (2 bytes)
        let payloadLength = UInt16(payload.count)
        data.append(contentsOf: withUnsafeBytes(of: payloadLength.bigEndian) { Array($0) })

        // Payload
        data.append(payload)

        // Signature (64 bytes if present)
        if let signature = signature {
            data.append(signature)
        }

        return data
    }

    static func decode(from data: Data) -> BitchatPacket? {
        guard data.count >= 29 else { return nil } // Minimum packet size

        var offset = 0

        // Version
        let version = data[offset]
        offset += 1

        // Type
        let type = data[offset]
        offset += 1

        // TTL
        let ttl = data[offset]
        offset += 1

        // Sender ID (8 bytes)
        let senderID = data.subdata(in: offset..<(offset + 8))
        offset += 8

        // Recipient ID (8 bytes)
        let recipientIDData = data.subdata(in: offset..<(offset + 8))
        let recipientID = recipientIDData.allSatisfy({ $0 == 0 }) ? nil : recipientIDData
        offset += 8

        // Timestamp (8 bytes)
        let timestampData = data.subdata(in: offset..<(offset + 8))
        let timestamp = timestampData.withUnsafeBytes { $0.load(as: UInt64.self).bigEndian }
        offset += 8

        // Payload length (2 bytes)
        let lengthData = data.subdata(in: offset..<(offset + 2))
        let payloadLength = Int(lengthData.withUnsafeBytes { $0.load(as: UInt16.self).bigEndian })
        offset += 2

        guard offset + payloadLength <= data.count else { return nil }

        // Payload
        let payload = data.subdata(in: offset..<(offset + payloadLength))
        offset += payloadLength

        // Signature (64 bytes if present)
        var signature: Data?
        if offset + 64 <= data.count {
            signature = data.subdata(in: offset..<(offset + 64))
        }

        return BitchatPacket(
            version: version,
            type: type,
            senderID: senderID,
            recipientID: recipientID,
            timestamp: timestamp,
            payload: payload,
            signature: signature,
            ttl: ttl
        )
    }
}

// MARK: - Data Extension

extension Data {
    init?(hexString: String?) {
        guard let hexString = hexString else { return nil }

        let len = hexString.count / 2
        var data = Data(capacity: len)
        var index = hexString.startIndex

        for _ in 0..<len {
            let nextIndex = hexString.index(index, offsetBy: 2)
            guard let byte = UInt8(hexString[index..<nextIndex], radix: 16) else { return nil }
            data.append(byte)
            index = nextIndex
        }

        self = data
    }
}
