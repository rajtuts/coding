package com.example.payments;

import java.util.List;

/**
 * Transactional outbox relay: reads committed-but-unpublished events and pushes them to the broker.
 * Delivery is AT LEAST ONCE (publish happens before the row is marked), so consumers must dedupe by message id.
 * On PostgreSQL, run several relays safely with "SELECT ... FOR UPDATE SKIP LOCKED".
 */
public class OutboxRelay {

    public interface Publisher {
        void publish(long messageId, String topic, String key, String payload);
    }

    private final Database db;
    private final OutboxRepository outbox;
    private final Publisher publisher;

    public OutboxRelay(Database db, OutboxRepository outbox, Publisher publisher) {
        this.db = db;
        this.outbox = outbox;
        this.publisher = publisher;
    }

    public int pollOnce(int batchSize) { return pollOnce(batchSize, false); }

    /** simulateCrashBeforeAck=true publishes but "dies" before marking rows, which causes redelivery on the next poll. */
    public int pollOnce(int batchSize, boolean simulateCrashBeforeAck) {
        List<OutboxRepository.Message> batch = db.inTx(c -> outbox.fetchUnpublished(c, batchSize));
        for (OutboxRepository.Message m : batch) {
            publisher.publish(m.id(), m.topic(), m.key(), m.payload());
        }
        if (!simulateCrashBeforeAck && !batch.isEmpty()) {
            List<Long> ids = batch.stream().map(OutboxRepository.Message::id).toList();
            db.execute(c -> outbox.markPublished(c, ids));
        }
        return batch.size();
    }
}
