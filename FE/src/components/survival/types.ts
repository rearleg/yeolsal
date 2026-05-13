// Shared types for the survival surface so iconMap can import without
// pulling the full SurvivalChip module (avoids circular imports).

export type SurvivalState = "ACTIVE" | "YELLOW" | "RED" | "SPECTATOR";
