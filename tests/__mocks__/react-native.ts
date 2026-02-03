// Mock for React Native
const mockBleMeshModule = {
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
};

export const NativeModules = {
  BleMesh: mockBleMeshModule,
};

export class NativeEventEmitter {
  addListener = jest.fn(() => ({ remove: jest.fn() }));
  removeAllListeners = jest.fn();
  emit = jest.fn();
}

export const Platform = {
  OS: 'ios' as const,
  Version: 16,
  select: jest.fn((obj: any) => obj.ios),
};

export const PermissionsAndroid = {
  PERMISSIONS: {
    BLUETOOTH_ADVERTISE: 'android.permission.BLUETOOTH_ADVERTISE',
    BLUETOOTH_CONNECT: 'android.permission.BLUETOOTH_CONNECT',
    BLUETOOTH_SCAN: 'android.permission.BLUETOOTH_SCAN',
    ACCESS_FINE_LOCATION: 'android.permission.ACCESS_FINE_LOCATION',
    ACCESS_COARSE_LOCATION: 'android.permission.ACCESS_COARSE_LOCATION',
    BLUETOOTH: 'android.permission.BLUETOOTH',
    BLUETOOTH_ADMIN: 'android.permission.BLUETOOTH_ADMIN',
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
};

// Default export for ES modules compatibility
export default {
  NativeModules,
  NativeEventEmitter,
  Platform,
  PermissionsAndroid,
};
