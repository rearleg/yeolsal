import { Platform } from "react-native";
import { registerPushToken } from "../api/notifications";

// expo-device / expo-notifications / expo-constants are loaded lazily inside
// the function body via require() so that a missing native module (e.g. when
// the current dev client was built before these deps were added) does NOT
// throw at module-import time. A throw at import time would unwind the
// _layout.tsx import chain and prevent the AuthProvider tree from mounting,
// surfacing a misleading "useAuth must be used inside AuthProvider" error.
type DeviceModule = { isDevice?: boolean };
type NotificationsModule = {
  getPermissionsAsync: () => Promise<{ granted: boolean }>;
  requestPermissionsAsync: () => Promise<{ granted: boolean }>;
  getExpoPushTokenAsync: (opts: { projectId: string }) => Promise<{ data: string }>;
};
type ConstantsModule = {
  default: {
    expoConfig?: { extra?: { eas?: { projectId?: string } } };
    easConfig?: { projectId?: string };
  };
};

/**
 * Acquire an Expo push token (asking for permission if needed) and register
 * it with the BE. Returns the token on success, or {@code null} when:
 *  - native modules are missing (dev client needs to be rebuilt with
 *    {@code expo prebuild --clean && expo run:ios}),
 *  - the OS denied permission,
 *  - the simulator doesn't support push,
 *  - or the EAS project id isn't configured yet.
 */
export async function registerForPushAsync(): Promise<string | null> {
  try {
    const Device: DeviceModule = require("expo-device");
    const Notifications: NotificationsModule = require("expo-notifications");
    const ConstantsMod: ConstantsModule = require("expo-constants");
    const Constants = ConstantsMod.default;

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
      Constants.expoConfig?.extra?.eas?.projectId ?? Constants.easConfig?.projectId;
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
  } catch {
    return null;
  }
}
