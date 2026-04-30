import { Stack } from "expo-router";
import { StatusBar } from "expo-status-bar";
import { useEffect } from "react";
import { AuthProvider, useAuth } from "../src/auth/AuthContext";
import { useWantedSans } from "../src/lib/fonts";
import { registerForPushAsync } from "../src/lib/push";

export default function RootLayout() {
  const fonts = useWantedSans();
  if (!fonts.loaded && !fonts.error) {
    return null;
  }
  return (
    <AuthProvider>
      <PushTokenBootstrap />
      <Stack screenOptions={{ headerShown: false }} />
      <StatusBar style="dark" />
    </AuthProvider>
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
