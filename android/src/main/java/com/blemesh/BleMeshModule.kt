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

    // File transfer tracking
    private val activeFileTransfers = mutableMapOf<String, FileTransfer>()
    private val fileTransferFragments = mutableMapOf<String, MutableMap<Int, ByteArray>>()
    private const val FILE_FRAGMENT_SIZE = 180 // Max payload size for fragments

    // Transaction chunking tracking
    private val pendingTransactionChunks = mutableMapOf<String, TransactionChunks>()
    private data class TransactionChunks(
        val txId: String,
        val senderId: String,
        val totalSize: Int,
        val totalChunks: Int,
        val chunks: MutableMap<Int, ByteArray> = mutableMapOf()
    )

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
        scope.launch {
            try {
                val transferId = UUID.randomUUID().toString()
                val file = java.io.File(filePath)
                
                if (!file.exists()) {
                    withContext(Dispatchers.Main) {
                        promise.reject("FILE_ERROR", "File does not exist: $filePath")
                    }
                    return@launch
                }

                val fileData = file.readBytes()
                val fileName = file.name
                val mimeType = getMimeType(fileName)
                val totalChunks = (fileData.size + FILE_FRAGMENT_SIZE - 1) / FILE_FRAGMENT_SIZE

                // Build file transfer metadata packet
                val metadataPayload = buildFileTransferMetadata(transferId, fileName, fileData.size, mimeType, totalChunks)
                
                val metadataPacket = createPacket(
                    type = MessageType.FILE_TRANSFER.value,
                    payload = metadataPayload,
                    recipientId = recipientPeerId?.let { hexStringToByteArray(it) }
                )
                broadcastPacket(metadataPacket)

                // Send file fragments
                for (chunkIndex in 0 until totalChunks) {
                    val start = chunkIndex * FILE_FRAGMENT_SIZE
                    val end = minOf(start + FILE_FRAGMENT_SIZE, fileData.size)
                    val chunkData = fileData.copyOfRange(start, end)
                    
                    val fragmentPayload = buildFileFragment(transferId, chunkIndex, totalChunks, chunkData)
                    
                    val fragmentPacket = createPacket(
                        type = MessageType.FRAGMENT.value,
                        payload = fragmentPayload,
                        recipientId = recipientPeerId?.let { hexStringToByteArray(it) }
                    )
                    broadcastPacket(fragmentPacket)
                    
                    // Small delay to avoid overwhelming the BLE stack
                    delay(50)
                }

                withContext(Dispatchers.Main) {
                    promise.resolve(transferId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send file: ${e.message}")
                withContext(Dispatchers.Main) {
                    promise.reject("FILE_ERROR", e.message)
                }
            }
        }
    }

    private fun buildFileTransferMetadata(transferId: String, fileName: String, fileSize: Int, mimeType: String, totalChunks: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        
        // Transfer ID TLV (tag 0x01)
        val transferIdBytes = transferId.toByteArray(Charsets.UTF_8)
        stream.write(0x01)
        stream.write((transferIdBytes.size shr 8) and 0xFF)
        stream.write(transferIdBytes.size and 0xFF)
        stream.write(transferIdBytes)
        
        // File name TLV (tag 0x02)
        val fileNameBytes = fileName.toByteArray(Charsets.UTF_8)
        stream.write(0x02)
        stream.write((fileNameBytes.size shr 8) and 0xFF)
        stream.write(fileNameBytes.size and 0xFF)
        stream.write(fileNameBytes)
        
        // File size TLV (tag 0x03) - 4 bytes
        stream.write(0x03)
        stream.write(0x00)
        stream.write(0x04)
        stream.write((fileSize shr 24) and 0xFF)
        stream.write((fileSize shr 16) and 0xFF)
        stream.write((fileSize shr 8) and 0xFF)
        stream.write(fileSize and 0xFF)
        
        // MIME type TLV (tag 0x04)
        val mimeTypeBytes = mimeType.toByteArray(Charsets.UTF_8)
        stream.write(0x04)
        stream.write((mimeTypeBytes.size shr 8) and 0xFF)
        stream.write(mimeTypeBytes.size and 0xFF)
        stream.write(mimeTypeBytes)
        
        // Total chunks TLV (tag 0x05) - 4 bytes
        stream.write(0x05)
        stream.write(0x00)
        stream.write(0x04)
        stream.write((totalChunks shr 24) and 0xFF)
        stream.write((totalChunks shr 16) and 0xFF)
        stream.write((totalChunks shr 8) and 0xFF)
        stream.write(totalChunks and 0xFF)
        
        return stream.toByteArray()
    }

    private fun buildFileFragment(transferId: String, chunkIndex: Int, totalChunks: Int, chunkData: ByteArray): ByteArray {
        val stream = ByteArrayOutputStream()
        
        // Transfer ID TLV (tag 0x01)
        val transferIdBytes = transferId.toByteArray(Charsets.UTF_8)
        stream.write(0x01)
        stream.write((transferIdBytes.size shr 8) and 0xFF)
        stream.write(transferIdBytes.size and 0xFF)
        stream.write(transferIdBytes)
        
        // Chunk index TLV (tag 0x02) - 4 bytes
        stream.write(0x02)
        stream.write(0x00)
        stream.write(0x04)
        stream.write((chunkIndex shr 24) and 0xFF)
        stream.write((chunkIndex shr 16) and 0xFF)
        stream.write((chunkIndex shr 8) and 0xFF)
        stream.write(chunkIndex and 0xFF)
        
        // Total chunks TLV (tag 0x03) - 4 bytes
        stream.write(0x03)
        stream.write(0x00)
        stream.write(0x04)
        stream.write((totalChunks shr 24) and 0xFF)
        stream.write((totalChunks shr 16) and 0xFF)
        stream.write((totalChunks shr 8) and 0xFF)
        stream.write(totalChunks and 0xFF)
        
        // Chunk data TLV (tag 0x04)
        stream.write(0x04)
        stream.write((chunkData.size shr 8) and 0xFF)
        stream.write(chunkData.size and 0xFF)
        stream.write(chunkData)
        
        return stream.toByteArray()
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "")
        return when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            else -> "application/octet-stream"
        }
    }

    @ReactMethod
    fun sendTransaction(
        txId: String,
        serializedTransaction: String,
        recipientPeerId: String,
        firstSignerPublicKey: String,
        secondSignerPublicKey: String,
        description: String?,
        promise: Promise
    ) {
        scope.launch {
            try {
                // Build TLV payload for Solana transaction
                val payload = buildSolanaTransactionPayload(
                    txId = txId,
                    serializedTransaction = serializedTransaction,
                    firstSignerPublicKey = firstSignerPublicKey,
                    secondSignerPublicKey = secondSignerPublicKey,
                    description = description
                )

                // Check if we need chunking (MTU limit is ~500 bytes for encrypted payload)
                val maxPayloadSize = 450 // Conservative limit for encrypted data
                
                if (sessions.containsKey(recipientPeerId)) {
                    val encryptedPayload = byteArrayOf(NoisePayloadType.SOLANA_TRANSACTION.value) + payload
                    val encrypted = encryptPayload(encryptedPayload, recipientPeerId)
                    
                    if (encrypted != null) {
                        if (encrypted.size <= maxPayloadSize) {
                            // Small enough for single packet
                            val packet = createPacket(
                                type = MessageType.NOISE_ENCRYPTED.value,
                                payload = encrypted,
                                recipientId = hexStringToByteArray(recipientPeerId)
                            )
                            broadcastPacket(packet)
                        } else {
                            // Need to chunk large transaction
                            Log.d(TAG, "Transaction too large (${encrypted.size} bytes), using chunking")
                            sendChunkedTransaction(txId, encrypted, recipientPeerId)
                        }
                    }
                } else {
                    // No session, initiate handshake first
                    initiateHandshakeInternal(recipientPeerId)
                }

                withContext(Dispatchers.Main) {
                    promise.resolve(txId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send transaction: ${e.message}")
                withContext(Dispatchers.Main) {
                    promise.reject("TRANSACTION_ERROR", e.message)
                }
            }
        }
    }

    private suspend fun sendChunkedTransaction(txId: String, encryptedData: ByteArray, recipientPeerId: String) {
        val chunkSize = 400 // Max chunk size for encrypted data
        val totalChunks = (encryptedData.size + chunkSize - 1) / chunkSize
        
        Log.d(TAG, "Sending chunked transaction $txId: ${encryptedData.size} bytes in $totalChunks chunks")
        
        // Send metadata packet first
        val metadataPayload = buildTransactionChunkMetadata(txId, encryptedData.size, totalChunks)
        val metadataPacket = createPacket(
            type = MessageType.SOLANA_TRANSACTION.value,
            payload = metadataPayload,
            recipientId = hexStringToByteArray(recipientPeerId)
        )
        broadcastPacket(metadataPacket)
        
        delay(100) // Give receiver time to prepare
        
        // Send chunks
        for (chunkIndex in 0 until totalChunks) {
            val start = chunkIndex * chunkSize
            val end = minOf(start + chunkSize, encryptedData.size)
            val chunkData = encryptedData.copyOfRange(start, end)
            
            val chunkPayload = buildTransactionChunk(txId, chunkIndex, totalChunks, chunkData)
            val chunkPacket = createPacket(
                type = MessageType.FRAGMENT.value,
                payload = chunkPayload,
                recipientId = hexStringToByteArray(recipientPeerId)
            )
            broadcastPacket(chunkPacket)
            
            Log.d(TAG, "Sent chunk ${chunkIndex + 1}/$totalChunks for transaction $txId")
            delay(50) // Small delay between chunks
        }
    }

    private fun buildTransactionChunkMetadata(txId: String, totalSize: Int, totalChunks: Int): ByteArray {
        val stream = ByteArrayOutputStream()
        
        // Transaction ID TLV (tag 0x01)
        val txIdBytes = txId.toByteArray(Charsets.UTF_8)
        stream.write(0x01)
        stream.write((txIdBytes.size shr 8) and 0xFF)
        stream.write(txIdBytes.size and 0xFF)
        stream.write(txIdBytes)
        
        // Total size TLV (tag 0x02) - 4 bytes
        stream.write(0x02)
        stream.write(0x00)
        stream.write(0x04)
        stream.write((totalSize shr 24) and 0xFF)
        stream.write((totalSize shr 16) and 0xFF)
        stream.write((totalSize shr 8) and 0xFF)
        stream.write(totalSize and 0xFF)
        
        // Total chunks TLV (tag 0x03) - 4 bytes
        stream.write(0x03)
        stream.write(0x00)
        stream.write(0x04)
        stream.write((totalChunks shr 24) and 0xFF)
        stream.write((totalChunks shr 16) and 0xFF)
        stream.write((totalChunks shr 8) and 0xFF)
        stream.write(totalChunks and 0xFF)
        
        return stream.toByteArray()
    }

    private fun buildTransactionChunk(txId: String, chunkIndex: Int, totalChunks: Int, chunkData: ByteArray): ByteArray {
        val stream = ByteArrayOutputStream()
        
        // Transaction ID TLV (tag 0x01)
        val txIdBytes = txId.toByteArray(Charsets.UTF_8)
        stream.write(0x01)
        stream.write((txIdBytes.size shr 8) and 0xFF)
        stream.write(txIdBytes.size and 0xFF)
        stream.write(txIdBytes)
        
        // Chunk index TLV (tag 0x02) - 4 bytes
        stream.write(0x02)
        stream.write(0x00)
        stream.write(0x04)
        stream.write((chunkIndex shr 24) and 0xFF)
        stream.write((chunkIndex shr 16) and 0xFF)
        stream.write((chunkIndex shr 8) and 0xFF)
        stream.write(chunkIndex and 0xFF)
        
        // Total chunks TLV (tag 0x03) - 4 bytes
        stream.write(0x03)
        stream.write(0x00)
        stream.write(0x04)
        stream.write((totalChunks shr 24) and 0xFF)
        stream.write((totalChunks shr 16) and 0xFF)
        stream.write((totalChunks shr 8) and 0xFF)
        stream.write(totalChunks and 0xFF)
        
        // Chunk data TLV (tag 0x04)
        stream.write(0x04)
        stream.write((chunkData.size shr 8) and 0xFF)
        stream.write(chunkData.size and 0xFF)
        stream.write(chunkData)
        
        return stream.toByteArray()
    }

    private fun buildSolanaTransactionPayload(
        txId: String,
        serializedTransaction: String,
        firstSignerPublicKey: String,
        secondSignerPublicKey: String,
        description: String?
    ): ByteArray {
        val stream = ByteArrayOutputStream()

        // Transaction ID TLV (tag 0x01)
        val txIdBytes = txId.toByteArray(Charsets.UTF_8)
        stream.write(0x01)
        stream.write((txIdBytes.size shr 8) and 0xFF)
        stream.write(txIdBytes.size and 0xFF)
        stream.write(txIdBytes)

        // Serialized transaction TLV (tag 0x02) - base64 encoded
        val txBytes = serializedTransaction.toByteArray(Charsets.UTF_8)
        stream.write(0x02)
        stream.write((txBytes.size shr 8) and 0xFF)
        stream.write(txBytes.size and 0xFF)
        stream.write(txBytes)

        // First signer public key TLV (tag 0x03)
        val firstSignerBytes = firstSignerPublicKey.toByteArray(Charsets.UTF_8)
        stream.write(0x03)
        stream.write((firstSignerBytes.size shr 8) and 0xFF)
        stream.write(firstSignerBytes.size and 0xFF)
        stream.write(firstSignerBytes)

        // Second signer public key TLV (tag 0x04)
        val secondSignerBytes = secondSignerPublicKey.toByteArray(Charsets.UTF_8)
        stream.write(0x04)
        stream.write((secondSignerBytes.size shr 8) and 0xFF)
        stream.write(secondSignerBytes.size and 0xFF)
        stream.write(secondSignerBytes)

        // Description TLV (tag 0x05) - optional
        if (!description.isNullOrEmpty()) {
            val descBytes = description.toByteArray(Charsets.UTF_8)
            stream.write(0x05)
            stream.write((descBytes.size shr 8) and 0xFF)
            stream.write(descBytes.size and 0xFF)
            stream.write(descBytes)
        }

        return stream.toByteArray()
    }

    @ReactMethod
    fun respondToTransaction(
        transactionId: String,
        recipientPeerId: String,
        signedTransaction: String?,
        error: String?,
        promise: Promise
    ) {
        scope.launch {
            try {
                // Build TLV payload for response
                val stream = ByteArrayOutputStream()

                // Transaction ID TLV (tag 0x01)
                val txIdBytes = transactionId.toByteArray(Charsets.UTF_8)
                stream.write(0x01)
                stream.write((txIdBytes.size shr 8) and 0xFF)
                stream.write(txIdBytes.size and 0xFF)
                stream.write(txIdBytes)

                // Signed transaction TLV (tag 0x02) - optional, base64 encoded
                if (!signedTransaction.isNullOrEmpty()) {
                    val signedTxBytes = signedTransaction.toByteArray(Charsets.UTF_8)
                    stream.write(0x02)
                    stream.write((signedTxBytes.size shr 8) and 0xFF)
                    stream.write(signedTxBytes.size and 0xFF)
                    stream.write(signedTxBytes)
                }

                // Error TLV (tag 0x03) - optional
                if (!error.isNullOrEmpty()) {
                    val errorBytes = error.toByteArray(Charsets.UTF_8)
                    stream.write(0x03)
                    stream.write((errorBytes.size shr 8) and 0xFF)
                    stream.write(errorBytes.size and 0xFF)
                    stream.write(errorBytes)
                }

                val payload = byteArrayOf(NoisePayloadType.TRANSACTION_RESPONSE.value) + stream.toByteArray()

                // Send encrypted response
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
                Log.e(TAG, "Failed to respond to transaction: ${e.message}")
                withContext(Dispatchers.Main) {
                    promise.reject("TRANSACTION_RESPONSE_ERROR", e.message)
                }
            }
        }
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
            MessageType.FILE_TRANSFER -> handleFileTransfer(packet, senderId)
            MessageType.FRAGMENT -> handleFragment(packet, senderId)
            MessageType.SOLANA_TRANSACTION -> handleTransactionMetadata(packet, senderId)
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
            NoisePayloadType.SOLANA_TRANSACTION -> handleSolanaTransaction(payloadData, senderId)
            NoisePayloadType.TRANSACTION_RESPONSE -> handleTransactionResponse(payloadData, senderId)
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

    private fun handleSolanaTransaction(data: ByteArray, senderId: String) {
        // Parse TLV Solana transaction
        var txId: String? = null
        var serializedTransaction: String? = null
        var firstSignerPublicKey: String? = null
        var secondSignerPublicKey: String? = null
        var description: String? = null

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
                0x01 -> txId = String(value, Charsets.UTF_8)
                0x02 -> serializedTransaction = String(value, Charsets.UTF_8)
                0x03 -> firstSignerPublicKey = String(value, Charsets.UTF_8)
                0x04 -> secondSignerPublicKey = String(value, Charsets.UTF_8)
                0x05 -> description = String(value, Charsets.UTF_8)
            }
        }

        if (txId == null || serializedTransaction == null || firstSignerPublicKey == null || secondSignerPublicKey == null) {
            Log.e(TAG, "Invalid Solana transaction data")
            return
        }

        val txMap = Arguments.createMap().apply {
            putString("id", txId)
            putString("serializedTransaction", serializedTransaction)
            putString("senderPeerId", senderId)
            putString("firstSignerPublicKey", firstSignerPublicKey)
            putString("secondSignerPublicKey", secondSignerPublicKey)
            if (description != null) putString("description", description)
            putDouble("timestamp", System.currentTimeMillis().toDouble())
            putBoolean("requiresSecondSigner", true)
        }

        sendEvent("onTransactionReceived", Arguments.createMap().apply {
            putMap("transaction", txMap)
        })

        Log.d(TAG, "Received Solana transaction $txId from $senderId")
    }

    private fun handleTransactionResponse(data: ByteArray, senderId: String) {
        // Parse TLV transaction response
        var txId: String? = null
        var signedTransaction: String? = null
        var error: String? = null

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
                0x01 -> txId = String(value, Charsets.UTF_8)
                0x02 -> signedTransaction = String(value, Charsets.UTF_8)
                0x03 -> error = String(value, Charsets.UTF_8)
            }
        }

        if (txId == null) {
            Log.e(TAG, "Invalid transaction response")
            return
        }

        val responseMap = Arguments.createMap().apply {
            putString("id", txId)
            putString("responderPeerId", senderId)
            if (signedTransaction != null) putString("signedTransaction", signedTransaction)
            if (error != null) putString("error", error)
            putDouble("timestamp", System.currentTimeMillis().toDouble())
        }

        sendEvent("onTransactionResponse", Arguments.createMap().apply {
            putMap("response", responseMap)
        })

        Log.d(TAG, "Received transaction response for $txId from $senderId")
    }

    private fun handleFileTransfer(packet: BitchatPacket, senderId: String) {
        // Parse file transfer metadata
        var transferId: String? = null
        var fileName: String? = null
        var fileSize: Int? = null
        var mimeType: String? = null
        var totalChunks: Int? = null

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
                0x01 -> transferId = String(value, Charsets.UTF_8)
                0x02 -> fileName = String(value, Charsets.UTF_8)
                0x03 -> fileSize = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                0x04 -> mimeType = String(value, Charsets.UTF_8)
                0x05 -> totalChunks = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            }
        }

        if (transferId == null || fileName == null || fileSize == null || mimeType == null || totalChunks == null) {
            Log.e(TAG, "Invalid file transfer metadata")
            return
        }

        // Store transfer info
        activeFileTransfers[transferId] = FileTransfer(
            id = transferId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            senderPeerId = senderId,
            totalChunks = totalChunks,
            receivedChunks = mutableSetOf()
        )
        fileTransferFragments[transferId] = mutableMapOf()

        Log.d(TAG, "Started receiving file: $fileName ($fileSize bytes, $totalChunks chunks)")
    }

    private fun handleFragment(packet: BitchatPacket, senderId: String) {
        // Parse fragment - could be file or transaction chunk
        var id: String? = null
        var chunkIndex: Int? = null
        var totalChunks: Int? = null
        var chunkData: ByteArray? = null

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
                0x01 -> id = String(value, Charsets.UTF_8)
                0x02 -> chunkIndex = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                0x03 -> totalChunks = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                0x04 -> chunkData = value
            }
        }

        if (id == null || chunkIndex == null || totalChunks == null || chunkData == null) {
            Log.e(TAG, "Invalid fragment")
            return
        }

        // Check if this is a file fragment
        if (activeFileTransfers.containsKey(id)) {
            handleFileFragment(id, chunkIndex, totalChunks, chunkData)
        } else if (pendingTransactionChunks.containsKey(id)) {
            handleTransactionChunk(id, chunkIndex, totalChunks, chunkData)
        } else {
            Log.w(TAG, "Received fragment for unknown transfer/transaction: $id")
        }
    }

    private fun handleFileFragment(transferId: String, chunkIndex: Int, totalChunks: Int, chunkData: ByteArray) {
        val transfer = activeFileTransfers[transferId] ?: return
        
        // Store the fragment
        fileTransferFragments[transferId]?.set(chunkIndex, chunkData)
        transfer.receivedChunks.add(chunkIndex)

        Log.d(TAG, "Received file chunk $chunkIndex/$totalChunks for transfer $transferId")

        // Check if we have all chunks
        if (transfer.receivedChunks.size == transfer.totalChunks) {
            // Reassemble file
            val reassembledData = ByteArrayOutputStream()
            for (i in 0 until transfer.totalChunks) {
                fileTransferFragments[transferId]?.get(i)?.let { reassembledData.write(it) }
            }

            // Convert to base64
            val base64Data = android.util.Base64.encodeToString(reassembledData.toByteArray(), android.util.Base64.DEFAULT)

            // Emit file received event
            val fileMap = Arguments.createMap().apply {
                putString("id", transfer.id)
                putString("fileName", transfer.fileName)
                putInt("fileSize", transfer.fileSize)
                putString("mimeType", transfer.mimeType)
                putString("data", base64Data)
                putString("senderPeerId", transfer.senderPeerId)
                putDouble("timestamp", System.currentTimeMillis().toDouble())
            }

            sendEvent("onFileReceived", Arguments.createMap().apply {
                putMap("file", fileMap)
            })

            // Clean up
            activeFileTransfers.remove(transferId)
            fileTransferFragments.remove(transferId)

            Log.d(TAG, "File transfer complete: ${transfer.fileName}")
        }
    }

    private fun handleTransactionChunk(txId: String, chunkIndex: Int, totalChunks: Int, chunkData: ByteArray) {
        val pendingTx = pendingTransactionChunks[txId] ?: return
        
        // Store the chunk
        pendingTx.chunks[chunkIndex] = chunkData

        Log.d(TAG, "Received transaction chunk $chunkIndex/$totalChunks for tx $txId")

        // Check if we have all chunks
        if (pendingTx.chunks.size == pendingTx.totalChunks) {
            // Reassemble encrypted data
            val reassembledData = ByteArrayOutputStream()
            for (i in 0 until pendingTx.totalChunks) {
                pendingTx.chunks[i]?.let { reassembledData.write(it) }
            }

            // Decrypt and process
            val sessionKey = sessions[pendingTx.senderId]
            if (sessionKey != null) {
                val decrypted = decryptPayload(reassembledData.toByteArray(), sessionKey)
                if (decrypted != null && decrypted.isNotEmpty()) {
                    // Skip the payload type byte and process as Solana transaction
                    val payloadData = decrypted.copyOfRange(1, decrypted.size)
                    handleSolanaTransaction(payloadData, pendingTx.senderId)
                }
            }

            // Clean up
            pendingTransactionChunks.remove(txId)

            Log.d(TAG, "Transaction receive complete: $txId")
        }
    }

    private fun handleTransactionMetadata(packet: BitchatPacket, senderId: String) {
        // Parse transaction chunk metadata
        var txId: String? = null
        var totalSize: Int? = null
        var totalChunks: Int? = null

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
                0x01 -> txId = String(value, Charsets.UTF_8)
                0x02 -> totalSize = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
                0x03 -> totalChunks = value.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            }
        }

        if (txId == null || totalSize == null || totalChunks == null) {
            Log.e(TAG, "Invalid transaction metadata")
            return
        }

        // Store pending transaction chunks info
        pendingTransactionChunks[txId] = TransactionChunks(
            txId = txId,
            senderId = senderId,
            totalSize = totalSize,
            totalChunks = totalChunks
        )

        Log.d(TAG, "Started receiving chunked transaction: $txId ($totalSize bytes, $totalChunks chunks)")
    }

    private data class FileTransfer(
        val id: String,
        val fileName: String,
        val fileSize: Int,
        val mimeType: String,
        val senderPeerId: String,
        val totalChunks: Int,
        val receivedChunks: MutableSet<Int> = mutableSetOf()
    )

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
        REQUEST_SYNC(0x08),
        SOLANA_TRANSACTION(0x09);

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
        VERIFY_RESPONSE(0x06),
        SOLANA_TRANSACTION(0x07),
        TRANSACTION_RESPONSE(0x08);

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
