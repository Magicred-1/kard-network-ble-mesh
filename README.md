# kard-network-ble-mesh

A React Native library for BLE (Bluetooth Low Energy) mesh networking. Build decentralized, peer-to-peer messaging apps that work without internet connectivity.

## Features

- **Cross-platform**: Works on both iOS and Android
- **Mesh networking**: Messages automatically relay through peers
- **End-to-end encryption**: Noise protocol for secure private messages
- **File transfer**: Send files over BLE mesh (chunked for reliability)
- **Solana transactions**: Multi-signature transaction signing over BLE
- **Seamless permissions**: Handles permission requests automatically
- **Message deduplication**: Built-in deduplication prevents duplicate messages
- **Peer discovery**: Automatic discovery of nearby devices

## Installation

```bash
npm install kard-network-ble-mesh
# or
yarn add kard-network-ble-mesh
```

### iOS

```bash
cd ios && pod install
```

Add the following to your `Info.plist`:

```xml
<key>NSBluetoothAlwaysUsageDescription</key>
<string>This app uses Bluetooth to communicate with nearby devices</string>
<key>NSBluetoothPeripheralUsageDescription</key>
<string>This app uses Bluetooth to communicate with nearby devices</string>
<key>UIBackgroundModes</key>
<array>
  <string>bluetooth-central</string>
  <string>bluetooth-peripheral</string>
</array>
```

### Android

Add the following permissions to your `AndroidManifest.xml` (they are already included in the library manifest):

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

## Usage

```typescript
import { BleMesh } from 'kard-network-ble-mesh';

// Start the mesh service (automatically requests permissions)
await BleMesh.start({ nickname: 'Alice' });

// Listen for events
BleMesh.onPeerListUpdated(({ peers }) => {
  console.log('Connected peers:', peers);
});

BleMesh.onMessageReceived(({ message }) => {
  console.log('Received message:', message);
});

// Send a public broadcast message
await BleMesh.sendMessage('Hello everyone!');

// Send a private encrypted message
await BleMesh.sendPrivateMessage('Secret message', recipientPeerId);

// Get current peers
const peers = await BleMesh.getPeers();

// Stop the service
await BleMesh.stop();
```

## API Reference

### BleMesh

The main singleton instance for interacting with the BLE mesh.

#### Methods

##### `start(config?: MeshServiceConfig): Promise<void>`

Starts the mesh service. Automatically requests required permissions.

```typescript
interface MeshServiceConfig {
  nickname?: string;              // Default: 'anon'
  autoRequestPermissions?: boolean; // Default: true
}
```

##### `stop(): Promise<void>`

Stops the mesh service and disconnects from all peers.

##### `setNickname(nickname: string): Promise<void>`

Updates your nickname and broadcasts the change to peers.

##### `getMyPeerId(): Promise<string>`

Returns your unique peer ID (derived from your public key fingerprint).

##### `getMyNickname(): Promise<string>`

Returns your current nickname.

##### `getPeers(): Promise<Peer[]>`

Returns an array of all discovered peers.

```typescript
interface Peer {
  peerId: string;
  nickname: string;
  isConnected: boolean;
  rssi?: number;
  lastSeen: number;
  isVerified: boolean;
}
```

##### `sendMessage(content: string, channel?: string): Promise<string>`

Sends a public broadcast message. Returns the message ID.

##### `sendPrivateMessage(content: string, recipientPeerId: string): Promise<string>`

Sends an encrypted private message to a specific peer. Returns the message ID.

##### `sendReadReceipt(messageId: string, recipientPeerId: string): Promise<void>`

Sends a read receipt for a received message.

##### `sendFile(filePath: string, options?: { recipientPeerId?: string; channel?: string }): Promise<string>`

Sends a file over the mesh network. Files are automatically chunked for reliable transmission.

```typescript
// Send to all peers (broadcast)
await BleMesh.sendFile('/path/to/photo.jpg');

// Send to specific peer
await BleMesh.sendFile('/path/to/document.pdf', { recipientPeerId: 'abc123' });
```

##### `sendTransaction(serializedTransaction: string, recipientPeerId: string, options): Promise<string>`

Sends a Solana transaction for the recipient to sign as the second signer. Perfect for multi-signature transactions.

```typescript
// Send a partially signed transaction
await BleMesh.sendTransaction(
  base64SerializedTx,  // Transaction signed by first signer
  recipientPeerId,
  {
    firstSignerPublicKey: senderPubKey,
    secondSignerPublicKey: recipientPubKey,
    description: 'Payment for services'
  }
);
```

##### `respondToTransaction(transactionId: string, recipientPeerId: string, response): Promise<void>`

Responds to a received transaction with the signed transaction or an error.

