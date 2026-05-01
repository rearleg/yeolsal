export type ToastVariant = "info" | "success" | "warning" | "danger";

interface ToastBridge {
  show: (variant: ToastVariant, message: string) => void;
}

let bridge: ToastBridge | null = null;

export function setToastBridge(next: ToastBridge | null): void {
  bridge = next;
}

export const toast = {
  info: (m: string) => bridge?.show("info", m),
  success: (m: string) => bridge?.show("success", m),
  warning: (m: string) => bridge?.show("warning", m),
  error: (m: string) => bridge?.show("danger", m),
};
