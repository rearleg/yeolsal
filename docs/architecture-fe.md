# Architecture — FE (Yeosal Mobile App)

## Executive Summary

Expo-managed React Native app for the Yeosal accountability product. Single codebase targets iOS and Android; the same JS bundle is used in dev (Expo Go / dev client) and prod (EAS-built APK/AAB/IPA). Authoritative data lives on the BE; FE caches it via TanStack Query persisted to AsyncStorage and reacts to BE-emitted STOMP events.

## Technology Stack

| Layer | Tech | Version |
|-------|------|---------|
| Runtime | Expo / React Native | 54.0.34 / 0.81.5 |
| UI library | React | 19.1.0 |
| Language | TypeScript (strict) | ~5.9.0 |
| Routing | expo-router | ~6.0.0 |
| Server-state | @tanstack/react-query | 5.100.6 |
| Cache persistence | @tanstack/react-query-persist-client + query-async-storage-persister | 5.100.6 |
| Realtime | @stomp/stompjs | 7.3.0 |
| Storage | @react-native-async-storage/async-storage / expo-secure-store | 2.2.0 / ~15.0.8 |
| Lists | @shopify/flash-list | 2.0.2 |
| Push | expo-notifications | ~0.32.0 |
| Observability | @sentry/react-native | ~7.2.0 |
| Linting | ESLint flat (`@eslint/js` + `typescript-eslint`) | 9 / 8.59 |
| Tests | Jest + jest-expo + @testing-library/react-native | 29.7 / 54.0.17 / 13.3.3 |
| Build | EAS (preview/APK, production/AAB+iOS) | — |

## Architecture Pattern

Provider-wrapped, router-driven, query-cached app:

```
expo-router (app/)
  └─ Root <Stack/> in _layout.tsx
       ├─ <QueryProvider>            ← persisted QueryClient
       │   └─ <AuthProvider>         ← token state, refresh callback
       │       └─ <RealtimeProvider> ← single STOMP client
       │           └─ Screens (Today / Feed / Monthly / Profile / Rooms / ...)
       └─ ErrorBoundary → Sentry
```

- **One STOMP client app-wide.** Screen-level hooks (`useChatRealtime`, etc.) subscribe/unsubscribe through the provider. Never instantiate a second client.
- **All HTTP through `apiRequest<T>`** in `src/api/client.ts`. The wrapper adds `Authorization: Bearer ...`, refreshes on 401 once, and throws `ApiError` (subclass of `Error`).
- **Server state vs client state separation.** Server state lives in TanStack Query under a domain hook (`src/lib/query/hooks/*`). Local UI state stays in component-local `useState` / `useReducer`.
- **Optimistic + dedupe pattern for chat.** WS-pushed messages must not write cache directly — they invalidate or merge using a dedupe key shared with the REST response.

## Data / Cache Architecture

- `QueryClient` is created in `src/providers/QueryProvider.tsx` and persisted to `AsyncStorage` via `react-query-persist-client`.
- Persistence boundary: AsyncStorage is for non-sensitive cache only. Auth tokens (`yeosal.accessToken`, `yeosal.refreshToken`) live in `expo-secure-store`.
- Calling `queryClient.clear()` is forbidden — it nukes the persisted store. Use `invalidateQueries` instead.

## API Surface

The FE consumes:
- REST: `${EXPO_PUBLIC_API_BASE_URL}/...` defaulting to `https://api.rearleg.com/yeolsal/api/v1`.
- WSS: a single `/ws` endpoint derived from the API base URL (https → wss).

Per-domain typed wrappers live in `src/api/{auth,chat,rooms,reflections,notifications}.ts`.

See [`api-contracts-be.md`](./api-contracts-be.md) and [`integration-architecture.md`](./integration-architecture.md).

## Source Tree (FE)

See [`source-tree-analysis.md`](./source-tree-analysis.md) (FE section).

## Development Workflow

- Install: `cd FE && npm install` (root `npm install` also works — root has FE as workspace).
- Run: `npm run android` / `npm run ios` (after `expo start` is booted).
- Verify: `npm run lint && npm run typecheck && npm test` before push.
- Native module changes: `adb uninstall app.yeosal.mobile` and rebuild — Metro reload alone does not reflect native changes.

Detailed: [`development-guide-fe.md`](./development-guide-fe.md), [`../RUNBOOK.md`](../RUNBOOK.md).

## Deployment Architecture

- **Dev**: Expo dev client. JS over Metro; Sentry auto-disabled when `EXPO_PUBLIC_SENTRY_DSN` is empty.
- **Preview**: `eas build --profile preview` produces APK for sideload testing.
- **Production**: `eas build --profile production` produces AAB (Google Play) and iOS bundle (TestFlight / App Store).
- The FE never embeds the Kakao REST API key. Kakao auth is initiated by hitting BE's `/auth/kakao/authorize`.

## Testing Strategy

- Discovery glob: `FE/src/**/__tests__/**/*.test.{ts,tsx}`.
- Sentry and `Animated/NativeAnimatedHelper` are mocked globally in `jest.setup.ts`. Per-test re-mocks are forbidden.
- TanStack Query hooks must wrap renderHook in a `QueryClientProvider` and stub `fetch`.
- Realtime hooks mock `RealtimeProvider`; never open a real WebSocket in tests.
- Coverage target: 80% on domain/utility logic. Visual components rely more on visual regression than markup snapshots.

## Cross-Cutting Concerns

| Concern | Location |
|---------|----------|
| Auth tokens | `expo-secure-store` (keys: `yeosal.accessToken`, `yeosal.refreshToken`) |
| API client | `src/api/client.ts` |
| Realtime client | `src/lib/realtime/`, mounted in `src/providers/RealtimeProvider.tsx` |
| Push tokens | `src/lib/push.ts`; tokens registered with BE — never persisted in SecureStore |
| Sentry | `src/lib/sentry.ts` (auto-disabled when DSN empty) |
| Theme tokens | `src/theme/` (Risograph + neobrutalist palette per `design-system.md`) |
