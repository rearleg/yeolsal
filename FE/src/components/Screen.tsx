import { type PropsWithChildren } from "react";
import { StyleSheet, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { surface } from "../theme/tokens";
import { layout, space } from "../theme/spacing";
import { Text } from "./ui/Text";
import { BottomNav } from "./BottomNav";

type Props = PropsWithChildren<{
  title: string;
  showFooter?: boolean;
}>;

export function Screen({ children, title, showFooter = true }: Props) {
  return (
    <SafeAreaView style={styles.screen}>
      <View style={styles.header}>
        <Text variant="h2" numberOfLines={1}>
          {title}
        </Text>
      </View>
      <View style={styles.content}>{children}</View>
      {showFooter ? <BottomNav /> : null}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
    backgroundColor: surface.page
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
