import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";
import { captureQueryError } from "../sentry";

export const queryClient = new QueryClient({
  queryCache: new QueryCache({
    onError: (error, query) => {
      captureQueryError(error, { kind: "query", key: query.queryKey });
    },
  }),
  mutationCache: new MutationCache({
    onError: (error, _vars, _ctx, mutation) => {
      captureQueryError(error, { kind: "mutation", key: mutation.options.mutationKey });
    },
  }),
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 1000 * 60 * 60 * 24,
      retry: (failureCount, error) => {
        if (error instanceof Error && /401|403/.test(error.message)) return false;
        return failureCount < 1;
      },
      refetchOnReconnect: "always",
      // Paired with setupReactQueryFocus() in app/_layout.tsx — RN has no
      // window focus event natively, so this only takes effect once the
      // AppState bridge calls focusManager.setFocused(true). Prior to PR-A
      // this was hard-coded false, which is why returning to the app
      // never triggered a refetch.
      refetchOnWindowFocus: true,
    },
    mutations: {
      retry: 0,
    },
  },
});
