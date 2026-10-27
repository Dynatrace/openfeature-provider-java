package com.dynatrace.openfeature;

import com.launchdarkly.eventsource.*;
import com.launchdarkly.eventsource.background.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Manages the SSE connection to the Ably event stream.
 *
 * <p>Message payloads are JSON envelopes parsed into {@link SseMessage} and dispatched to the
 * {@code onMessage} callback. Unparseable payloads are dropped (logged at debug). The SSE URL is
 * treated as an opaque HTTP endpoint — no Ably SDK is used. Reconnection backoff is delegated to
 * {@code okhttp-eventsource}.
 */
final class SseManager {

    private static final Logger log = LoggerFactory.getLogger(SseManager.class);

    private final String sseUrl;
    private final Runnable onConnected;
    private final Runnable onDisconnected;
    private final Consumer<SseMessage> onMessage;

    private BackgroundEventSource backgroundEventSource;

    SseManager(String sseUrl, Runnable onConnected, Runnable onDisconnected, Consumer<SseMessage> onMessage) {
        this.sseUrl = sseUrl;
        this.onConnected = onConnected;
        this.onDisconnected = onDisconnected;
        this.onMessage = onMessage;
    }

    void start() {
        EventSource.Builder eventSourceBuilder = new EventSource.Builder(ConnectStrategy.http(URI.create(sseUrl)).clientBuilderActions(clientBuilder ->
                clientBuilder
                        .readTimeout(150, TimeUnit.SECONDS)
        ));
        backgroundEventSource = new BackgroundEventSource.Builder(createHandler(), eventSourceBuilder).build();
        backgroundEventSource.start();
        log.info("SSE manager started (url={})", maskUrl(sseUrl));
    }

    /** Package-private for testing — creates the event handler that routes SSE callbacks. */
    BackgroundEventHandler createHandler() {
        return new BackgroundEventHandler() {
            @Override
            public void onOpen() {
                log.info("SSE connection opened (url={})", maskUrl(sseUrl));
                onConnected.run();
            }

            @Override
            public void onClosed() {
                log.info("SSE connection closed");
                onDisconnected.run();
            }

            @Override
            public void onMessage(String event, MessageEvent messageEvent) {
                String data = messageEvent.getData();
                SseMessage parsed = SseMessage.parse(data);
                if (parsed == null) {
                    log.debug("SSE event received (event={}) but payload unparseable; ignoring", event);
                    return;
                }
                log.debug("SSE event received (event={}, type={}, lastModified={})",
                        event, parsed.type, parsed.lastModified);
                onMessage.accept(parsed);
            }

            @Override
            public void onComment(String comment) {
                // keep-alive comments; ignore
            }

            @Override
            public void onError(Throwable t) {
                log.warn("SSE error (url={}): {}", maskUrl(sseUrl), t.getMessage());
                onDisconnected.run();
            }
        };
    }

    void stop() {
        BackgroundEventSource bes = backgroundEventSource;
        if (bes != null) {
            try {
                bes.close();
            } catch (Exception e) {
                log.warn("error closing SSE connection: {}", e.getMessage());
            }
            log.info("SSE manager stopped");
        }
    }

    /** Masks the query string (contains Ably credentials) for safe logging. */
    private static String maskUrl(String url) {
        int q = url.indexOf('?');
        return q >= 0 ? url.substring(0, q) + "?<redacted>" : url;
    }
}
