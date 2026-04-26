import { StyleSheet, Text } from "react-native";
import { Link } from "expo-router";
import { GrassGrid } from "../src/components/GrassGrid";
import { NeoCard } from "../src/components/NeoCard";
import { Screen } from "../src/components/Screen";
import { grassDays } from "../src/domain/mockData";
import { colors } from "../src/theme/tokens";

export default function FriendProfileScreen() {
  return (
    <Screen title="민서의 잔디">
      <Link href="/profile" style={styles.link}>내 프로필</Link>
      <NeoCard tone="pink">
        <Text style={styles.name}>이번 달 20일 성공</Text>
        <GrassGrid days={grassDays} />
      </NeoCard>
    </Screen>
  );
}

const styles = StyleSheet.create({
  link: { color: colors.ink, fontWeight: "900", textDecorationLine: "underline" },
  name: { color: colors.ink, fontSize: 22, fontWeight: "900", marginBottom: 14 }
});
