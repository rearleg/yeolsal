import { Redirect, useLocalSearchParams } from "expo-router";
import { useRequireAuth } from "../../../../src/auth/useRequireAuth";
import { RoomMemberCapEditor } from "../../../../src/components/rooms/RoomMemberCapEditor";
import { SubModeProvider } from "../../../../src/providers/SubModeProvider";

export default function RoomMemberCapEditorRoute() {
  useRequireAuth();
  const params = useLocalSearchParams<{ id: string }>();
  const roomId = Number(params.id);
  if (!Number.isSafeInteger(roomId) || roomId <= 0) {
    return <Redirect href="/(tabs)/rooms" />;
  }
  return (
    <SubModeProvider subMode="plate">
      <RoomMemberCapEditor roomId={roomId} />
    </SubModeProvider>
  );
}
