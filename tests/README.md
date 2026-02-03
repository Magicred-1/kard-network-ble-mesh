# BleMesh Test Suite

This folder contains the test suite for the kard-network-ble-mesh library.

## Test Structure

```
tests/
├── __mocks__/
│   └── react-native.ts       # Mock implementation of React Native modules
├── BleMesh.test.ts           # Unit tests for BleMesh service
├── SolanaTransaction.integration.test.ts  # Integration tests for Solana transactions
├── setup.ts                  # Test environment setup
└── README.md                 # This file
```

## Running Tests

```bash
# Run all tests
npm test

# Run tests in watch mode
npm run test:watch

# Run tests with coverage
npm run test:coverage
```

## Test Coverage

### Unit Tests (BleMesh.test.ts)
- **Service Lifecycle**: Start, stop, get peer ID, get peers
- **Messaging**: Send public and private messages
- **File Transfer**: Send files to peers (broadcast and private)
- **Solana Transactions**:
  - Send transactions with description
  - Send transactions without description
  - Respond with signed transaction
  - Respond with error/rejection
  - Error handling when not initialized
- **Event Listeners**: Subscription and unsubscription

### Integration Tests (SolanaTransaction.integration.test.ts)
- **Transaction Structure**: Validates interface definitions
- **Transaction Events**: Event type definitions
- **Multi-Sig Transaction Flow**:
  - Full two-party signing flow
  - Transaction rejection handling
  - Public key validation (base58)
- **Security**: Unique transaction IDs, data integrity

## Key Test Scenarios

### Solana Multi-Sig Transaction Flow

```
Alice (First Signer)                    Bob (Second Signer)
     |                                        |
     | 1. Create & partially sign transaction |
     |--------------------------------------->|
     |                                        |
     | 2. Send via BleMesh.sendTransaction()  |
     |--------------------------------------->|
     |                                        |
     |     3. onTransactionReceived event     |
     |<---------------------------------------|
     |                                        |
     |     4. Review & sign transaction       |
     |<---------------------------------------|
     |                                        |
     | 5. respondToTransaction() with signed  |
     |--------------------------------------->|
     |                                        |
     |     6. onTransactionResponse event     |
     |<---------------------------------------|
     |                                        |
     | 7. Broadcast fully signed transaction  |
     |            to Solana network           |
     v                                        v
```

## Mock Implementation

The `__mocks__/react-native.ts` file provides mocked implementations of:
- `NativeModules.BleMesh`: All native methods return promises
- `NativeEventEmitter`: Event subscription mocking
- `Platform`: iOS/Android detection
- `PermissionsAndroid`: Permission request/response mocking

## Adding New Tests

To add new tests:

1. Create a new `.test.ts` file in the `tests/` folder
2. Import the module under test from `../src/`
3. Use Jest's `describe`, `it`, and `expect` functions
4. Mock any dependencies using `jest.mock()`

Example:
```typescript
import { BleMesh } from '../src';

describe('New Feature', () => {
  it('should do something', async () => {
    const result = await BleMesh.someMethod();
    expect(result).toBe('expected');
  });
});
```

## Continuous Integration

These tests can be run in CI/CD pipelines:

```yaml
# Example GitHub Actions
- name: Run tests
  run: |
    npm ci
    npm test
```
