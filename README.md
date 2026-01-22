# kard-network-ble-mesh

A React Native library for BLE (Bluetooth Low Energy) mesh networking. Build decentralized, peer-to-peer messaging apps that work without internet connectivity.

## Features

- **Cross-platform**: Works on both iOS and Android
- **Mesh networking**: Messages automatically relay through peers
- **End-to-end encryption**: Noise protocol for secure private messages
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

##### `onError(callback): () => void`

Called when an error occurs.

## Protocol Compatibility

This library uses the same binary protocol as:
- [bitchat (iOS)](https://github.com/permissionlesstech/bitchat)
- [bitchat-android](https://github.com/permissionlesstech/bitchat-android)

Messages can be exchanged between all three platforms.

## License

MIT
