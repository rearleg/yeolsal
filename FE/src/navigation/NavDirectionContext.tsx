import { router } from "expo-router";
import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode
} from "react";

export type TabAnimation = "slide_from_right" | "slide_from_left" | "default";

interface NavDirectionContextValue {
  animation: TabAnimation;
  navigateToTab: (href: string, nextIndex: number) => void;
}

const NavDirectionContext = createContext<NavDirectionContextValue | null>(null);

export function NavDirectionProvider({ children }: { children: ReactNode }) {
  const [animation, setAnimation] = useState<TabAnimation>("default");
  const lastIndexRef = useRef<number>(0);

  const navigateToTab = useCallback((href: string, nextIndex: number) => {
    const lastIndex = lastIndexRef.current;
    let next: TabAnimation = "default";
    if (nextIndex > lastIndex) {
      next = "slide_from_right";
    } else if (nextIndex < lastIndex) {
      next = "slide_from_left";
    }
    setAnimation(next);
    lastIndexRef.current = nextIndex;
    router.replace(href as never);
  }, []);

  const value = useMemo<NavDirectionContextValue>(
    () => ({ animation, navigateToTab }),
    [animation, navigateToTab]
  );

  return (
    <NavDirectionContext.Provider value={value}>
      {children}
    </NavDirectionContext.Provider>
  );
}

export function useNavDirection(): NavDirectionContextValue {
  const ctx = useContext(NavDirectionContext);
  if (!ctx) {
    throw new Error("useNavDirection must be used within a NavDirectionProvider");
  }
  return ctx;
}
