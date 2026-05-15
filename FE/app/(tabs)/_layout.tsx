import { Tabs } from "expo-router";
import { BottomNav } from "../../src/components/BottomNav";
import {
  SpectatorRouteProvider,
  useSpectatorRoute,
} from "../../src/providers/SpectatorRouteProvider";
import { SubModeProvider } from "../../src/providers/SubModeProvider";
import { surface } from "../../src/theme/tokens";

/**
 * Story 2.1 AC2 — branch in-layout, NEVER a parallel route group
 * (Architecture §4.7). Same tabs, same routes. When the viewer is
 * SPECTATOR across every room, wrap the entire surface in
 * `<SubModeProvider subMode="quiet">` so D3 Quiet Dark tokens light up
 * (dim body, slow motion, ember absence). Leaf components MUST NOT read
 * the spectator boolean — they call `useTheme()` and consume tokens.
 */
export default function TabsLayout() {
  return (
    <SpectatorRouteProvider>
      <TabsWithSubMode />
    </SpectatorRouteProvider>
  );
}

function TabsWithSubMode() {
  const { isSpectatorEverywhere } = useSpectatorRoute();
  const tabs = (
    <Tabs
      screenOptions={{
        headerShown: false,
        // "shift" avoids the cross-fade window where both screens go
        // translucent and the dark RN window background bleeds through.
        animation: "shift",
        sceneStyle: { backgroundColor: surface.page },
      }}
      tabBar={(props) => <BottomNav {...props} />}
    >
      <Tabs.Screen name="today" options={{ title: "오늘" }} />
      <Tabs.Screen name="feed" options={{ title: "친구" }} />
      <Tabs.Screen name="rooms" options={{ title: "그룹" }} />
      <Tabs.Screen name="chat" options={{ title: "채팅" }} />
      <Tabs.Screen name="profile" options={{ title: "마이" }} />
    </Tabs>
  );

  if (isSpectatorEverywhere) {
    return <SubModeProvider subMode="quiet">{tabs}</SubModeProvider>;
  }
  return tabs;
}
