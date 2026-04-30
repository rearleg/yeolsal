import { apiRequest, type ApiEnvelope } from "./client";

export interface NotificationPrefs {
  goalNudgeEnabled: boolean;
  reflectionNudgeEnabled: boolean;
  eventHooksEnabled: boolean;
  quietStartHour: number;
  quietEndHour: number;
}

export interface RegisteredPushToken {
  id: number;
  platform: string;
}

export async function getNotificationPrefs(): Promise<NotificationPrefs> {
  const envelope = await apiRequest<ApiEnvelope<NotificationPrefs>>("/me/notification-prefs");
  return envelope.data;
}

export async function updateNotificationPrefs(prefs: NotificationPrefs): Promise<NotificationPrefs> {
  const envelope = await apiRequest<ApiEnvelope<NotificationPrefs>>("/me/notification-prefs", {
    method: "PUT",
    body: JSON.stringify(prefs),
  });
  return envelope.data;
}

export async function registerPushToken(
  token: string,
  platform: "ios" | "android"
): Promise<RegisteredPushToken> {
  const envelope = await apiRequest<ApiEnvelope<RegisteredPushToken>>("/me/push-tokens", {
    method: "POST",
    body: JSON.stringify({ token, platform }),
  });
  return envelope.data;
}

export async function deletePushToken(id: number): Promise<void> {
  await apiRequest<void>(`/me/push-tokens/${id}`, { method: "DELETE" });
}
