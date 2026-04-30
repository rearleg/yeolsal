package com.yeosal.api.notification;

import java.util.List;

/**
 * Sends a push notification to one or more Expo push tokens. Intentionally an
 * interface so tests can inject a no-op or recording fake without setting up a
 * RestTemplate or hitting the network.
 */
public interface ExpoPushClient {
    void send(List<String> tokens, String title, String body);
}
