import { Redirect, useLocalSearchParams } from "expo-router";
import { useRequireAuth } from "../../../../src/auth/useRequireAuth";
import { RoomRuleEditor } from "../../../../src/components/rooms/RoomRuleEditor";
import { SubModeProvider } from "../../../../src/providers/SubModeProvider";

export default function RoomRuleEditorRoute() {
  useRequireAuth();
  const params = useLocalSearchParams<{ id: string }>();
  const roomId = Number(params.id);
  if (!Number.isSafeInteger(roomId) || roomId <= 0) {
    return <Redirect href="/(tabs)/rooms" />;
  }
  return (
    <SubModeProvider subMode="plate">
      <RoomRuleEditor roomId={roomId} />
    </SubModeProvider>
  );
}
