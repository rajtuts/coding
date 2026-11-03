package com.example.payments;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Stand-in for Kafka/SQS plus one idempotent consumer (e.g. the merchant-webhook dispatcher). */
public class InMemoryBroker implements OutboxRelay.Publisher {

    private final List<String> delivered = new ArrayList<>();
    private final Set<Long> seenMessageIds = new HashSet<>();
    private final List<String> processed = new ArrayList<>();

    @Override
    public synchronized void publish(long messageId, String topic, String key, String payload) {
        delivered.add(topic + " " + payload);
        if (seenMessageIds.add(messageId)) {          // consumer-side dedupe
            processed.add(topic + " " + payload);
        }
    }

    public synchronized int deliveredCount() { return delivered.size(); }
    public synchronized int processedCount() { return processed.size(); }
    public synchronized List<String> processed() { return List.copyOf(processed); }
}
