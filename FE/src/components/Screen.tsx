import { type PropsWithChildren } from "react";
import { KeyboardAvoidingView, Platform, StyleSheet, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { surface } from "../theme/tokens";
import { layout, space } from "../theme/spacing";
import { Text } from "./ui/Text";

type Props = PropsWithChildren<{
  // Default top-bar title. Pass an empty string or omit to suppress the
  // built-in header — useful for screens that render a custom header
  // (e.g. the date-driven Today header) inside their own content area.
  title?: string;
  // No-op — see (tabs)/_layout.tsx for the tab bar.
  showFooter?: boolean;
  // Wrap content in KeyboardAvoidingView so text inputs stay visible
  // above the keyboard. Default true; opt out for screens that manage
  // their own keyboard layout.
  avoidKeyboard?: boolean;
}>;

export function Screen({ children, title, avoidKeyboard = true }: Props) {
  const body = <View style={styles.content}>{children}</View>;
  return (
    <SafeAreaView style={styles.screen}>
      {title ? (
        <View style={styles.header}>
          <Text variant="h2" numberOfLines={1}>
            {title}
          </Text>
        </View>
      ) : null}
      {avoidKeyboard ? (
        <KeyboardAvoidingView
          style={styles.flex}
          behavior={Platform.OS === "ios" ? "padding" : "height"}
        >
          {body}
        </KeyboardAvoidingView>
      ) : (
        body
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: surface.page
  },
  flex: {
    flex: 1
  },
  header: {
    minHeight: layout.headerHeight,
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: layout.pageHorizontal,
    paddingTop: space[1],
    paddingBottom: space[2]
  },
  content: {
    flex: 1,
    paddingHorizontal: layout.pageHorizontal,
    paddingTop: space[2]
  }
});
