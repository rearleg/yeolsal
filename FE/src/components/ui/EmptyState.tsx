import type { ReactNode } from "react";
import { StyleSheet, View } from "react-native";
import { space } from "../../theme/spacing";
import { palette } from "../../theme/tokens";
import { Card } from "./Card";
import { Text } from "./Text";

interface EmptyStateProps {
  title: string;
  description?: string;
  action?: ReactNode;
}

export function EmptyState({ title, description, action }: EmptyStateProps) {
  return (
    <Card tone="outline" size="lg" style={styles.container}>
      <View
        accessibilityRole="text"
        accessibilityLabel={description ? `${title}. ${description}` : title}
      >
        <Text variant="h3" align="center">
          {title}
        </Text>
        {description ? (
          <Text variant="body" color={palette.inkMute} align="center" style={styles.description}>
            {description}
          </Text>
        ) : null}
      </View>
      {action ? <View style={styles.action}>{action}</View> : null}
    </Card>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: "center",
    gap: space[3],
  },
  description: {
    marginTop: space[2],
  },
  action: {
    marginTop: space[3],
    alignSelf: "stretch",
  },
});
