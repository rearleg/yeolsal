import { QueryClient } from "@tanstack/react-query";

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      gcTime: 1000 * 60 * 60 * 24,
      retry: (failureCount, error) => {
        if (error instanceof Error && /401|403/.test(error.message)) return false;
        return failureCount < 1;
      },
      refetchOnReconnect: "always",
      refetchOnWindowFocus: false,
    },
    mutations: {
      retry: 0,
    },
  },
});
