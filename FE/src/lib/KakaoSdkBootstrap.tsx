import { initializeKakaoSDK } from "@react-native-kakao/core";
import { useEffect } from "react";
import { KAKAO_NATIVE_APP_KEY } from "../api/config";

export function KakaoSdkBootstrap() {
  useEffect(() => {
    if (!KAKAO_NATIVE_APP_KEY) return;
    void initializeKakaoSDK(KAKAO_NATIVE_APP_KEY).catch(() => {
      // Sharing remains available through the generic system share sheet.
    });
  }, []);
  return null;
}
