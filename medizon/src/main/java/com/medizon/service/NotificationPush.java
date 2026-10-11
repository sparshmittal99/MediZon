package com.medizon.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live connections (Server-Sent Events) from browsers. When a notification is created, the user's open
 * connections get a "notify" event right away, and the page then fetches it; no waiting for the next poll.
 * Each browser keeps one connection and shares the news with its other tabs.
 */
@Slf4j
@Component
public class NotificationPush {

    private static final long TIMEOUT_MS = 30 * 60_000L;     // browsers reconnect on their own afterwards

    private final Map<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(Long userId) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        Set<SseEmitter> mine = emitters.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet());
        mine.add(emitter);
        Runnable drop = () -> mine.remove(emitter);
        emitter.onCompletion(drop);
        emitter.onTimeout(drop);
        emitter.onError(e -> drop.run());
        send(emitter, SseEmitter.event().comment("connected"));
        return emitter;
    }

    /** Tells the user's open pages that something new arrived, once the notification is committed. */
    public void signal(Long userId) {
        Runnable push = () -> {
            Set<SseEmitter> mine = emitters.get(userId);
            if (mine != null) mine.forEach(e -> send(e, SseEmitter.event().name("notify").data("new")));
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() { push.run(); }
            });
        } else {
            push.run();
        }
    }

    /** Keeps idle connections open through proxies and clears ones whose browser has gone. */
    @Scheduled(fixedRate = 25_000)
    public void heartbeat() {
        emitters.values().forEach(set -> set.forEach(e -> send(e, SseEmitter.event().comment("ping"))));
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (Exception e) {
            emitter.completeWithError(e);       // the tab was closed; onError removes it
        }
    }
}
