import { QueryClientProvider } from "@tanstack/react-query";
import { useEffect, type PropsWithChildren } from "react";
import { queryClient } from "../lib/query/client";
import { bootstrapPersist } from "../lib/query/persist";

export function QueryProvider({ children }: PropsWithChildren) {
  useEffect(() => {
    bootstrapPersist();
  }, []);
  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
