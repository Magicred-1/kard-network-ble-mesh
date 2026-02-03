/**
 * BleMesh Service Tests
 * 
 * These tests verify the BleMesh service functionality including:
 * - Basic service operations (start, stop, peers)
 * - File transfer functionality
 * - Solana transaction functionality
 */

// Mock react-native before importing BleMesh
jest.mock('react-native', () => ({
  NativeModules: {
    BleMesh: {
      requestPermissions: jest.fn(() => Promise.resolve({ bluetooth: true, location: true })),
      checkPermissions: jest.fn(() => Promise.resolve({ bluetooth: true, location: true })),
      start: jest.fn(() => Promise.resolve()),
      stop: jest.fn(() => Promise.resolve()),
      setNickname: jest.fn(() => Promise.resolve()),
      getMyPeerId: jest.fn(() => Promise.resolve('test-peer-id')),
      getMyNickname: jest.fn(() => Promise.resolve('test-user')),
      getPeers: jest.fn(() => Promise.resolve([])),
      sendMessage: jest.fn(() => Promise.resolve('msg-id-123')),
      sendPrivateMessage: jest.fn(() => Promise.resolve('private-msg-id-123')),
      sendFile: jest.fn(() => Promise.resolve('file-transfer-id')),
      sendTransaction: jest.fn(() => Promise.resolve('tx-id-123')),
      respondToTransaction: jest.fn(() => Promise.resolve()),
      sendReadReceipt: jest.fn(() => Promise.resolve()),
      hasEncryptedSession: jest.fn(() => Promise.resolve(false)),
      initiateHandshake: jest.fn(() => Promise.resolve()),
      getIdentityFingerprint: jest.fn(() => Promise.resolve('fingerprint123')),
      getPeerFingerprint: jest.fn(() => Promise.resolve(null)),
      broadcastAnnounce: jest.fn(() => Promise.resolve()),
    },
  },
  NativeEventEmitter: jest.fn().mockImplementation(() => ({
    addListener: jest.fn(() => ({ remove: jest.fn() })),
    removeAllListeners: jest.fn(),
  })),
  Platform: {
    OS: 'ios',
    Version: 16,
    select: jest.fn((obj: any) => obj.ios),
  },
  PermissionsAndroid: {
    PERMISSIONS: {
      BLUETOOTH_ADVERTISE: 'android.permission.BLUETOOTH_ADVERTISE',
      BLUETOOTH_CONNECT: 'android.permission.BLUETOOTH_CONNECT',
      BLUETOOTH_SCAN: 'android.permission.BLUETOOTH_SCAN',
      ACCESS_FINE_LOCATION: 'android.permission.ACCESS_FINE_LOCATION',
      ACCESS_COARSE_LOCATION: 'android.permission.ACCESS_COARSE_LOCATION',
    },
    requestMultiple: jest.fn(() =>
      Promise.resolve({
        'android.permission.BLUETOOTH_ADVERTISE': 'granted',
        'android.permission.BLUETOOTH_CONNECT': 'granted',
        'android.permission.BLUETOOTH_SCAN': 'granted',
        'android.permission.ACCESS_FINE_LOCATION': 'granted',
      })
    ),
    check: jest.fn(() => Promise.resolve(true)),
  },
}));

import { BleMeshService } from '../src/index';
import { NativeModules } from 'react-native';

