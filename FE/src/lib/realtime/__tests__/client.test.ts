import { deriveWebSocketUrl } from "../client";

describe("deriveWebSocketUrl", () => {
  it("upgrades https://host/yeolsal/api/v1 to wss://host/yeolsal/ws", () => {
    expect(deriveWebSocketUrl("https://api.rearleg.com/yeolsal/api/v1")).toBe(
      "wss://api.rearleg.com/yeolsal/ws",
    );
  });

  it("upgrades http://localhost:8080/api/v1 to ws://localhost:8080/ws", () => {
    expect(deriveWebSocketUrl("http://localhost:8080/api/v1")).toBe(
      "ws://localhost:8080/ws",
    );
  });

  it("tolerates a trailing slash on /api/v1", () => {
    expect(deriveWebSocketUrl("https://api.example.com/api/v1/")).toBe(
      "wss://api.example.com/ws",
    );
  });

  it("preserves the path prefix when there is no /api/v1 suffix", () => {
    // Defensive: the spec assumes /api/v1 but if the env var ever points
    // at a bare host, we still upgrade the scheme rather than throwing.
    expect(deriveWebSocketUrl("https://api.example.com")).toBe(
      "wss://api.example.com",
    );
  });
});