```typescript
// Approve and sign
await BleMesh.respondToTransaction(txId, senderPeerId, {
  signedTransaction: fullySignedBase64Tx
});

// Reject
await BleMesh.respondToTransaction(txId, senderPeerId, {
  error: 'User rejected transaction'
});
```

##### `hasEncryptedSession(peerId: string): Promise<boolean>`

Checks if an encrypted session exists with the specified peer.

##### `initiateHandshake(peerId: string): Promise<void>`

Initiates a Noise handshake with a peer for encrypted communication.

##### `getIdentityFingerprint(): Promise<string>`

Returns your identity fingerprint for verification.

##### `getPeerFingerprint(peerId: string): Promise<string | null>`

Returns a peer's identity fingerprint for verification.

##### `broadcastAnnounce(): Promise<void>`

Forces a broadcast announce to refresh your presence on the mesh.

#### Events

##### `onPeerListUpdated(callback): () => void`

Called when the peer list changes.

```typescript
BleMesh.onPeerListUpdated(({ peers }) => {
  console.log('Peers updated:', peers);
});
```

##### `onMessageReceived(callback): () => void`

Called when a message is received.

```typescript
BleMesh.onMessageReceived(({ message }) => {
  console.log('Message from', message.senderNickname, ':', message.content);
});
```

##### `onConnectionStateChanged(callback): () => void`

Called when the connection state changes.

##### `onReadReceipt(callback): () => void`

Called when a read receipt is received.

##### `onDeliveryAck(callback): () => void`

Called when a delivery acknowledgment is received.

##### `onFileReceived(callback): () => void`

Called when a file is received.

```typescript
BleMesh.onFileReceived(({ file }) => {
  console.log('Received file:', file.fileName);
  console.log('Size:', file.fileSize);
  console.log('Data (base64):', file.data);
  // Save or display the file...
});
```

##### `onTransactionReceived(callback): () => void`

Called when a Solana transaction is received for signing.

```typescript
BleMesh.onTransactionReceived(({ transaction }) => {
  console.log('Transaction to sign:', transaction.id);
  console.log('Serialized:', transaction.serializedTransaction);
  console.log('First signer:', transaction.firstSignerPublicKey);
  console.log('Second signer (you):', transaction.secondSignerPublicKey);
  
  // Sign with your wallet...
  const signedTx = await yourWallet.signTransaction(transaction.serializedTransaction);
  
  // Respond
  await BleMesh.respondToTransaction(transaction.id, transaction.senderPeerId, {
    signedTransaction: signedTx
  });
});
```

##### `onTransactionResponse(callback): () => void`

Called when a transaction response is received (signed or rejected).

```typescript
BleMesh.onTransactionResponse(({ response }) => {
  if (response.signedTransaction) {
    console.log('Transaction fully signed!');
    // Broadcast to Solana network...
  } else if (response.error) {
    console.log('Transaction rejected:', response.error);
  }
});
```

##### `onError(callback): () => void`

Called when an error occurs.

## Solana Multi-Signature Transaction Flow

For 2-of-2 multi-signature transactions over BLE:

```
Alice (First Signer)                    Bob (Second Signer)
     |                                        |
     | 1. Create transaction                  |
     | 2. Sign with Alice's key               |
     | 3. Send via BleMesh.sendTransaction()  |
     |--------------------------------------->|
     |                                        |
     |     4. onTransactionReceived event     |
     |<---------------------------------------|
     |                                        |
     |     5. Review transaction              |
     |     6. Sign with Bob's key             |
     |<---------------------------------------|
     |                                        |
     | 7. respondToTransaction() with signed  |
     |--------------------------------------->|
     |                                        |
     |     8. onTransactionResponse event     |
     |<---------------------------------------|
     |                                        |
     | 9. Broadcast fully signed tx           |
     |    to Solana network                   |
     v                                        v
```

### MTU and Transaction Size Limits

The library automatically handles BLE MTU limitations:

| Feature | Max Size | Handling |
|---------|----------|----------|
| Simple messages | ~450 bytes | Single packet |
| Small transactions | ~450 bytes | Single packet |
| Large transactions | Unlimited | Automatic chunking (400 byte chunks) |
| Files | Unlimited | Chunked (180 byte chunks) |

**For Solana transactions:**
- Most transactions (simple transfers) fit in a single packet
- Complex transactions (multi-instruction, large memos) are automatically chunked
- Chunking is transparent to the application layer
- The receiver automatically reassembles chunks before decrypting

## Protocol Compatibility

This library uses the same binary protocol as:
- [bitchat (iOS)](https://github.com/permissionlesstech/bitchat)
- [bitchat-android](https://github.com/permissionlesstech/bitchat-android)

Messages can be exchanged between all three platforms.

## License

MIT
