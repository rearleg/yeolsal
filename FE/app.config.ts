// Dynamic Expo config that extends app.json.
// Production builds get strict App Transport Security (HTTPS only).
// Development & preview builds get a dev-only ATS exemption so Metro can be
// reached over plain HTTP via LAN IP. EAS sets EAS_BUILD_PROFILE during build;
// when unset (local prebuild / `expo start`) we treat it as a dev environment
// so the local iOS / Android app can connect to the bundler.
//
// Story 6.2 AC7 — also merges the @react-native-kakao/core expo-config-plugin
// into the existing app.json plugins array, with the Native App Key
// substituted from EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY (Kakao Developers
// Console; safe to ship in the client bundle — see Trap #3 in the story).

import type { ConfigContext, ExpoConfig } from "expo/config";

const buildProfile = process.env.EAS_BUILD_PROFILE;
const isProductionBuild = buildProfile === "production";

interface IosBase {
  infoPlist?: Record<string, unknown>;
  [key: string]: unknown;
}

interface AndroidBase {
  [key: string]: unknown;
}

// Story 6.2 AC7 — dev / OSS forks may omit the key. In that case the
// plugin is not registered, so Expo config evaluation and Metro still work.
const KAKAO_NATIVE_APP_KEY =
  process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY ?? "";

const KAKAO_PLUGIN: [string, Record<string, unknown>] = [
  "@react-native-kakao/core",
  {
    nativeAppKey: KAKAO_NATIVE_APP_KEY,
    android: { authCodeHandlerActivity: false },
    ios: { handleKakaoOpenUrl: false },
  },
];

function withKakaoPlugin(config: ExpoConfig): ExpoConfig["plugins"] {
  const existing = (config.plugins ?? []) as ExpoConfig["plugins"];
  if (!KAKAO_NATIVE_APP_KEY) {
    return existing;
  }
  return [...(existing ?? []), KAKAO_PLUGIN];
}

export default ({ config }: ConfigContext): ExpoConfig => {
  const base = config as ExpoConfig;
  const withKakao: ExpoConfig = {
    ...base,
    plugins: withKakaoPlugin(base),
  };

  if (isProductionBuild) {
    return withKakao;
  }

  const ios = (withKakao.ios ?? {}) as IosBase;
  const android = (withKakao.android ?? {}) as AndroidBase;

  return {
    ...withKakao,
    ios: {
      ...ios,
      infoPlist: {
        ...(ios.infoPlist ?? {}),
        NSAppTransportSecurity: {
          NSAllowsArbitraryLoads: true,
          NSAllowsLocalNetworking: true
        }
      }
    },
    android: {
      ...android,
      usesCleartextTraffic: true
    }
  };
};
