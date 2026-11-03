package com.example.payments;

import com.example.payments.PspAdapter.Result.Status;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Inbound PSP callbacks. Verified (HMAC), deduplicated (webhook_inbox PK), and applied through the
 * same state machine as everything else, all in ONE transaction: if applying fails, the inbox row
 * rolls back too, so the PSP's redelivery is processed instead of being dropped as a duplicate.
 *
 * Demo wire format (pipe-delimited so we need no JSON parser):
 *   eventId|type|reference|pspReference     e.g.  evt_1|payment.captured|pay_xxx|alpha_9f3a
 */
public class WebhookService {

    public record Event(String eventId, String type, String reference, String pspReference) {
        static Event parse(String body) {
            String[] f = body.split("\\|", -1);
            if (f.length != 4) throw new ApiException(400, "malformed_event", "expected 4 fields");
            return new Event(f[0], f[1], f[2], f[3]);
        }
    }

    private final Database db;
    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final String secret;

    public WebhookService(Database db, PaymentRepository payments, PaymentService paymentService, String secret) {
        this.db = db;
        this.payments = payments;
        this.paymentService = paymentService;
        this.secret = secret;
    }

    /** What the PSP does on its side; exposed so the demo can play the PSP. */
    public String sign(String body) { return Crypto.hmacSha256Hex(secret, body); }

    /** @return "processed" | "duplicate" | "ignored" | "no-op ..." (all map to HTTP 200) */
    public String handle(String provider, String signature, String body) {
        if (signature == null || !Crypto.constantTimeEquals(sign(body), signature)) {
            throw new ApiException(401, "bad_signature", "Webhook signature mismatch");
        }
        Event event = Event.parse(body);

        return db.inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO webhook_inbox (psp, event_id, payload, received_at) VALUES (?,?,?,?)")) {
                ps.setString(1, provider);
                ps.setString(2, event.eventId());
                ps.setString(3, body);
                ps.setTimestamp(4, Timestamp.from(Instant.now()));
                ps.executeUpdate();
            } catch (SQLException e) {
                if (Database.isUniqueViolation(e)) return "duplicate";
                throw e;
            }

            Status status = switch (event.type()) {
                case "payment.authorized" -> Status.AUTHORIZED;
                case "payment.captured" -> Status.CAPTURED;
                case "payment.failed" -> Status.FAILED;
                default -> null;
            };
            if (status == null) return "ignored";

            Optional<Payment> payment = payments.find(c, event.reference(), false);
            if (payment.isEmpty() && !event.pspReference().isEmpty()) {
                payment = payments.findByPspRef(c, provider, event.pspReference());
            }
            if (payment.isEmpty()) return "ignored";   // unknown payment: ack so the PSP stops retrying

            Payment before = payment.get();
            Payment after = paymentService.applyPspResult(c, before.id(),
                    new PspAdapter.Result(status, event.pspReference().isEmpty() ? null : event.pspReference(),
                            status == Status.FAILED ? "PSP_WEBHOOK_FAILED" : null),
                    "psp_webhook");
            return after.version() == before.version() ? "no-op (stale or already applied)" : "processed";
        });
    }
}
