import { NativeModules, NativeEventEmitter, Platform, PermissionsAndroid, Permission } from 'react-native';

const LINKING_ERROR =
  `The package 'ble-mesh' doesn't seem to be linked. Make sure: \n\n` +
  Platform.select({ ios: "- You have run 'pod install'\n", default: '' }) +
  '- You rebuilt the app after installing the package\n' +
  '- You are not using Expo Go (this package requires a development build)\n';

const BleMeshModule = NativeModules.BleMesh
  ? NativeModules.BleMesh
  : new Proxy(
      {},
      {
        get() {
          throw new Error(LINKING_ERROR);
        },
      }
    );

const eventEmitter = new NativeEventEmitter(BleMeshModule);

// Types
export interface Peer {
  peerId: string;
  nickname: string;
  isConnected: boolean;
  rssi?: number;
  lastSeen: number;
  isVerified: boolean;
}

export interface Message {
  id: string;
  content: string;
  senderPeerId: string;
  senderNickname: string;
  timestamp: number;
  isPrivate: boolean;
  channel?: string;
}

export interface FileTransfer {
  id: string;
  fileName: string;
  fileSize: number;
  mimeType: string;
  data: string; // base64 encoded
  senderPeerId: string;
  timestamp: number;
}

export interface SolanaTransaction {
  id: string;
  /** Base64-encoded serialized transaction (could be partially signed) */
  serializedTransaction: string;
  /** The peer ID who initiated/sent the transaction */
  senderPeerId: string;
  /** The public key of the first signer (sender) */
  firstSignerPublicKey: string;
  /** 
   * The preferred public key of the second signer (optional).
   * If not provided, any peer can sign as the second signer.
   */
  secondSignerPublicKey?: string;
  /** Description or purpose of the transaction */
  description?: string;
  /** Timestamp when transaction was sent */
  timestamp: number;
  /** Whether this transaction requires a second signer to sign */
  requiresSecondSigner: boolean;
}

export interface TransactionResponse {
  id: string;
  /** The peer ID who responded */
  responderPeerId: string;
  /** Base64-encoded fully signed transaction (after second signer signs) */
  signedTransaction?: string;
  /** Error message if signing failed */
  error?: string;
  /** Timestamp of response */
  timestamp: number;
}

export interface SolanaNonceTransaction {
  recentBlockhash: string;
  nonceAccount: string;
  nonceAuthority: string;
  feePayer: string;
  instructions: Array<{
    programId: string;
    keys: Array<{ pubkey: string; isSigner: boolean; isWritable: boolean }>;
    data: string; // base64 encoded
  }>;
}

export interface PermissionStatus {
  bluetooth: boolean;
  bluetoothAdvertise?: boolean; // Android 12+
  bluetoothConnect?: boolean;   // Android 12+
  bluetoothScan?: boolean;      // Android 12+
  location: boolean;
}

export type ConnectionState = 'disconnected' | 'connecting' | 'connected' | 'scanning';

export interface MeshServiceConfig {
  nickname?: string;
  autoRequestPermissions?: boolean;
}

// Event types
export type PeerListUpdatedEvent = { peers: Peer[] };
export type MessageReceivedEvent = { message: Message };
export type FileReceivedEvent = { file: FileTransfer };
export type TransactionReceivedEvent = { transaction: SolanaTransaction };
export type TransactionResponseEvent = { response: TransactionResponse };
export type ConnectionStateChangedEvent = { state: ConnectionState; peerCount: number };
export type PermissionsChangedEvent = { permissions: PermissionStatus };
export type ErrorEvent = { code: string; message: string };

// Event listener types
type EventCallback<T> = (data: T) => void;

class BleMeshService {
  private isInitialized = false;

  // Request all required permissions for BLE mesh networking
  async requestPermissions(): Promise<PermissionStatus> {
    if (Platform.OS === 'android') {
      return this.requestAndroidPermissions();
    } else if (Platform.OS === 'ios') {
      return this.requestIOSPermissions();
    }
    throw new Error('Unsupported platform');
  }

  private async requestAndroidPermissions(): Promise<PermissionStatus> {
    const apiLevel = Platform.Version as number;

    let permissions: Permission[] = [];

    if (apiLevel >= 31) {
      // Android 12+ (API 31+)
      permissions = [
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE as Permission,
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT as Permission,
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN as Permission,
        PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION as Permission,
      ];
    } else {
      // Android 11 and below
      permissions = [
        PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION as Permission,
        PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION as Permission,
      ];
    }

    const results = await PermissionsAndroid.requestMultiple(permissions);

    const status: PermissionStatus = {
      bluetooth: true,
      location: results[PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION] === 'granted',
    };

    if (apiLevel >= 31) {
      status.bluetoothAdvertise = results[PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE] === 'granted';
      status.bluetoothConnect = results[PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT] === 'granted';
      status.bluetoothScan = results[PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN] === 'granted';
      status.bluetooth = status.bluetoothAdvertise && status.bluetoothConnect && status.bluetoothScan;
    }

    return status;
  }

