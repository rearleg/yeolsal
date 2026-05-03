import { Tabs } from "expo-router";
import { BottomNav } from "../../src/components/BottomNav";

export default function TabsLayout() {
  return (
    <Tabs
      screenOptions={{ headerShown: false, animation: "fade" }}
      tabBar={(props) => <BottomNav {...props} />}
    >
      <Tabs.Screen name="today" options={{ title: "오늘" }} />
      <Tabs.Screen name="feed" options={{ title: "친구" }} />
      <Tabs.Screen name="rooms" options={{ title: "그룹" }} />
      <Tabs.Screen name="chat" options={{ title: "채팅" }} />
      <Tabs.Screen name="profile" options={{ title: "마이" }} />
    </Tabs>
  );
}
