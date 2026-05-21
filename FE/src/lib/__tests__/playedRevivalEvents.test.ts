// Story 3.2 FE-13 — playedRevivalEvents helper tests.

import {
  addPlayedRevivalEventId,
  hasPlayedRevivalEvent,
} from "../playedRevivalEvents";

jest.mock("expo-secure-store", () => ({
  setItemAsync: jest.fn(),
  getItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

const SecureStore = jest.requireMock("expo-secure-store") as {
  setItemAsync: jest.Mock;
  getItemAsync: jest.Mock;
};

describe("playedRevivalEvents helper", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("hasPlayedRevivalEvent → false when SecureStore is empty", async () => {
    SecureStore.getItemAsync.mockResolvedValue(null);
    expect(await hasPlayedRevivalEvent(42)).toBe(false);
  });

  it("hasPlayedRevivalEvent → true when id is present in the stored array", async () => {
    SecureStore.getItemAsync.mockResolvedValue("[1, 2, 42]");
    expect(await hasPlayedRevivalEvent(42)).toBe(true);
  });

  it("hasPlayedRevivalEvent → false on malformed JSON (graceful fallback)", async () => {
    SecureStore.getItemAsync.mockResolvedValue("not-valid-json");
    expect(await hasPlayedRevivalEvent(42)).toBe(false);
  });

  it("addPlayedRevivalEventId appends a fresh id", async () => {
    SecureStore.getItemAsync.mockResolvedValue("[1, 2]");
    await addPlayedRevivalEventId(42);
    expect(SecureStore.setItemAsync).toHaveBeenCalledWith(
      "yeosal.playedRevivalEventIds",
      JSON.stringify([1, 2, 42]),
    );
  });

  it("addPlayedRevivalEventId is idempotent — does not duplicate", async () => {
    SecureStore.getItemAsync.mockResolvedValue("[1, 2, 42]");
    await addPlayedRevivalEventId(42);
    expect(SecureStore.setItemAsync).not.toHaveBeenCalled();
  });

  it("addPlayedRevivalEventId LRU-evicts at length > 50", async () => {
    const fifty = Array.from({ length: 50 }, (_, i) => i + 1);
    SecureStore.getItemAsync.mockResolvedValue(JSON.stringify(fifty));
    await addPlayedRevivalEventId(999);
    const writeCall = SecureStore.setItemAsync.mock.calls[0];
    expect(writeCall).toBeDefined();
    const written = JSON.parse(writeCall[1]) as number[];
    expect(written).toHaveLength(50);
    expect(written[0]).toBe(2); // first eviction
    expect(written[written.length - 1]).toBe(999);
  });
});
