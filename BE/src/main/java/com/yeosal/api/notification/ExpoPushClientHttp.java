package com.yeosal.api.notification;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP-backed {@link ExpoPushClient} that POSTs to Expo's push endpoint.
 *
 * <p>A failed push should not unwind the surrounding business transaction, so
 * we catch and log instead of rethrowing — the dedup row is still written and
 * the user simply misses one nudge cycle.
 */
@Component
public class ExpoPushClientHttp implements ExpoPushClient {

    private static final Logger log = LoggerFactory.getLogger(ExpoPushClientHttp.class);
    private static final String ENDPOINT = "https://exp.host/--/api/v2/push/send";

    private final RestTemplate http = new RestTemplate();

    @Override
    public void send(List<String> tokens, String title, String body) {
        if (tokens == null || tokens.isEmpty()) {
            return;
        }
        Map<String, Object> payload = Map.of(
                "to", tokens,
                "title", title,
                "body", body
        );
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            http.postForEntity(ENDPOINT, new HttpEntity<>(payload, headers), String.class);
        } catch (Exception ex) {
            log.warn("Expo push send failed (tokens={}): {}", tokens.size(), ex.getMessage());
        }
    }
}
