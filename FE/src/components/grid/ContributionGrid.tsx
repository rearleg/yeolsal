import { useCallback, useMemo, useRef, type ReactNode } from "react";
import { Pressable, ScrollView, StyleSheet, View } from "react-native";
import Svg, { G, Rect, Text as SvgText } from "react-native-svg";
import type { GrassDayDto } from "../../api/types";
import { palette, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";
import { Text } from "../ui/Text";
import { bucketColor, bucketDescription, bucketFor, type Bucket } from "../../lib/bucket";
import {
  buildGridLayout,
  monthLabels,
  type IsoDate,
  type GridLayout
} from "../../lib/calendar";

const CELL = 12;
const GAP = 3;
const STEP = CELL + GAP;
const LABEL_GUTTER = 24;
const TOP_GUTTER = 18;
const ROW_INSET_FOR_LABEL = 4;

// Row 0=Mon … Row 6=Sun. Show every-other label so the grid stays compact.
const WEEKDAY_LABELS = ["", "화", "", "목", "", "토", ""];

interface ContributionGridProps {
  days: GrassDayDto[];
  selectedDate?: IsoDate | null;
  onSelect?: (day: GrassDayDto) => void;
}

interface IndexedDay {
  iso: IsoDate;
  data: GrassDayDto | null;
  bucket: Bucket;
}

export function ContributionGrid({ days, selectedDate, onSelect }: ContributionGridProps) {
  const scrollRef = useRef<ScrollView | null>(null);
  const hasScrolledToEndRef = useRef(false);

  const handleContentSizeChange = useCallback(() => {
    if (hasScrolledToEndRef.current) {
      return;
    }
    scrollRef.current?.scrollToEnd({ animated: false });
    hasScrolledToEndRef.current = true;
  }, []);

  const { layout, byDate, totalWidth, totalHeight, months } = useMemo(() => {
    const isoDays = days.map((d) => d.date);
    const builtLayout = buildGridLayout(isoDays);
    const indexed = new Map<IsoDate, IndexedDay>();
    for (const day of days) {
      indexed.set(day.date, { iso: day.date, data: day, bucket: bucketFor(day) });
    }
    return {
      layout: builtLayout,
      byDate: indexed,
      totalWidth: LABEL_GUTTER + builtLayout.columnCount * STEP,
      totalHeight: TOP_GUTTER + 7 * STEP,
      months: monthLabels(builtLayout)
    };
  }, [days]);

  if (layout.columnCount === 0) {
    return (
      <View style={styles.empty}>
        <Text variant="bodySmall" color={palette.inkMute}>
          아직 표시할 잔디가 없어요.
        </Text>
      </View>
    );
  }

  return (
    <View style={styles.wrap}>
      <ScrollView
        ref={scrollRef}
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.scroll}
        onContentSizeChange={handleContentSizeChange}
      >
        <View>
          <Svg width={totalWidth} height={totalHeight} accessibilityLabel="잔디 기여도 그리드">
            <Group layout={layout} byDate={byDate} months={months} selectedDate={selectedDate ?? null} />
          </Svg>
          <Tap layout={layout} byDate={byDate} onSelect={onSelect} />
        </View>
      </ScrollView>
      <Legend />
    </View>
  );
}

interface GroupProps {
  layout: GridLayout;
  byDate: Map<IsoDate, IndexedDay>;
  months: ReturnType<typeof monthLabels>;
  selectedDate: IsoDate | null;
}