describe('BleMeshService', () => {
  let bleMesh: BleMeshService;
  const mockBleMeshModule = NativeModules.BleMesh;

  beforeEach(() => {
    jest.clearAllMocks();
    // Create a new instance for each test
    bleMesh = new BleMeshService();
  });

  describe('Service Lifecycle', () => {
    it('should start the service with default config', async () => {
      await bleMesh.start();
      expect(mockBleMeshModule.start).toHaveBeenCalledWith('anon');
    });

    it('should start the service with custom nickname', async () => {
      await bleMesh.start({ nickname: 'Alice' });
      expect(mockBleMeshModule.start).toHaveBeenCalledWith('Alice');
    });

    it('should stop the service', async () => {
      await bleMesh.start();
      await bleMesh.stop();
      expect(mockBleMeshModule.stop).toHaveBeenCalled();
    });

    it('should get peer ID', async () => {
      await bleMesh.start();
      const peerId = await bleMesh.getMyPeerId();
      expect(peerId).toBe('test-peer-id');
      expect(mockBleMeshModule.getMyPeerId).toHaveBeenCalled();
    });

    it('should get peers list', async () => {
      await bleMesh.start();
      const peers = await bleMesh.getPeers();
      expect(Array.isArray(peers)).toBe(true);
      expect(mockBleMeshModule.getPeers).toHaveBeenCalled();
    });
  });

  describe('Messaging', () => {
    beforeEach(async () => {
      await bleMesh.start();
    });

    it('should send a public message', async () => {
      const messageId = await bleMesh.sendMessage('Hello everyone!');
      expect(messageId).toBe('msg-id-123');
      expect(mockBleMeshModule.sendMessage).toHaveBeenCalledWith('Hello everyone!', null);
    });

    it('should send a private message', async () => {
      const messageId = await bleMesh.sendPrivateMessage('Secret', 'peer-abc');
      expect(messageId).toBe('private-msg-id-123');
      expect(mockBleMeshModule.sendPrivateMessage).toHaveBeenCalledWith('Secret', 'peer-abc');
    });
  });

  describe('File Transfer', () => {
    beforeEach(async () => {
      await bleMesh.start();
    });

    it('should send a file to all peers', async () => {
      const transferId = await bleMesh.sendFile('/path/to/file.jpg');
      expect(transferId).toBe('file-transfer-id');
      expect(mockBleMeshModule.sendFile).toHaveBeenCalledWith('/path/to/file.jpg', null, null);
    });

    it('should send a file to specific peer', async () => {
      const transferId = await bleMesh.sendFile('/path/to/file.jpg', { recipientPeerId: 'peer-abc' });
      expect(transferId).toBe('file-transfer-id');
      expect(mockBleMeshModule.sendFile).toHaveBeenCalledWith('/path/to/file.jpg', 'peer-abc', null);
    });

    it('should send a file with channel', async () => {
      const transferId = await bleMesh.sendFile('/path/to/file.jpg', { recipientPeerId: 'peer-abc', channel: 'files' });
      expect(transferId).toBe('file-transfer-id');
      expect(mockBleMeshModule.sendFile).toHaveBeenCalledWith('/path/to/file.jpg', 'peer-abc', 'files');
    });
  });

  describe('Solana Transactions', () => {
    beforeEach(async () => {
      await bleMesh.start();
    });

    it('should send a Solana transaction', async () => {
      const serializedTx = 'base64EncodedTransactionData';
      const recipientPeerId = 'peer-abc';
      const options = {
        firstSignerPublicKey: 'senderPubKey11111111111111111111111111111111',
        secondSignerPublicKey: 'recipientPubKey2222222222222222222222222222',
        description: 'Payment for services',
      };

      const txId = await bleMesh.sendTransaction(serializedTx, recipientPeerId, options);

      expect(txId).toBe('tx-id-123');
      expect(mockBleMeshModule.sendTransaction).toHaveBeenCalled();
      const callArgs = mockBleMeshModule.sendTransaction.mock.calls[0];
      expect(callArgs[0]).toMatch(/^[a-z0-9]+$/); // txId is auto-generated
      expect(callArgs[1]).toBe(serializedTx);
      expect(callArgs[2]).toBe(recipientPeerId);
      expect(callArgs[3]).toBe(options.firstSignerPublicKey);
      expect(callArgs[4]).toBe(options.secondSignerPublicKey);
      expect(callArgs[5]).toBe(options.description);
    });

    it('should send a Solana transaction without description', async () => {
      const serializedTx = 'base64EncodedTransactionData';
      const recipientPeerId = 'peer-abc';
      const options = {
        firstSignerPublicKey: 'senderPubKey11111111111111111111111111111111',
        secondSignerPublicKey: 'recipientPubKey2222222222222222222222222222',
      };

      const txId = await bleMesh.sendTransaction(serializedTx, recipientPeerId, options);

      expect(txId).toBe('tx-id-123');
      expect(mockBleMeshModule.sendTransaction).toHaveBeenCalled();
      const callArgs = mockBleMeshModule.sendTransaction.mock.calls[0];
      expect(callArgs[5]).toBeNull(); // description is null
    });

    it('should respond to a transaction with signed data', async () => {
      const transactionId = 'tx-abc-123';
      const recipientPeerId = 'peer-abc';
      const signedTransaction = 'fullySignedBase64Transaction';

      await bleMesh.respondToTransaction(transactionId, recipientPeerId, {
        signedTransaction,
      });

      expect(mockBleMeshModule.respondToTransaction).toHaveBeenCalledWith(
        transactionId,
        recipientPeerId,
        signedTransaction,
        null
      );
    });

    it('should respond to a transaction with error', async () => {
      const transactionId = 'tx-abc-123';
      const recipientPeerId = 'peer-abc';
      const error = 'User rejected transaction';

      await bleMesh.respondToTransaction(transactionId, recipientPeerId, {
        error,
      });

      expect(mockBleMeshModule.respondToTransaction).toHaveBeenCalledWith(
        transactionId,
        recipientPeerId,
        null,
        error
      );
    });

    it('should throw error when sending transaction without initializing', async () => {
      const newBleMesh = new BleMeshService();
      // Don't call start()
      
      await expect(
        newBleMesh.sendTransaction('tx', 'peer', {
          firstSignerPublicKey: 'key1',
          secondSignerPublicKey: 'key2',
        })
      ).rejects.toThrow('BleMesh service not initialized');
    });

    it('should throw error when responding to transaction without initializing', async () => {
      const newBleMesh = new BleMeshService();
      // Don't call start()
      
      await expect(
        newBleMesh.respondToTransaction('tx-id', 'peer', { signedTransaction: 'signed' })
      ).rejects.toThrow('BleMesh service not initialized');
    });
  });

  describe('Event Listeners', () => {
    it('should subscribe to transaction received events', () => {
      const callback = jest.fn();
      const unsubscribe = bleMesh.onTransactionReceived(callback);
      
      expect(typeof unsubscribe).toBe('function');
      unsubscribe();
    });

    it('should subscribe to transaction response events', () => {
      const callback = jest.fn();
      const unsubscribe = bleMesh.onTransactionResponse(callback);
      
      expect(typeof unsubscribe).toBe('function');
      unsubscribe();
    });

    it('should subscribe to file received events', () => {
      const callback = jest.fn();
      const unsubscribe = bleMesh.onFileReceived(callback);
      
      expect(typeof unsubscribe).toBe('function');
      unsubscribe();
    });
  });
});
