// Page-level sub-mode provider (Story 1.5 AC7).
//
// The single seat that sets the sub-mode for everything underneath.
// Leaf components do NOT read the subMode prop directly — they call
// useTheme() from "@/theme/useTheme" and consume the resolved tokens.
// This invariant is the load-bearing piece of UX cross-cutting rule #9:
// sub-mode is a page-level decision, never a leaf branching condition.

import { type ReactNode } from "react";
import { SubModeContextProvider, type SubModeId } from "../theme/useTheme";

export type SubMode = SubModeId | null;

interface SubModeProviderProps {
  readonly subMode: SubMode;
  readonly children: ReactNode;
}

export function SubModeProvider({ subMode, children }: SubModeProviderProps) {
  return (
    <SubModeContextProvider value={subMode}>{children}</SubModeContextProvider>
  );
}
