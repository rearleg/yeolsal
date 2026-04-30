import Constants from "expo-constants";
import * as Device from "expo-device";
import * as Notifications from "expo-notifications";
import { Platform } from "react-native";
import { registerPushToken } from "../api/notifications";

/**
 * Acquire an Expo push token (asking for permission if needed) and register it
 * with the BE. Safe to call repeatedly — registration is idempotent on the
 * (user_id, token) UNIQUE constraint server-side.
 *
 * Returns the token string on success, or `null` when:
 *  - the OS denied permission,
 *  - we're running in a simulator that doesn't support push,
 *  - the EAS project id isn't configured yet,
 *  - or the BE registration call fails (logged silently so the render flow keeps going).
 */
export async function registerForPushAsync(): Promise<string | null> {
  if (!Device.isDevice) {
    return null;
  }

  const settings = await Notifications.getPermissionsAsync();
  let granted = settings.granted;
  if (!granted) {
    const request = await Notifications.requestPermissionsAsync();
    granted = request.granted;
  }
  if (!granted) {
    return null;
  }

  const projectId =
    Constants.expoConfig?.extra?.eas?.projectId ??
    (Constants as { easConfig?: { projectId?: string } }).easConfig?.projectId;
  if (!projectId) {
    return null;
  }

  const tokenResponse = await Notifications.getExpoPushTokenAsync({ projectId });
  const token = tokenResponse.data;
  const platform: "ios" | "android" = Platform.OS === "ios" ? "ios" : "android";

  try {
    await registerPushToken(token, platform);
  } catch {
    // BE may be unreachable; keep the locally acquired token so a retry can
    // re-register without re-prompting permission.
  }
  return token;
}
