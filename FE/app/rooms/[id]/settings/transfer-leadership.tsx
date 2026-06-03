import { Redirect, useLocalSearchParams } from "expo-router";
import { useRequireAuth } from "../../../../src/auth/useRequireAuth";
import { LeaderTransferPicker } from "../../../../src/components/rooms/LeaderTransferPicker";
import { SubModeProvider } from "../../../../src/providers/SubModeProvider";

export default function LeaderTransferRoute() {
  useRequireAuth();
  const params = useLocalSearchParams<{ id: string }>();
  const roomId = Number(params.id);
  if (!Number.isSafeInteger(roomId) || roomId <= 0) {
    return <Redirect href="/(tabs)/rooms" />;
  }
  return (
    <SubModeProvider subMode="plate">
      <LeaderTransferPicker roomId={roomId} />
    </SubModeProvider>
  );
}
