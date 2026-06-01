// Wallet route — per-room (Story 3.4 AC1, AC6).
//
// Thin Expo-router wrapper that resolves the dynamic `roomId` param,
// wraps the screen in <SubModeProvider subMode="bento"> (AC1 + AC5 —
// sub-mode is page-level only), and delegates rendering to the
// <WalletScreen> component living under src/components/wallet/.

import { Redirect, useLocalSearchParams } from "expo-router";
import { useRequireAuth } from "../../src/auth/useRequireAuth";
import { WalletScreen } from "../../src/components/wallet/WalletScreen";
import { SubModeProvider } from "../../src/providers/SubModeProvider";

export default function WalletRoute() {
  useRequireAuth();
  const params = useLocalSearchParams<{ roomId: string }>();
  const roomId = Number(params.roomId);
  // Bad deep links (`/wallet/abc`, `/wallet/-1`, `/wallet/0`) used to fall
  // through to `roomId={0}` and render a broken wallet with no feedback.
  // Bounce the user back to the profile tab — the wallet entrypoints there
  // surface the rooms the user actually belongs to.
  if (!Number.isFinite(roomId) || roomId <= 0) {
    return <Redirect href="/(tabs)/profile" />;
  }
  return (
    <SubModeProvider subMode="bento">
      <WalletScreen roomId={roomId} />
    </SubModeProvider>
  );
}
