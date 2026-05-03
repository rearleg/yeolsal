package com.yeosal.api.notification;

import java.util.List;
import java.util.Map;

/**
 * Sends a push notification to one or more Expo push tokens. Intentionally an
 * interface so tests can inject a no-op or recording fake without setting up a
 * RestTemplate or hitting the network.
 *
 * <p>{@code data} is a structured payload Expo forwards verbatim to the device.
 * The FE listener inspects {@code data.kind} to decide which React Query
 * caches to invalidate (e.g. {@code FRIEND_GOAL} → friend feed,
 * {@code MILESTONE} → room messages). May be {@link Map#of()} when no kind
 * routing is needed.
 *
 * <p>Returns {@code true} when the HTTP call to Expo completed without
 * throwing, {@code false} otherwise. NotificationService uses the result to
 * gate dedup-log writes so that a failed send does not silently block the
 * next retry cycle.
 */
public interface ExpoPushClient {
    boolean send(List<String> tokens, String title, String body, Map<String, Object> data);
}
