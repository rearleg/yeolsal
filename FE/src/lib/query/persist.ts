import AsyncStorage from "@react-native-async-storage/async-storage";
import { createAsyncStoragePersister } from "@tanstack/query-async-storage-persister";
import { persistQueryClient } from "@tanstack/react-query-persist-client";
import { queryClient } from "./client";

const persister = createAsyncStoragePersister({
  storage: AsyncStorage,
  key: "yeosal.query.v1",
  throttleTime: 1000,
});

export function bootstrapPersist(): void {
  persistQueryClient({
    queryClient,
    persister,
    maxAge: 1000 * 60 * 60 * 24,
    buster: "v1",
    dehydrateOptions: {
      shouldDehydrateQuery: (q) => q.state.status === "success",
    },
  });
}
