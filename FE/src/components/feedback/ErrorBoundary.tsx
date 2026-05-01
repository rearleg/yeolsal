import { Component, type ErrorInfo, type PropsWithChildren, type ReactNode } from "react";
import { Button } from "../ui/Button";
import { EmptyState } from "../ui/EmptyState";

interface State {
  error: Error | null;
}

export class ErrorBoundary extends Component<PropsWithChildren, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    if (__DEV__) {
      // eslint-disable-next-line no-console
      console.error("[ErrorBoundary]", error, info.componentStack);
    }
    // Production hook for Sentry — wired in a later sprint.
  }

  reset = (): void => {
    this.setState({ error: null });
  };

  render(): ReactNode {
    const { error } = this.state;
    if (!error) return this.props.children;
    return (
      <EmptyState
        title="문제가 발생했어요"
        description={__DEV__ ? error.message : "다시 시도해 주세요."}
        action={<Button label="다시 시도" tone="primary" size="md" fullWidth onPress={this.reset} />}
      />
    );
  }
}
