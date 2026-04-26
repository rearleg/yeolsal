import { useEffect } from "react";
import { router } from "expo-router";
import { useAuth } from "./AuthContext";

export function useRequireAuth() {
  const auth = useAuth();

  useEffect(() => {
    if (!auth.loading && !auth.user) {
      router.replace("/login");
    }
  }, [auth.loading, auth.user]);

  return auth;
}
