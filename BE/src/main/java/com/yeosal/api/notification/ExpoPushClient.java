package com.yeosal.api.notification;

import java.util.List;

/**
 * Sends a push notification to one or more Expo push tokens. Intentionally an
 * interface so tests can inject a no-op or recording fake without setting up a
 * RestTemplate or hitting the network.
 *
 * <p>Returns {@code true} when the HTTP call to Expo completed without
 * throwing, {@code false} otherwise. NotificationService uses the result to
 * gate dedup-log writes so that a failed send does not silently block the
 * next retry cycle.
 */
public interface ExpoPushClient {
    boolean send(List<String> tokens, String title, String body);
}
