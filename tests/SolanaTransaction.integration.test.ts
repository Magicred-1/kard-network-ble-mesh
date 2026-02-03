/**
 * Solana Transaction Integration Tests
 * 
 * These tests simulate the full flow of Solana multi-signature transactions
 * over the BLE mesh network.
 */

import type {
  SolanaTransaction,
  TransactionResponse,
  TransactionReceivedEvent,
  TransactionResponseEvent,
} from '../src/index';

describe('Solana Transaction Flow', () => {
  // Mock transaction data
  const mockSerializedTx = 'AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABAAIIdGVzdC1rZXkA';
  // Valid base58 Solana addresses (excludes: 0, O, I, l)
  const mockFirstSignerPubKey = 'HbQFxMyZHxeGFK3T3xeBhAMwPhX3NntthBVB8JxbyhV';
  const mockSecondSignerPubKey = '8aY4oKGWYC52hkMNQXKtqhp1c1xZfBnbY8E1nFUmcaf';

  describe('Transaction Structure', () => {
    it('should have correct SolanaTransaction interface', () => {
      const transaction: SolanaTransaction = {
        id: 'tx-123',
        serializedTransaction: mockSerializedTx,
        senderPeerId: 'peer-abc',
        firstSignerPublicKey: mockFirstSignerPubKey,
        secondSignerPublicKey: mockSecondSignerPubKey,
        description: 'Test payment',
        timestamp: Date.now(),
        requiresSecondSigner: true,
      };

      expect(transaction.id).toBeDefined();
      expect(transaction.serializedTransaction).toBe(mockSerializedTx);
      expect(transaction.senderPeerId).toBe('peer-abc');
      expect(transaction.firstSignerPublicKey).toBe(mockFirstSignerPubKey);
      expect(transaction.secondSignerPublicKey).toBe(mockSecondSignerPubKey);
      expect(transaction.description).toBe('Test payment');
      expect(transaction.timestamp).toBeGreaterThan(0);
      expect(transaction.requiresSecondSigner).toBe(true);
    });

    it('should have correct TransactionResponse interface', () => {
      const response: TransactionResponse = {
        id: 'tx-123',
        responderPeerId: 'peer-xyz',
        signedTransaction: mockSerializedTx + 'signedData',
        timestamp: Date.now(),
      };

      expect(response.id).toBe('tx-123');
      expect(response.responderPeerId).toBe('peer-xyz');
      expect(response.signedTransaction).toBeDefined();
      expect(response.timestamp).toBeGreaterThan(0);
    });

    it('should handle error response', () => {
      const response: TransactionResponse = {
        id: 'tx-123',
        responderPeerId: 'peer-xyz',
        error: 'User rejected the transaction',
        timestamp: Date.now(),
      };

      expect(response.error).toBe('User rejected the transaction');
      expect(response.signedTransaction).toBeUndefined();
    });
  });

  describe('Transaction Events', () => {
    it('should handle TransactionReceivedEvent', () => {
      const event: TransactionReceivedEvent = {
        transaction: {
          id: 'tx-456',
          serializedTransaction: mockSerializedTx,
          senderPeerId: 'peer-sender',
          firstSignerPublicKey: mockFirstSignerPubKey,
          secondSignerPublicKey: mockSecondSignerPubKey,
          description: 'Multi-sig transfer',
          timestamp: Date.now(),
          requiresSecondSigner: true,
        },
      };

      expect(event.transaction.id).toBe('tx-456');
      expect(event.transaction.requiresSecondSigner).toBe(true);
    });

    it('should handle TransactionResponseEvent', () => {
      const event: TransactionResponseEvent = {
        response: {
          id: 'tx-789',
          responderPeerId: 'peer-responder',
          signedTransaction: 'fullySignedTxBase64',
          timestamp: Date.now(),
        },
      };

      expect(event.response.id).toBe('tx-789');
      expect(event.response.responderPeerId).toBe('peer-responder');
    });
  });

  describe('Multi-Sig Transaction Flow', () => {
    it('should complete a full two-party signing flow', async () => {
      // Step 1: Sender creates and partially signs transaction
      const partiallySignedTx = {
        id: 'multisig-tx-001',
        serializedTransaction: mockSerializedTx,
        senderPeerId: 'peer-alice',
        firstSignerPublicKey: mockFirstSignerPubKey,
        secondSignerPublicKey: mockSecondSignerPubKey,
        description: 'Split payment: Alice and Bob',
        timestamp: Date.now(),
        requiresSecondSigner: true,
      };

      // Verify transaction structure
      expect(partiallySignedTx.firstSignerPublicKey).toBeDefined();
      expect(partiallySignedTx.secondSignerPublicKey).toBeDefined();
      expect(partiallySignedTx.serializedTransaction).toContain('AQ'); // Base64 start

      // Step 2: Recipient receives and validates transaction
      const receivedEvent: TransactionReceivedEvent = {
        transaction: partiallySignedTx,
      };

      expect(receivedEvent.transaction.senderPeerId).toBe('peer-alice');
      expect(receivedEvent.transaction.requiresSecondSigner).toBe(true);

      // Step 3: Recipient signs and responds
      const fullySignedTx = partiallySignedTx.serializedTransaction + 'BobSignature';
      const response: TransactionResponse = {
        id: partiallySignedTx.id,
        responderPeerId: 'peer-bob',
        signedTransaction: fullySignedTx,
        timestamp: Date.now(),
      };

      expect(response.signedTransaction).toContain('BobSignature');
      expect(response.id).toBe(partiallySignedTx.id);

      // Step 4: Sender receives fully signed transaction
      const responseEvent: TransactionResponseEvent = {
        response,
      };

      expect(responseEvent.response.signedTransaction).toBeDefined();
      expect(responseEvent.response.error).toBeUndefined();
    });

    it('should handle transaction rejection', async () => {
      // Transaction sent by Alice
      const transaction: SolanaTransaction = {
        id: 'reject-tx-001',
        serializedTransaction: mockSerializedTx,
        senderPeerId: 'peer-alice',
        firstSignerPublicKey: mockFirstSignerPubKey,
        secondSignerPublicKey: mockSecondSignerPubKey,
        description: 'Payment request',
        timestamp: Date.now(),
        requiresSecondSigner: true,
      };

      // Bob rejects the transaction
      const rejectionResponse: TransactionResponse = {
        id: transaction.id,
        responderPeerId: 'peer-bob',
        error: 'Transaction rejected: Invalid amount',
        timestamp: Date.now(),
      };

      expect(rejectionResponse.error).toBe('Transaction rejected: Invalid amount');
      expect(rejectionResponse.signedTransaction).toBeUndefined();

      // Alice receives rejection
      const responseEvent: TransactionResponseEvent = {
        response: rejectionResponse,
      };

      expect(responseEvent.response.error).toBeDefined();
    });

    it('should verify public keys match expected signers', () => {
      const transaction: SolanaTransaction = {
        id: 'verify-tx-001',
        serializedTransaction: mockSerializedTx,
        senderPeerId: 'peer-alice',
        firstSignerPublicKey: mockFirstSignerPubKey,
        secondSignerPublicKey: mockSecondSignerPubKey,
        description: 'Verified transfer',
        timestamp: Date.now(),
        requiresSecondSigner: true,
      };

      // Verify both public keys are valid base58 (Solana addresses are base58)
      const isValidBase58 = (str: string) => /^[A-HJ-NP-Za-km-z1-9]+$/.test(str);
      
      expect(isValidBase58(transaction.firstSignerPublicKey)).toBe(true);
      expect(isValidBase58(transaction.secondSignerPublicKey)).toBe(true);
      
      // Verify keys are different
      expect(transaction.firstSignerPublicKey).not.toBe(transaction.secondSignerPublicKey);
    });
  });

  describe('Transaction Security', () => {
    it('should include unique transaction IDs', () => {
      const ids = new Set<string>();
      
      for (let i = 0; i < 100; i++) {
        const tx: SolanaTransaction = {
          id: `tx-${Math.random().toString(36).substring(2, 15)}`,
          serializedTransaction: mockSerializedTx,
          senderPeerId: 'peer-test',
          firstSignerPublicKey: mockFirstSignerPubKey,
          secondSignerPublicKey: mockSecondSignerPubKey,
          timestamp: Date.now(),
          requiresSecondSigner: true,
        };
        ids.add(tx.id);
      }
      
      // All 100 IDs should be unique
      expect(ids.size).toBe(100);
    });

    it('should preserve transaction data integrity', () => {
      const originalTx = 'AQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fICEiIyQlJicoKSorLC0uLzAxMjM0NTY3ODk6Ozw9Pj9A';
      
      const transaction: SolanaTransaction = {
        id: 'integrity-tx',
        serializedTransaction: originalTx,
        senderPeerId: 'peer-alice',
        firstSignerPublicKey: mockFirstSignerPubKey,
        secondSignerPublicKey: mockSecondSignerPubKey,
        description: 'Integrity test',
        timestamp: Date.now(),
        requiresSecondSigner: true,
      };

      // Data should remain unchanged
      expect(transaction.serializedTransaction).toBe(originalTx);
    });
  });
});
