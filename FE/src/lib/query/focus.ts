import { AppState, type AppStateStatus, Platform } from "react-native";
import { focusManager } from "@tanstack/react-query";

/**
 * Wires React Native's AppState into React Query's focus manager so any
 * "active" → "background" → "active" transition triggers a global refetch
 * of stale queries (today / friend feed / room messages / member roster).
 *
 * Without this bridge `refetchOnWindowFocus: true` is a no-op on RN —
 * RN has no window focus event — which is why the user previously had
 * to restart the app or hop tabs to see fresh data.
 *
 * Returns the unsubscribe handle so the root layout can clean up on
 * unmount; web bypasses the listener entirely (the browser already
 * supplies its own focus signal).
 */
export function setupReactQueryFocus(): () => void {
  function onChange(status: AppStateStatus): void {
    if (Platform.OS === "web") return;
    focusManager.setFocused(status === "active");
  }
  const subscription = AppState.addEventListener("change", onChange);
  return () => subscription.remove();
}
