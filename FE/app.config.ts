// Dynamic Expo config that extends app.json.
// Production builds get strict App Transport Security (HTTPS only).
// Development & preview builds get a dev-only ATS exemption so Metro can be
// reached over plain HTTP via LAN IP. EAS sets EAS_BUILD_PROFILE during build;
// when unset (local prebuild / `expo start`) we treat it as a dev environment
// so the local iOS / Android app can connect to the bundler.

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

export default ({ config }: ConfigContext): ExpoConfig => {
  if (isProductionBuild) {
    return config as ExpoConfig;
  }

  const ios = (config.ios ?? {}) as IosBase;
  const android = (config.android ?? {}) as AndroidBase;

  return {
    ...(config as ExpoConfig),
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