function Group({ layout, byDate, months, selectedDate }: GroupProps) {
  const monthLabelEls = months.map(({ col, label }) => (
    <SvgText
      key={`${col}-${label}`}
      x={LABEL_GUTTER + col * STEP}
      y={12}
      fontSize={9}
      fontWeight="600"
      fill={palette.inkMute}
    >
      {label}
    </SvgText>
  ));

  const weekdayEls = WEEKDAY_LABELS.map((label, row) => {
    if (!label) {
      return null;
    }
    return (
      <SvgText
        key={`wk-${row}`}
        x={0}
        y={TOP_GUTTER + row * STEP + CELL - ROW_INSET_FOR_LABEL}
        fontSize={9}
        fontWeight="600"
        fill={palette.inkMute}
      >
        {label}
      </SvgText>
    );
  });

  const cellEls: ReactNode[] = [];
  for (let col = 0; col < layout.columnCount; col += 1) {
    for (let row = 0; row < 7; row += 1) {
      const iso = layout.cells[col][row];
      if (!iso) {
        continue;
      }
      const cell = byDate.get(iso);
      const bucket = cell?.bucket ?? 0;
      const fill = bucketColor[bucket];
      const isSelected = iso === selectedDate;
      cellEls.push(
        <Rect
          key={iso}
          x={LABEL_GUTTER + col * STEP}
          y={TOP_GUTTER + row * STEP}
          width={CELL}
          height={CELL}
          rx={2}
          ry={2}
          fill={fill}
          stroke={isSelected ? palette.ink : bucket === 0 ? palette.border : "transparent"}
          strokeWidth={isSelected ? 1.5 : bucket === 0 ? 1 : 0}
        />
      );
    }
  }

  return (
    <G>
      {monthLabelEls}
      {weekdayEls}
      {cellEls}
    </G>
  );
}

interface TapProps {
  layout: GridLayout;
  byDate: Map<IsoDate, IndexedDay>;
  onSelect?: (day: GrassDayDto) => void;
}

// Transparent absolutely-positioned Pressable per filled cell, mirroring the
// SVG layout. Keeps SVG static (faster) while preserving tap targets and
// per-cell accessibility labels.
function Tap({ layout, byDate, onSelect }: TapProps) {
  const targets: Array<{ key: string; x: number; y: number; iso: IsoDate; data: GrassDayDto | null; bucket: Bucket }> = [];
  for (let col = 0; col < layout.columnCount; col += 1) {
    for (let row = 0; row < 7; row += 1) {
      const iso = layout.cells[col][row];
      if (!iso) {
        continue;
      }
      const cell = byDate.get(iso);
      targets.push({
        key: iso,
        x: LABEL_GUTTER + col * STEP,
        y: TOP_GUTTER + row * STEP,
        iso,
        data: cell?.data ?? null,
        bucket: cell?.bucket ?? 0
      });
    }
  }

  return (
    <View pointerEvents="box-none" style={StyleSheet.absoluteFill}>
      {targets.map((t) => {
        const description = bucketDescription[t.bucket];
        const completed = t.data?.completedTodoCount ?? 0;
        return (
          <Pressable
            key={t.key}
            accessibilityRole="button"
            accessibilityLabel={`${t.iso} ${description} 완료한 할 일 ${completed}개`}
            onPress={() => {
              if (t.data && onSelect) {
                onSelect(t.data);
              }
            }}
            style={{
              position: "absolute",
              left: t.x - 2,
              top: t.y - 2,
              width: CELL + 4,
              height: CELL + 4
            }}
          />
        );
      })}
    </View>
  );
}

function Legend() {
  return (
    <View style={styles.legend}>
      <Text variant="caption" color={palette.inkMute}>적음</Text>
      {([0, 1, 2, 3, 4] as Bucket[]).map((b) => (
        <View
          key={b}
          accessibilityElementsHidden
          style={[styles.legendBox, { backgroundColor: bucketColor[b], borderColor: b === 0 ? palette.border : "transparent" }]}
        />
      ))}
      <Text variant="caption" color={palette.inkMute}>많음</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: {
    gap: space[2],
    paddingVertical: space[1],
    backgroundColor: surface.card,
    borderRadius: 12,
    overflow: "hidden"
  },
  scroll: {
    paddingHorizontal: space[2]
  },
  empty: {
    minHeight: 120,
    alignItems: "center",
    justifyContent: "center"
  },
  legend: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "flex-end",
    gap: 4,
    paddingHorizontal: space[3]
  },
  legendBox: {
    width: 12,
    height: 12,
    borderRadius: 2,
    borderWidth: 1
  }
});
