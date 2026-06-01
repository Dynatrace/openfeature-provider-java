package com.dynatrace.openfeature;

import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.background.BackgroundEventHandler;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SseManagerTest {

    private static final String SSE_URL =
            "https://realtime.ably.io/event-stream?key=test&channels=test-channel";

    @Test
    void onOpenCallsOnConnected() throws Exception {
        AtomicInteger connected = new AtomicInteger();
        SseManager mgr = new SseManager(SSE_URL, connected::incrementAndGet, () -> {}, m -> {});
        BackgroundEventHandler handler = mgr.createHandler();

        handler.onOpen();

        assertEquals(1, connected.get());
    }

    @Test
    void onClosedCallsOnDisconnected() throws Exception {
        AtomicInteger disconnected = new AtomicInteger();
        SseManager mgr = new SseManager(SSE_URL, () -> {}, disconnected::incrementAndGet, m -> {});
        BackgroundEventHandler handler = mgr.createHandler();

        handler.onClosed();

        assertEquals(1, disconnected.get());
    }

    @Test
    void onErrorCallsOnDisconnected() {
        AtomicInteger disconnected = new AtomicInteger();
        SseManager mgr = new SseManager(SSE_URL, () -> {}, disconnected::incrementAndGet, m -> {});
        BackgroundEventHandler handler = mgr.createHandler();

        handler.onError(new RuntimeException("network failure"));

        assertEquals(1, disconnected.get());
    }

    @Test
    void onMessageParsesAndDispatchesParsedEnvelope() throws Exception {
        AtomicReference<SseMessage> received = new AtomicReference<>();
        SseManager mgr = new SseManager(SSE_URL, () -> {}, () -> {}, received::set);
        BackgroundEventHandler handler = mgr.createHandler();

        handler.onMessage("flag-update", new MessageEvent(
                "{\"type\":\"refetchConfig\",\"etag\":\"\\\"abc123\\\"\",\"lastModified\":1771622898}"));

        SseMessage msg = received.get();
        assertNotNull(msg);
        assertEquals("refetchConfig", msg.type);
        assertEquals("\"abc123\"", msg.etag);
        assertEquals(1771622898L, msg.lastModified);
    }

    @Test
    void onMessageDropsUnparseablePayload() throws Exception {
        AtomicInteger messages = new AtomicInteger();
        SseManager mgr = new SseManager(SSE_URL, () -> {}, () -> {}, m -> messages.incrementAndGet());
        BackgroundEventHandler handler = mgr.createHandler();

        handler.onMessage("flag-update", new MessageEvent("not-json"));

        assertEquals(0, messages.get());
    }

    @Test
    void onMessageDropsPayloadMissingType() throws Exception {
        AtomicInteger messages = new AtomicInteger();
        SseManager mgr = new SseManager(SSE_URL, () -> {}, () -> {}, m -> messages.incrementAndGet());
        BackgroundEventHandler handler = mgr.createHandler();

        handler.onMessage("flag-update", new MessageEvent("{\"lastModified\":1}"));

        assertEquals(0, messages.get());
    }

    @Test
    void onCommentDoesNotCallAnyCallback() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Runnable count = calls::incrementAndGet;
        SseManager mgr = new SseManager(SSE_URL, count, count, m -> calls.incrementAndGet());
        BackgroundEventHandler handler = mgr.createHandler();

        handler.onComment(":heartbeat");

        assertEquals(0, calls.get());
    }

    @Test
    void onOpenDoesNotCallDisconnected() throws Exception {
        AtomicInteger disconnected = new AtomicInteger();
        SseManager mgr = new SseManager(SSE_URL, () -> {}, disconnected::incrementAndGet, m -> {});
        BackgroundEventHandler handler = mgr.createHandler();

        handler.onOpen();

        assertEquals(0, disconnected.get());
    }

    @Test
    void stopIsIdempotentWhenNotStarted() {
        SseManager mgr = new SseManager(SSE_URL, () -> {}, () -> {}, m -> {});
        assertDoesNotThrow(mgr::stop);
        assertDoesNotThrow(mgr::stop); // double stop
    }

    @Test
    void onMessageCallsOnlyMessageCallback() throws Exception {
        AtomicInteger connected = new AtomicInteger();
        AtomicInteger disconnected = new AtomicInteger();
        AtomicInteger messages = new AtomicInteger();
        SseManager mgr = new SseManager(SSE_URL, connected::incrementAndGet,
                disconnected::incrementAndGet, m -> messages.incrementAndGet());
        BackgroundEventHandler handler = mgr.createHandler();

        handler.onMessage("update", new MessageEvent("{\"type\":\"refetchConfig\"}"));

        assertEquals(0, connected.get());
        assertEquals(0, disconnected.get());
        assertEquals(1, messages.get());
    }
}
