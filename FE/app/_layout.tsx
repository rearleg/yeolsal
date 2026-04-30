import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { useEffect } from "react";
import { AuthProvider, useAuth } from "../src/auth/AuthContext";
import { useWantedSans } from "../src/lib/fonts";
import { registerForPushAsync } from "../src/lib/push";
import { NavDirectionProvider, useNavDirection } from "../src/navigation/NavDirectionContext";

export default function RootLayout() {
  const fonts = useWantedSans();
  if (!fonts.loaded && !fonts.error) {
    return null;
  }
  return (
    <AuthProvider>
      <NavDirectionProvider>
        <PushTokenBootstrap />
        <DirectionalStack />
        <StatusBar style="dark" />
      </NavDirectionProvider>
    </AuthProvider>
  );
}

function DirectionalStack() {
  const { animation } = useNavDirection();
  return (
    <Stack screenOptions={{ headerShown: false }}>
      <Stack.Screen name="today" options={{ animation }} />
      <Stack.Screen name="feed" options={{ animation }} />
      <Stack.Screen name="rooms" options={{ animation }} />
      <Stack.Screen name="monthly" options={{ animation }} />
      <Stack.Screen name="profile" options={{ animation }} />
    </Stack>
  );
}

function PushTokenBootstrap() {
  const auth = useAuth();
  useEffect(() => {
    if (auth.loading || !auth.user) return;
    registerForPushAsync().catch(() => {
      // expo-notifications can fail in simulator / dev clients without native
      // changes; swallow to avoid breaking app boot.
    });
  }, [auth.loading, auth.user]);
  return null;
}
