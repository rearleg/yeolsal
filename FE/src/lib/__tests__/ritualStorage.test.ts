import AsyncStorage from "@react-native-async-storage/async-storage";

import {
  RITUAL_LAST_FIRED_KEY,
  getLastFiredKstDate,
  setLastFiredKstDate,
} from "../ritualStorage";

jest.mock("@react-native-async-storage/async-storage", () => ({
  __esModule: true,
  default: {
    getItem: jest.fn(),
    setItem: jest.fn(),
    removeItem: jest.fn(),
  },
}));

const mockedAsyncStorage = AsyncStorage as jest.Mocked<typeof AsyncStorage>;

describe("ritualStorage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("RITUAL_LAST_FIRED_KEY", () => {
    it("uses the documented namespaced key", () => {
      expect(RITUAL_LAST_FIRED_KEY).toBe("ritual.lastFiredKstDate");
    });
  });

  describe("getLastFiredKstDate", () => {
    it("returns null when AsyncStorage has no value", async () => {
      mockedAsyncStorage.getItem.mockResolvedValueOnce(null);
      await expect(getLastFiredKstDate()).resolves.toBeNull();
      expect(mockedAsyncStorage.getItem).toHaveBeenCalledWith(RITUAL_LAST_FIRED_KEY);
    });

    it("returns the stored ISO date when present and well-formed", async () => {
      mockedAsyncStorage.getItem.mockResolvedValueOnce("2026-05-14");
      await expect(getLastFiredKstDate()).resolves.toBe("2026-05-14");
    });

    it("returns null when the stored value is not a YYYY-MM-DD string (parse failure)", async () => {
      mockedAsyncStorage.getItem.mockResolvedValueOnce("not-a-date");
      await expect(getLastFiredKstDate()).resolves.toBeNull();
    });

    it("returns null when the stored value is a partial date (missing day)", async () => {
      mockedAsyncStorage.getItem.mockResolvedValueOnce("2026-05");
      await expect(getLastFiredKstDate()).resolves.toBeNull();
    });

    it("returns null when AsyncStorage rejects (defensive — IO failure must not crash callers)", async () => {
      mockedAsyncStorage.getItem.mockRejectedValueOnce(new Error("disk full"));
      await expect(getLastFiredKstDate()).resolves.toBeNull();
    });
  });

  describe("setLastFiredKstDate", () => {
    it("writes the value under the namespaced key", async () => {
      mockedAsyncStorage.setItem.mockResolvedValueOnce(undefined);
      await setLastFiredKstDate("2026-05-14");
      expect(mockedAsyncStorage.setItem).toHaveBeenCalledWith(
        RITUAL_LAST_FIRED_KEY,
        "2026-05-14",
      );
    });

    it("propagates the AsyncStorage rejection so callers can decide policy", async () => {
      const boom = new Error("write failure");
      mockedAsyncStorage.setItem.mockRejectedValueOnce(boom);
      await expect(setLastFiredKstDate("2026-05-14")).rejects.toThrow("write failure");
    });
  });
});
