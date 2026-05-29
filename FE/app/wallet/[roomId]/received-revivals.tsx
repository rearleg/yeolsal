// Wallet received-revivals drill-in route (Story 3.4 AC3).
//
// Nested under `[roomId]/` so expo-router shares the dynamic param with
// the wallet root. Wraps the screen in the D2.bento sub-mode and forwards
// the roomId.

import { Redirect, useLocalSearchParams } from "expo-router";
import { useRequireAuth } from "../../../src/auth/useRequireAuth";
import { ReceivedRevivalsDetailScreen } from "../../../src/components/wallet/ReceivedRevivalsDetailScreen";
import { SubModeProvider } from "../../../src/providers/SubModeProvider";

export default function WalletReceivedRoute() {
  useRequireAuth();
  const params = useLocalSearchParams<{ roomId: string }>();
  const roomId = Number(params.roomId);
  // Parent `[roomId].tsx` is a screen file, not a layout, so it does NOT
  // wrap this nested route — a deep link to `/wallet/abc/received-revivals`
  // bypasses the parent's guard. Mirror the redirect here.
  if (!Number.isFinite(roomId) || roomId <= 0) {
    return <Redirect href="/(tabs)/profile" />;
  }
  return (
    <SubModeProvider subMode="bento">
      <ReceivedRevivalsDetailScreen roomId={roomId} />
    </SubModeProvider>
  );
}
