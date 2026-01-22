package com.blemesh

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.ActivityCompat
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import kotlinx.coroutines.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class BleMeshModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

    companion object {
        private const val TAG = "BleMeshModule"
        private const val SERVICE_UUID_DEBUG = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5A"
        private const val SERVICE_UUID_RELEASE = "F47B5E2D-4A9E-4C5A-9B3F-8E1D2C3A4B5C"
        private const val CHARACTERISTIC_UUID = "A1B2C3D4-E5F6-4A5B-8C9D-0E1F2A3B4C5D"
        private const val MESSAGE_TTL: Byte = 7
    }

    private val serviceUUID = UUID.fromString(if (BuildConfig.DEBUG) SERVICE_UUID_DEBUG else SERVICE_UUID_RELEASE)
    private val characteristicUUID = UUID.fromString(CHARACTERISTIC_UUID)

    // BLE Objects
    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothLeAdvertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var gattCharacteristic: BluetoothGattCharacteristic? = null

    // State
    private var isRunning = false
    private var myNickname = "anon"
    private var myPeerId = ""
    private var myPeerIdBytes = ByteArray(8)

    // Peer tracking
    private data class PeerInfo(
        val peerId: String,
        var nickname: String,
        var isConnected: Boolean,
        var rssi: Int? = null,
        var lastSeen: Long,
        var noisePublicKey: ByteArray? = null,
        var isVerified: Boolean = false
    )
    private val peers = mutableMapOf<String, PeerInfo>()
    private val connectedDevices = mutableMapOf<String, BluetoothDevice>()
    private val deviceToPeer = mutableMapOf<String, String>()
    private val gattConnections = mutableMapOf<String, BluetoothGatt>()

    // Encryption
    private var privateKey: KeyPair? = null
    private var signingKey: KeyPair? = null
    private val sessions = mutableMapOf<String, ByteArray>()

    // Message deduplication
    private val processedMessages = mutableSetOf<String>()

    // Coroutines
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun getName(): String = "BleMesh"

    init {
        bluetoothManager = reactApplicationContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        generateIdentity()
    }

    override fun getConstants(): Map<String, Any> = mapOf(
        "SERVICE_UUID" to serviceUUID.toString(),
        "CHARACTERISTIC_UUID" to characteristicUUID.toString()
    )

    // MARK: - Identity

    private fun generateIdentity() {
        try {
            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(256)

            // Load or generate keys
            val prefs = reactApplicationContext.getSharedPreferences("blemesh", Context.MODE_PRIVATE)

            val savedPrivateKey = prefs.getString("privateKey", null)
            if (savedPrivateKey != null) {
                // TODO: Properly load saved key
            }

            privateKey = keyGen.generateKeyPair()
            signingKey = keyGen.generateKeyPair()

            // Generate peer ID from public key fingerprint
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(privateKey!!.public.encoded)
            myPeerIdBytes = hash.copyOf(8)
            myPeerId = myPeerIdBytes.joinToString("") { "%02x".format(it) }

            Log.d(TAG, "Generated peer ID: $myPeerId")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate identity: ${e.message}")
        }
    }

    // MARK: - React Native API

    @ReactMethod
    fun requestPermissions(promise: Promise) {
        // Android permissions are handled at the JS layer via PermissionsAndroid
        // This just checks the current state
        val hasPermissions = hasBluetoothPermissions()
        val result = Arguments.createMap().apply {
            putBoolean("bluetooth", hasPermissions)
            putBoolean("location", hasLocationPermission())
        }
        promise.resolve(result)
    }

    @ReactMethod
    fun checkPermissions(promise: Promise) {
        val result = Arguments.createMap().apply {
            putBoolean("bluetooth", hasBluetoothPermissions())
            putBoolean("location", hasLocationPermission())
        }
        promise.resolve(result)
    }

    @ReactMethod
    fun start(nickname: String, promise: Promise) {
        myNickname = nickname

        // If already running, just update nickname and resolve
        if (isRunning) {
            Log.d(TAG, "Already running, updating nickname to: $nickname")
            sendAnnounce()
            promise.resolve(null)
            return
        }

        scope.launch {
            try {
                startBleServices()
                isRunning = true
                withContext(Dispatchers.Main) {
                    promise.resolve(null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("START_ERROR", e.message)
                }
            }
        }
    }

    @ReactMethod
    fun stop(promise: Promise) {
        // If not running, just resolve
        if (!isRunning) {
            Log.d(TAG, "Already stopped, nothing to do")
            promise.resolve(null)
            return
        }

        scope.launch {
            try {
                sendLeaveAnnouncement()
                stopBleServices()
                isRunning = false
                peers.clear()
                connectedDevices.clear()
                deviceToPeer.clear()
                withContext(Dispatchers.Main) {
                    promise.resolve(null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("STOP_ERROR", e.message)
                }
            }
        }
    }

    @ReactMethod
    fun setNickname(nickname: String, promise: Promise) {
        myNickname = nickname
        sendAnnounce()
        promise.resolve(null)
    }

    @ReactMethod
    fun getMyPeerId(promise: Promise) {
        promise.resolve(myPeerId)
    }

    @ReactMethod
    fun getMyNickname(promise: Promise) {
        promise.resolve(myNickname)
    }

    @ReactMethod
    fun getPeers(promise: Promise) {
        val peersArray = Arguments.createArray()
        peers.values.forEach { peer ->
            peersArray.pushMap(Arguments.createMap().apply {
                putString("peerId", peer.peerId)
                putString("nickname", peer.nickname)
                putBoolean("isConnected", peer.isConnected)
                peer.rssi?.let { putInt("rssi", it) }
                putDouble("lastSeen", peer.lastSeen.toDouble())
                putBoolean("isVerified", peer.isVerified)
            })
        }
        promise.resolve(peersArray)
    }

    @ReactMethod
    fun sendMessage(content: String, channel: String?, promise: Promise) {
        val messageId = UUID.randomUUID().toString()

        scope.launch {
            try {
                val packet = createPacket(
                    type = MessageType.MESSAGE.value,
                    payload = content.toByteArray(Charsets.UTF_8),
                    recipientId = null
                )
                broadcastPacket(packet)
                withContext(Dispatchers.Main) {
                    promise.resolve(messageId)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("SEND_ERROR", e.message)
                }
            }
        }
    }

    @ReactMethod
    fun sendPrivateMessage(content: String, recipientPeerId: String, promise: Promise) {
        val messageId = UUID.randomUUID().toString()

        scope.launch {
            try {
                if (sessions.containsKey(recipientPeerId)) {
                    val encrypted = encryptMessage(content, recipientPeerId)
                    if (encrypted != null) {
                        val packet = createPacket(
                            type = MessageType.NOISE_ENCRYPTED.value,
                            payload = encrypted,
                            recipientId = hexStringToByteArray(recipientPeerId)
                        )
                        broadcastPacket(packet)
                    }
                } else {
                    initiateHandshakeInternal(recipientPeerId)
                }
                withContext(Dispatchers.Main) {
                    promise.resolve(messageId)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("SEND_ERROR", e.message)
                }
            }
        }
    }

    @ReactMethod
    fun sendFile(filePath: String, recipientPeerId: String?, channel: String?, promise: Promise) {
        val transferId = UUID.randomUUID().toString()
        // TODO: Implement file transfer
        promise.resolve(transferId)
    }

    @ReactMethod
    fun sendReadReceipt(messageId: String, recipientPeerId: String, promise: Promise) {
        scope.launch {
            try {
                val payload = byteArrayOf(NoisePayloadType.READ_RECEIPT.value) + messageId.toByteArray(Charsets.UTF_8)
                val encrypted = encryptPayload(payload, recipientPeerId)
                if (encrypted != null) {
                    val packet = createPacket(
                        type = MessageType.NOISE_ENCRYPTED.value,
                        payload = encrypted,
                        recipientId = hexStringToByteArray(recipientPeerId)
                    )
                    broadcastPacket(packet)
                }
                withContext(Dispatchers.Main) {
                    promise.resolve(null)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    promise.reject("SEND_ERROR", e.message)
                }
            }
        }
    }

    @ReactMethod
    fun hasEncryptedSession(peerId: String, promise: Promise) {
        promise.resolve(sessions.containsKey(peerId))
    }

    @ReactMethod
    fun initiateHandshake(peerId: String, promise: Promise) {
        initiateHandshakeInternal(peerId)
        promise.resolve(null)
    }

    @ReactMethod
    fun getIdentityFingerprint(promise: Promise) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(privateKey!!.public.encoded)
            val fingerprint = hash.joinToString("") { "%02x".format(it) }
            promise.resolve(fingerprint)
        } catch (e: Exception) {
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun getPeerFingerprint(peerId: String, promise: Promise) {
        val peer = peers[peerId]
        if (peer?.noisePublicKey != null) {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(peer.noisePublicKey)
                val fingerprint = hash.joinToString("") { "%02x".format(it) }
                promise.resolve(fingerprint)
            } catch (e: Exception) {
                promise.resolve(null)
            }
        } else {
            promise.resolve(null)
        }
    }

    @ReactMethod
    fun broadcastAnnounce(promise: Promise) {
        sendAnnounce()
        promise.resolve(null)
    }

    @ReactMethod
    fun addListener(eventName: String) {
        // Required for RN event emitter
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        // Required for RN event emitter
    }

    // MARK: - BLE Services

    @SuppressLint("MissingPermission")
    private fun startBleServices() {
        if (!hasBluetoothPermissions()) {
            throw Exception("Bluetooth permissions not granted")
        }

        bluetoothLeScanner = bluetoothAdapter?.bluetoothLeScanner
        bluetoothLeAdvertiser = bluetoothAdapter?.bluetoothLeAdvertiser

        // Start GATT Server
        startGattServer()

        // Start advertising
        startAdvertising()

        // Start scanning
        startScanning()
    }

    @SuppressLint("MissingPermission")
    private fun stopBleServices() {
        bluetoothLeScanner?.stopScan(scanCallback)
        bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        gattServer?.close()

        gattConnections.values.forEach { it.close() }
        gattConnections.clear()
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        gattServer = bluetoothManager?.openGattServer(reactApplicationContext, gattServerCallback)

        val service = BluetoothGattService(serviceUUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)

        gattCharacteristic = BluetoothGattCharacteristic(
            characteristicUUID,
            BluetoothGattCharacteristic.PROPERTY_READ or
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ or BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        gattCharacteristic?.addDescriptor(descriptor)

        service.addCharacteristic(gattCharacteristic)
        gattServer?.addService(service)
    }

    @SuppressLint("MissingPermission")
    private fun startAdvertising() {
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addServiceUuid(ParcelUuid(serviceUUID))
            .build()

        bluetoothLeAdvertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    private fun startScanning() {
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(serviceUUID))
            .build()

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        bluetoothLeScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)
    }

    // MARK: - BLE Callbacks

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val address = device.address

            if (!connectedDevices.containsKey(address) && !gattConnections.containsKey(address)) {
                Log.d(TAG, "Discovered device: $address")
                connectedDevices[address] = device
                device.connectGatt(reactApplicationContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "Scan failed with error: $errorCode")
            sendErrorEvent("SCAN_ERROR", "Scan failed with error code: $errorCode")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d(TAG, "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed with error: $errorCode")
            sendErrorEvent("ADVERTISE_ERROR", "Advertising failed with error code: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val address = gatt.device.address

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Connected to GATT server: $address")
                    gattConnections[address] = gatt
                    // Request larger MTU for bigger packets (512 bytes)
                    Log.d(TAG, "Requesting MTU 512 for $address")
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Disconnected from GATT server: $address")
                    gattConnections.remove(address)
                    handleDeviceDisconnected(address)

                    // Reconnect
                    if (isRunning) {
                        connectedDevices[address]?.connectGatt(
                            reactApplicationContext, false, this, BluetoothDevice.TRANSPORT_LE
                        )
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            Log.d(TAG, "onServicesDiscovered: status=$status for ${gatt.device.address}")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(serviceUUID)
                Log.d(TAG, "Found service: ${service != null}, UUID: $serviceUUID")
                val characteristic = service?.getCharacteristic(characteristicUUID)
                Log.d(TAG, "Found characteristic: ${characteristic != null}")

                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)

                    val descriptor = characteristic.getDescriptor(
                        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                    )
                    Log.d(TAG, "Found descriptor: ${descriptor != null}")
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)

                    // Send announce
                    Log.d(TAG, "Sending announce after services discovered")
                    sendAnnounce()
                } else {
                    Log.e(TAG, "Characteristic not found on remote device!")
                }
            } else {
                Log.e(TAG, "Service discovery failed with status: $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            Log.d(TAG, "onCharacteristicChanged: received ${data?.size ?: 0} bytes from ${gatt.device.address}")
            if (data != null) {
                handleReceivedPacket(data, gatt.device.address)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(TAG, "MTU changed to $mtu for ${gatt.device.address}, status: $status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Now discover services after MTU is set
                gatt.discoverServices()
            } else {
                Log.e(TAG, "MTU negotiation failed, discovering services anyway")
                gatt.discoverServices()
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.d(TAG, "Central connected: ${device.address}")
                    connectedDevices[device.address] = device
                    sendAnnounce()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.d(TAG, "Central disconnected: ${device.address}")
                    handleDeviceDisconnected(device.address)
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            Log.d(TAG, "onCharacteristicWriteRequest: ${value.size} bytes from ${device.address}, uuid=${characteristic.uuid}")
            if (characteristic.uuid == characteristicUUID) {
                Log.d(TAG, "Characteristic matches, handling packet")
                handleReceivedPacket(value, device.address)
            } else {
                Log.d(TAG, "Characteristic UUID mismatch: expected $characteristicUUID")
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    // MARK: - Packet Handling

    private fun handleReceivedPacket(data: ByteArray, fromAddress: String) {
        Log.d(TAG, "handleReceivedPacket: ${data.size} bytes from $fromAddress")
        val packet = BitchatPacket.decode(data)
        if (packet == null) {
            Log.e(TAG, "Failed to decode packet!")
            return
        }

        val senderId = packet.senderId.joinToString("") { "%02x".format(it) }
        Log.d(TAG, "Packet from sender: $senderId, type: ${packet.type}, myPeerId: $myPeerId")

        // Skip our own packets
        if (senderId == myPeerId) {
            Log.d(TAG, "Skipping own packet")
            return
        }

        // Deduplication
        val messageId = "$senderId-${packet.timestamp}-${packet.type}"
        if (processedMessages.contains(messageId)) {
            Log.d(TAG, "Duplicate packet, skipping")
            return
        }
        processedMessages.add(messageId)

        val messageType = MessageType.fromValue(packet.type)
        Log.d(TAG, "Processing packet type: $messageType")

        // Handle by type
        when (messageType) {
            MessageType.ANNOUNCE -> handleAnnounce(packet, senderId)
            MessageType.MESSAGE -> handleMessage(packet, senderId)
            MessageType.NOISE_HANDSHAKE -> handleNoiseHandshake(packet, senderId)
            MessageType.NOISE_ENCRYPTED -> handleNoiseEncrypted(packet, senderId)
            MessageType.LEAVE -> handleLeave(senderId)
            else -> Log.d(TAG, "Unknown message type: ${packet.type}")
        }

        // Relay if TTL > 0
        if (packet.ttl > 0) {
            val relayPacket = packet.copy(ttl = (packet.ttl - 1).toByte())
            scope.launch {
                delay((10L..100L).random())
                relayPacket(relayPacket.encode(), fromAddress)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun relayPacket(data: ByteArray, excludeAddress: String) {
        // Write to all GATT connections except source
        gattConnections.filter { it.key != excludeAddress }.forEach { (_, gatt) ->
            val service = gatt.getService(serviceUUID)
            val characteristic = service?.getCharacteristic(characteristicUUID)
            if (characteristic != null) {
                characteristic.value = data
                gatt.writeCharacteristic(characteristic)
            }
        }

        // Notify all connected devices via GATT server
        gattCharacteristic?.let { char ->
            char.value = data
            connectedDevices.filter { it.key != excludeAddress }.forEach { (_, device) ->
                gattServer?.notifyCharacteristicChanged(device, char, false)
            }
        }
    }

    private fun handleAnnounce(packet: BitchatPacket, senderId: String) {
        Log.d(TAG, "handleAnnounce from $senderId, payload size: ${packet.payload.size}")
        // Parse TLV payload
        var nickname = senderId
        var noisePublicKey: ByteArray? = null

        var offset = 0
        while (offset < packet.payload.size) {
            if (offset + 3 > packet.payload.size) break

            val tag = packet.payload[offset]
            val length = ((packet.payload[offset + 1].toInt() and 0xFF) shl 8) or
                    (packet.payload[offset + 2].toInt() and 0xFF)
            offset += 3

            if (offset + length > packet.payload.size) break
            val value = packet.payload.copyOfRange(offset, offset + length)
            offset += length

            when (tag.toInt()) {
                0x01 -> nickname = String(value, Charsets.UTF_8)
                0x02 -> noisePublicKey = value
            }
        }

        Log.d(TAG, "Parsed announce: nickname=$nickname from peer $senderId")

        peers[senderId] = PeerInfo(
            peerId = senderId,
            nickname = nickname,
            isConnected = true,
            lastSeen = System.currentTimeMillis(),
            noisePublicKey = noisePublicKey,
            isVerified = false
        )

        Log.d(TAG, "Peer added, total peers: ${peers.size}")
        notifyPeerListUpdated()
    }

    private fun handleMessage(packet: BitchatPacket, senderId: String) {
        val content = String(packet.payload, Charsets.UTF_8)
        val nickname = peers[senderId]?.nickname ?: senderId

        val message = Arguments.createMap().apply {
            putString("id", UUID.randomUUID().toString())
            putString("content", content)
            putString("senderPeerId", senderId)
            putString("senderNickname", nickname)
            putDouble("timestamp", packet.timestamp.toDouble())
            putBoolean("isPrivate", false)
        }

        sendEvent("onMessageReceived", Arguments.createMap().apply {
            putMap("message", message)
        })
    }

    private fun handleNoiseHandshake(packet: BitchatPacket, senderId: String) {
        if (packet.payload.size != 65) return // EC public key size

        try {
            // Derive shared secret
            val keyFactory = java.security.KeyFactory.getInstance("EC")
            val keySpec = java.security.spec.X509EncodedKeySpec(packet.payload)
            val peerPublicKey = keyFactory.generatePublic(keySpec)

            val keyAgreement = KeyAgreement.getInstance("ECDH")
            keyAgreement.init(privateKey?.private)
            keyAgreement.doPhase(peerPublicKey, true)

            val sharedSecret = keyAgreement.generateSecret()
            val digest = MessageDigest.getInstance("SHA-256")
            val symmetricKey = digest.digest(sharedSecret)

            sessions[senderId] = symmetricKey

            // Update peer's noise public key
            peers[senderId]?.let { peer ->
                peers[senderId] = peer.copy(noisePublicKey = packet.payload)
            }

            // Send response handshake
            if (packet.recipientId == null || packet.recipientId.contentEquals(myPeerIdBytes)) {
                initiateHandshakeInternal(senderId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Handshake failed: ${e.message}")
            sendErrorEvent("HANDSHAKE_ERROR", e.message ?: "Unknown error")
        }
    }

    private fun handleNoiseEncrypted(packet: BitchatPacket, senderId: String) {
        // Check if message is for us
        if (packet.recipientId != null && !packet.recipientId.contentEquals(myPeerIdBytes)) {
            return
        }

        val sessionKey = sessions[senderId] ?: return
        val decrypted = decryptPayload(packet.payload, sessionKey) ?: return

        if (decrypted.isEmpty()) return

        val payloadType = NoisePayloadType.fromValue(decrypted[0])
        val payloadData = decrypted.copyOfRange(1, decrypted.size)

        when (payloadType) {
            NoisePayloadType.PRIVATE_MESSAGE -> handlePrivateMessage(payloadData, senderId)
            NoisePayloadType.READ_RECEIPT -> {
                val messageId = String(payloadData, Charsets.UTF_8)
                sendEvent("onReadReceipt", Arguments.createMap().apply {
                    putString("messageId", messageId)
                    putString("fromPeerId", senderId)
                })
            }
            NoisePayloadType.DELIVERY_ACK -> {
                val messageId = String(payloadData, Charsets.UTF_8)
                sendEvent("onDeliveryAck", Arguments.createMap().apply {
                    putString("messageId", messageId)
                    putString("fromPeerId", senderId)
                })
            }
            else -> {}
        }
    }

    private fun handlePrivateMessage(data: ByteArray, senderId: String) {
        // Parse TLV private message
        var messageId: String? = null
        var content: String? = null

        var offset = 0
        while (offset < data.size) {
            if (offset + 3 > data.size) break

            val tag = data[offset]
            val length = ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    (data[offset + 2].toInt() and 0xFF)
            offset += 3

            if (offset + length > data.size) break
            val value = data.copyOfRange(offset, offset + length)
            offset += length

            when (tag.toInt()) {
                0x01 -> messageId = String(value, Charsets.UTF_8)
                0x02 -> content = String(value, Charsets.UTF_8)
            }
        }

        if (messageId == null || content == null) return

        val nickname = peers[senderId]?.nickname ?: senderId

        val message = Arguments.createMap().apply {
            putString("id", messageId)
            putString("content", content)
            putString("senderPeerId", senderId)
            putString("senderNickname", nickname)
            putDouble("timestamp", System.currentTimeMillis().toDouble())
            putBoolean("isPrivate", true)
        }

        sendEvent("onMessageReceived", Arguments.createMap().apply {
            putMap("message", message)
        })
    }

    private fun handleLeave(senderId: String) {
        peers.remove(senderId)
        sessions.remove(senderId)
        notifyPeerListUpdated()
    }

    private fun handleDeviceDisconnected(address: String) {
        val peerId = deviceToPeer[address]
        if (peerId != null) {
            peers[peerId]?.let { peer ->
                peers[peerId] = peer.copy(isConnected = false)
            }
            notifyPeerListUpdated()
        }
        connectedDevices.remove(address)
        deviceToPeer.remove(address)
    }

    // MARK: - Private Methods

    private fun sendAnnounce() {
        val publicKey = privateKey?.public?.encoded ?: return
        val signingPublicKey = signingKey?.public?.encoded ?: return

        // Build TLV payload
        val payload = ByteArrayOutputStream().apply {
            // Nickname TLV (tag 0x01)
            val nicknameBytes = myNickname.toByteArray(Charsets.UTF_8)
            write(0x01)
            write((nicknameBytes.size shr 8) and 0xFF)
            write(nicknameBytes.size and 0xFF)
            write(nicknameBytes)

            // Noise public key TLV (tag 0x02)
            write(0x02)
            write((publicKey.size shr 8) and 0xFF)
            write(publicKey.size and 0xFF)
            write(publicKey)

            // Signing public key TLV (tag 0x03)
            write(0x03)
            write((signingPublicKey.size shr 8) and 0xFF)
            write(signingPublicKey.size and 0xFF)
            write(signingPublicKey)
        }.toByteArray()

        val packet = createPacket(MessageType.ANNOUNCE.value, payload, null)
        broadcastPacket(packet)
    }

    private fun sendLeaveAnnouncement() {
        val packet = createPacket(MessageType.LEAVE.value, ByteArray(0), null)
        broadcastPacket(packet)
    }

    private fun initiateHandshakeInternal(peerId: String) {
        val publicKey = privateKey?.public?.encoded ?: return
        val packet = createPacket(
            type = MessageType.NOISE_HANDSHAKE.value,
            payload = publicKey,
            recipientId = hexStringToByteArray(peerId)
        )
        broadcastPacket(packet)
    }

    private fun createPacket(type: Byte, payload: ByteArray, recipientId: ByteArray?): BitchatPacket {
        val timestamp = System.currentTimeMillis().toULong()

        var packet = BitchatPacket(
            version = 1,
            type = type,
            senderId = myPeerIdBytes,
            recipientId = recipientId,
            timestamp = timestamp,
            payload = payload,
            signature = null,
            ttl = MESSAGE_TTL
        )

        // Sign the packet
        try {
            val signature = Signature.getInstance("SHA256withECDSA")
            signature.initSign(signingKey?.private)
            signature.update(packet.dataForSigning())
            packet = packet.copy(signature = signature.sign())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign packet: ${e.message}")
        }

        return packet
    }

    @SuppressLint("MissingPermission")
    private fun broadcastPacket(packet: BitchatPacket) {
        val data = packet.encode()

        // Write to all GATT connections
        gattConnections.values.forEach { gatt ->
            val service = gatt.getService(serviceUUID)
            val characteristic = service?.getCharacteristic(characteristicUUID)
            if (characteristic != null) {
                characteristic.value = data
                gatt.writeCharacteristic(characteristic)
            }
        }

        // Notify all connected devices via GATT server
        gattCharacteristic?.let { char ->
            char.value = data
            connectedDevices.values.forEach { device ->
                gattServer?.notifyCharacteristicChanged(device, char, false)
            }
        }
    }

    private fun encryptMessage(content: String, peerId: String): ByteArray? {
        val messageId = UUID.randomUUID().toString()

        // Build TLV payload
        val tlvData = ByteArrayOutputStream().apply {
            val idBytes = messageId.toByteArray(Charsets.UTF_8)
            write(0x01)
            write((idBytes.size shr 8) and 0xFF)
            write(idBytes.size and 0xFF)
            write(idBytes)

            val contentBytes = content.toByteArray(Charsets.UTF_8)
            write(0x02)
            write((contentBytes.size shr 8) and 0xFF)
            write(contentBytes.size and 0xFF)
            write(contentBytes)
        }.toByteArray()

        val payload = byteArrayOf(NoisePayloadType.PRIVATE_MESSAGE.value) + tlvData
        return encryptPayload(payload, peerId)
    }

    private fun encryptPayload(payload: ByteArray, peerId: String): ByteArray? {
        val sessionKey = sessions[peerId] ?: return null

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val nonce = ByteArray(12)
            SecureRandom().nextBytes(nonce)
            val spec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(sessionKey, "AES"), spec)
            val encrypted = cipher.doFinal(payload)
            nonce + encrypted
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed: ${e.message}")
            null
        }
    }

    private fun decryptPayload(encrypted: ByteArray, sessionKey: ByteArray): ByteArray? {
        if (encrypted.size < 12) return null

        return try {
            val nonce = encrypted.copyOfRange(0, 12)
            val ciphertext = encrypted.copyOfRange(12, encrypted.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, nonce)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), spec)
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed: ${e.message}")
            null
        }
    }

    private fun notifyPeerListUpdated() {
        val peersArray = Arguments.createArray()
        peers.values.forEach { peer ->
            peersArray.pushMap(Arguments.createMap().apply {
                putString("peerId", peer.peerId)
                putString("nickname", peer.nickname)
                putBoolean("isConnected", peer.isConnected)
                peer.rssi?.let { putInt("rssi", it) }
                putDouble("lastSeen", peer.lastSeen.toDouble())
                putBoolean("isVerified", peer.isVerified)
            })
        }

        sendEvent("onPeerListUpdated", Arguments.createMap().apply {
            putArray("peers", peersArray)
        })
    }

    private fun sendEvent(eventName: String, params: WritableMap) {
        reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(eventName, params)
    }

    private fun sendErrorEvent(code: String, message: String) {
        sendEvent("onError", Arguments.createMap().apply {
            putString("code", code)
            putString("message", message)
        })
    }

    // MARK: - Permission Helpers

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.BLUETOOTH_ADMIN) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(reactApplicationContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hexStringToByteArray(hex: String): ByteArray {
        val result = ByteArray(8)
        var index = 0
        var hexIndex = 0
        while (hexIndex < hex.length && index < 8) {
            val byte = hex.substring(hexIndex, minOf(hexIndex + 2, hex.length)).toIntOrNull(16) ?: 0
            result[index] = byte.toByte()
            hexIndex += 2
            index++
        }
        return result
    }

    // MARK: - Supporting Types

    enum class MessageType(val value: Byte) {
        ANNOUNCE(0x01),
        MESSAGE(0x02),
        LEAVE(0x03),
        NOISE_HANDSHAKE(0x04),
        NOISE_ENCRYPTED(0x05),
        FILE_TRANSFER(0x06),
        FRAGMENT(0x07),
        REQUEST_SYNC(0x08);

        companion object {
            fun fromValue(value: Byte): MessageType? = values().find { it.value == value }
        }
    }

    enum class NoisePayloadType(val value: Byte) {
        PRIVATE_MESSAGE(0x01),
        READ_RECEIPT(0x02),
        DELIVERY_ACK(0x03),
        FILE_TRANSFER(0x04),
        VERIFY_CHALLENGE(0x05),
        VERIFY_RESPONSE(0x06);

        companion object {
            fun fromValue(value: Byte): NoisePayloadType? = values().find { it.value == value }
        }
    }

    data class BitchatPacket(
        val version: Byte = 1,
        val type: Byte,
        val senderId: ByteArray,
        val recipientId: ByteArray?,
        val timestamp: ULong,
        val payload: ByteArray,
        val signature: ByteArray?,
        val ttl: Byte
    ) {
        fun dataForSigning(): ByteArray {
            val buffer = ByteBuffer.allocate(1 + 1 + 8 + 8 + 8 + payload.size + 1)
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.put(version)
            buffer.put(type)
            buffer.put(senderId.copyOf(8))
            buffer.put(recipientId?.copyOf(8) ?: ByteArray(8))
            buffer.putLong(timestamp.toLong())
            buffer.put(payload)
            buffer.put(ttl)
            return buffer.array()
        }

        fun encode(): ByteArray {
            val buffer = ByteBuffer.allocate(29 + payload.size + (signature?.size ?: 0))
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.put(version)
            buffer.put(type)
            buffer.put(ttl)
            buffer.put(senderId.copyOf(8))
            buffer.put(recipientId?.copyOf(8) ?: ByteArray(8))
            buffer.putLong(timestamp.toLong())
            buffer.putShort(payload.size.toShort())
            buffer.put(payload)
            signature?.let { buffer.put(it) }
            return buffer.array()
        }

        companion object {
            fun decode(data: ByteArray): BitchatPacket? {
                if (data.size < 29) return null

                val buffer = ByteBuffer.wrap(data)
                buffer.order(ByteOrder.BIG_ENDIAN)

                val version = buffer.get()
                val type = buffer.get()
                val ttl = buffer.get()

                val senderId = ByteArray(8)
                buffer.get(senderId)

                val recipientIdBytes = ByteArray(8)
                buffer.get(recipientIdBytes)
                val recipientId = if (recipientIdBytes.all { it == 0.toByte() }) null else recipientIdBytes

                val timestamp = buffer.long.toULong()
                val payloadLength = buffer.short.toInt() and 0xFFFF

                if (buffer.remaining() < payloadLength) return null
                val payload = ByteArray(payloadLength)
                buffer.get(payload)

                val signature = if (buffer.remaining() >= 64) {
                    ByteArray(64).also { buffer.get(it) }
                } else null

                return BitchatPacket(
                    version = version,
                    type = type,
                    senderId = senderId,
                    recipientId = recipientId,
                    timestamp = timestamp,
                    payload = payload,
                    signature = signature,
                    ttl = ttl
                )
            }
        }
    }
}

private class ByteArrayOutputStream : java.io.ByteArrayOutputStream()
