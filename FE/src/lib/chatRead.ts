// Per-room lastReadMessageId persistence. Backed by AsyncStorage so the
// "unread" badge on the chat tab survives app restarts. Single source of
// truth used by the chat tab list (compares against the latest message id)
// and the room chat screen (advances the watermark on enter / new message).
import AsyncStorage from "@react-native-async-storage/async-storage";

const KEY_PREFIX = "chat:lastRead:";

export async function getLastReadId(roomId: number): Promise<number | null> {
  const raw = await AsyncStorage.getItem(KEY_PREFIX + roomId);
  if (raw == null) return null;
  const n = Number.parseInt(raw, 10);
  return Number.isFinite(n) ? n : null;
}

export async function setLastReadId(roomId: number, messageId: number): Promise<void> {
  await AsyncStorage.setItem(KEY_PREFIX + roomId, String(messageId));
}
