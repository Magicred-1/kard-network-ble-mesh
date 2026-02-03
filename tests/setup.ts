/**
 * Test Setup File
 * 
 * This file runs before each test file to set up the testing environment.
 */

// Set up any global test utilities or mocks here

// Mock console methods to reduce noise during tests
global.console = {
  ...console,
  // Uncomment to ignore specific console methods during tests
  // log: jest.fn(),
  // debug: jest.fn(),
  // info: jest.fn(),
  // warn: jest.fn(),
  // error: jest.fn(),
};

// Add custom matchers if needed
expect.extend({
  // Example custom matcher
  toBeValidTransactionId(received: string) {
    const pass = /^[a-z0-9-]+$/.test(received);
    return {
      message: () => `expected ${received} to be a valid transaction ID`,
      pass,
    };
  },
});

// Global test timeout
jest.setTimeout(10000);
