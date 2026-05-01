import { useFocusEffect, router } from "expo-router";
import { useCallback } from "react";
import { Alert, BackHandler, Platform } from "react-native";

interface Options {
  /**
   * Custom handler invoked first. Return `true` to mark the back press as
   * handled and skip default behaviour (router.back / exit confirm).
   */
  onBack?: () => boolean | void;
  /**
   * If the screen is the root of its stack (router.canGoBack() === false),
   * show a confirm dialog before quitting the app. Use only on the home tab.
   */
  confirmExitOnRoot?: boolean;
}

export function useAndroidBack({ onBack, confirmExitOnRoot }: Options = {}): void {
  useFocusEffect(
    useCallback(() => {
      if (Platform.OS !== "android") return;
      const sub = BackHandler.addEventListener("hardwareBackPress", () => {
        if (onBack) {
          const handled = onBack();
          if (handled) return true;
        }
        if (router.canGoBack()) {
          router.back();
          return true;
        }
        if (confirmExitOnRoot) {
          Alert.alert("앱 종료", "앱을 종료할까요?", [
            { text: "취소", style: "cancel" },
            { text: "종료", style: "destructive", onPress: () => BackHandler.exitApp() },
          ]);
          return true;
        }
        return false;
      });
      return () => sub.remove();
    }, [onBack, confirmExitOnRoot]),
  );
}
