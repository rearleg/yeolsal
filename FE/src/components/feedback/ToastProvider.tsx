import { useCallback, useEffect, useRef, useState, type PropsWithChildren } from "react";
import { setToastBridge, type ToastVariant } from "../../lib/toast";
import { ToastHost } from "./ToastHost";

export interface ToastEntry {
  id: number;
  variant: ToastVariant;
  message: string;
}

const MAX_VISIBLE = 3;
const DISMISS_MS = 3500;

export function ToastProvider({ children }: PropsWithChildren) {
  const [toasts, setToasts] = useState<ToastEntry[]>([]);
  const idRef = useRef(0);

  const show = useCallback((variant: ToastVariant, message: string) => {
    const id = ++idRef.current;
    setToasts((prev) => {
      const next = [...prev, { id, variant, message }];
      if (next.length > MAX_VISIBLE) {
        return next.slice(next.length - MAX_VISIBLE);
      }
      return next;
    });
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, DISMISS_MS);
  }, []);

  useEffect(() => {
    setToastBridge({ show });
    return () => setToastBridge(null);
  }, [show]);

  return (
    <>
      {children}
      <ToastHost toasts={toasts} />
    </>
  );
}