  private async requestIOSPermissions(): Promise<PermissionStatus> {
    // On iOS, Bluetooth permissions are requested automatically when starting services
    // The native module handles the permission prompts
    const result = await BleMeshModule.requestPermissions();
    return {
      bluetooth: result.bluetooth,
      location: result.location,
    };
  }

  // Check current permission status without requesting
  async checkPermissions(): Promise<PermissionStatus> {
    if (Platform.OS === 'android') {
      return this.checkAndroidPermissions();
    }
    return BleMeshModule.checkPermissions();
  }

  private async checkAndroidPermissions(): Promise<PermissionStatus> {
    const apiLevel = Platform.Version as number;

    const fineLocation = await PermissionsAndroid.check(
      PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION
    );

    const status: PermissionStatus = {
      bluetooth: true,
      location: fineLocation,
    };

    if (apiLevel >= 31) {
      const advertise = await PermissionsAndroid.check(
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_ADVERTISE
      );
      const connect = await PermissionsAndroid.check(
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT
      );
      const scan = await PermissionsAndroid.check(
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN
      );

      status.bluetoothAdvertise = advertise;
      status.bluetoothConnect = connect;
      status.bluetoothScan = scan;
      status.bluetooth = advertise && connect && scan;
    }

    return status;
  }

  // Initialize and start the mesh service
  async start(config: MeshServiceConfig = {}): Promise<void> {
    const { nickname = 'anon', autoRequestPermissions = true } = config;

    if (autoRequestPermissions) {
      const permissions = await this.requestPermissions();
      const hasAllPermissions = permissions.bluetooth && permissions.location;

      if (!hasAllPermissions) {
        throw new Error('Required permissions not granted. Bluetooth and Location permissions are required.');
      }
    }

    await BleMeshModule.start(nickname);
    this.isInitialized = true;
  }

  // Stop the mesh service
  async stop(): Promise<void> {
    if (!this.isInitialized) return;
    await BleMeshModule.stop();
    this.isInitialized = false;
  }

  // Set the user's nickname
  async setNickname(nickname: string): Promise<void> {
    this.ensureInitialized();
    await BleMeshModule.setNickname(nickname);
  }

  // Get the current peer ID
  async getMyPeerId(): Promise<string> {
    this.ensureInitialized();
    return BleMeshModule.getMyPeerId();
  }

  // Get the current nickname
  async getMyNickname(): Promise<string> {
    this.ensureInitialized();
    return BleMeshModule.getMyNickname();
  }

  // Get list of currently connected peers
  async getPeers(): Promise<Peer[]> {
    this.ensureInitialized();
    return BleMeshModule.getPeers();
  }

  // Send a public broadcast message to all peers
  async sendMessage(content: string, channel?: string): Promise<string> {
    this.ensureInitialized();
    return BleMeshModule.sendMessage(content, channel || null);
  }

  // Send a private encrypted message to a specific peer
  async sendPrivateMessage(content: string, recipientPeerId: string): Promise<string> {
    this.ensureInitialized();
    return BleMeshModule.sendPrivateMessage(content, recipientPeerId);
  }

  // Send a file (broadcast or private)
  async sendFile(
    filePath: string,
    options?: { recipientPeerId?: string; channel?: string }
  ): Promise<string> {
    this.ensureInitialized();
    return BleMeshModule.sendFile(filePath, options?.recipientPeerId || null, options?.channel || null);
  }

  /**
   * Send a Solana transaction for any peer to sign as second signer.
   * 
   * @param serializedTransaction - Base64-encoded partially signed transaction
   * @param options - Transaction options
   * @param options.firstSignerPublicKey - Public key of the first signer (required)
   * @param options.secondSignerPublicKey - Preferred second signer (optional, any peer can sign if not specified)
   * @param options.description - Description of the transaction
   * @param options.recipientPeerId - Specific peer to send to (optional, broadcasts to all if not specified)
   */
  async sendTransaction(
    serializedTransaction: string,
    options?: {
      firstSignerPublicKey: string;
      secondSignerPublicKey?: string;
      description?: string;
      recipientPeerId?: string;
    }
  ): Promise<string> {
    this.ensureInitialized();
    const txId = Math.random().toString(36).substring(2, 15);
    return BleMeshModule.sendTransaction(
      txId,
      serializedTransaction,
      options?.recipientPeerId || null,
      options?.firstSignerPublicKey,
      options?.secondSignerPublicKey || null,
      options?.description || null
    );
  }

  // Respond to a received transaction (sign or reject)
  async respondToTransaction(
    transactionId: string,
    recipientPeerId: string,
    response: {
      signedTransaction?: string;
      error?: string;
    }
  ): Promise<void> {
    this.ensureInitialized();
    await BleMeshModule.respondToTransaction(
      transactionId,
      recipientPeerId,
      response.signedTransaction || null,
      response.error || null
    );
  }

  // Send a read receipt for a message
  async sendReadReceipt(messageId: string, recipientPeerId: string): Promise<void> {
    this.ensureInitialized();
    await BleMeshModule.sendReadReceipt(messageId, recipientPeerId);
  }

  // Check if we have an encrypted session with a peer
  async hasEncryptedSession(peerId: string): Promise<boolean> {
    this.ensureInitialized();
    return BleMeshModule.hasEncryptedSession(peerId);
  }

  // Initiate a Noise handshake with a peer
  async initiateHandshake(peerId: string): Promise<void> {
    this.ensureInitialized();
    await BleMeshModule.initiateHandshake(peerId);
  }

  async sendSolanaNonceTransaction(
    transaction: SolanaNonceTransaction,
    recipientPeerId: string
  ): Promise<string> {
    this.ensureInitialized();
    return BleMeshModule.sendSolanaNonceTransaction(transaction, recipientPeerId);
  }

  // Get the identity fingerprint for verification
  async getIdentityFingerprint(): Promise<string> {
    this.ensureInitialized();
    return BleMeshModule.getIdentityFingerprint();
  }

  // Get peer's fingerprint for verification
  async getPeerFingerprint(peerId: string): Promise<string | null> {
    this.ensureInitialized();
    return BleMeshModule.getPeerFingerprint(peerId);
  }

  // Force a broadcast announce to refresh presence
  async broadcastAnnounce(): Promise<void> {
    this.ensureInitialized();
    await BleMeshModule.broadcastAnnounce();
  }

  // Event listeners
  onPeerListUpdated(callback: EventCallback<PeerListUpdatedEvent>): () => void {
    const subscription = eventEmitter.addListener('onPeerListUpdated', callback);
    return () => subscription.remove();
  }

  onMessageReceived(callback: EventCallback<MessageReceivedEvent>): () => void {
    const subscription = eventEmitter.addListener('onMessageReceived', callback);
    return () => subscription.remove();
  }

  onFileReceived(callback: EventCallback<FileReceivedEvent>): () => void {
    const subscription = eventEmitter.addListener('onFileReceived', callback);
    return () => subscription.remove();
  }

  onTransactionReceived(callback: EventCallback<TransactionReceivedEvent>): () => void {
    const subscription = eventEmitter.addListener('onTransactionReceived', callback);
    return () => subscription.remove();
  }

  onTransactionResponse(callback: EventCallback<TransactionResponseEvent>): () => void {
    const subscription = eventEmitter.addListener('onTransactionResponse', callback);
    return () => subscription.remove();
  }

  onConnectionStateChanged(callback: EventCallback<ConnectionStateChangedEvent>): () => void {
    const subscription = eventEmitter.addListener('onConnectionStateChanged', callback);
    return () => subscription.remove();
  }

  onReadReceipt(callback: EventCallback<{ messageId: string; fromPeerId: string }>): () => void {
    const subscription = eventEmitter.addListener('onReadReceipt', callback);
    return () => subscription.remove();
  }

  onDeliveryAck(callback: EventCallback<{ messageId: string; fromPeerId: string }>): () => void {
    const subscription = eventEmitter.addListener('onDeliveryAck', callback);
    return () => subscription.remove();
  }

  onError(callback: EventCallback<ErrorEvent>): () => void {
    const subscription = eventEmitter.addListener('onError', callback);
    return () => subscription.remove();
  }

  private ensureInitialized(): void {
    if (!this.isInitialized) {
      throw new Error('BleMesh service not initialized. Call start() first.');
    }
  }
}

// Export singleton instance
export const BleMesh = new BleMeshService();

// Also export the class for those who want multiple instances
export { BleMeshService };

// Default export
export default BleMesh;
