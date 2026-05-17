// This file is in the 'service' package
package com.demo.upimesh.service;

// Crypto service for hashing and decryption
import com.demo.upimesh.crypto.HybridCryptoService;
// Models we need
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
// Logger
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Spring annotations
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
// Time
import java.time.Instant;

// @Service = Spring manages this class
// This is THE most important service!
// It orchestrates the ENTIRE payment pipeline!
// Every packet from every bridge phone
// comes through here first!
@Service
public class BridgeIngestionService {

    private static final Logger log =
            LoggerFactory.getLogger(BridgeIngestionService.class);

    // HybridCryptoService for:
    // → hashCiphertext() = get fingerprint
    // → decrypt() = unlock payment
    @Autowired
    private HybridCryptoService crypto;

    // IdempotencyService for:
    // → claim() = check if duplicate
    @Autowired
    private IdempotencyService idempotency;

    // SettlementService for:
    // → settle() = actually move money
    @Autowired
    private SettlementService settlement;

    // Read from application.properties:
    // upi.mesh.packet-max-age-seconds=86400
    // 86400 seconds = 24 hours
    // Packets older than this = replay attack!
    // Rejected!
    @Value("${upi.mesh.packet-max-age-seconds:86400}")
    private long maxAgeSeconds;

    // ═══════════════════════════════════════════════
    // METHOD: ingest()
    // WHO CALLS: ApiController when bridge POSTs packet
    // WHAT: Runs full 5-step pipeline on each packet
    // IN:  MeshPacket + bridgeNodeId + hopCount
    // OUT: IngestResult (SETTLED/DUPLICATE/INVALID)
    // ═══════════════════════════════════════════════
    public IngestResult ingest(
            MeshPacket packet,
            String bridgeNodeId,
            int hopCount) {

        // Outer try-catch catches ANY unexpected error
        // Returns INVALID instead of crashing!
        // App stays running even if something breaks!
        try {

            // ─────────────────────────────────────
            // STEP 1: Get fingerprint of ciphertext
            // SHA-256 hash of the encrypted payment
            // Same packet = same hash always!
            // This hash = idempotency key!
            // ─────────────────────────────────────
            String packetHash = crypto.hashCiphertext(
                    packet.getCiphertext());

            // ─────────────────────────────────────
            // STEP 2: Idempotency gate
            // Is this the first time we see this hash?
            // claim() returns:
            // true  = first time! proceed!
            // false = seen before! duplicate! drop!
            //
            // ! = NOT operator
            // !idempotency.claim() means:
            // "if claim returns FALSE (duplicate)"
            // then enter the if block and drop it!
            // ─────────────────────────────────────
            if (!idempotency.claim(packetHash)) {

                // Log duplicate detection
                // Only show first 12 chars of hash
                // Full 64 chars too long for logs!
                log.info(
                        "DUPLICATE packet {} from bridge {} — dropped",
                        packetHash.substring(0, 12) + "...",
                        bridgeNodeId);

                // Return DUPLICATE result
                // Payment NOT settled!
                // Alice NOT charged again!
                return IngestResult.duplicate(packetHash);
            }

            // ─────────────────────────────────────
            // STEP 3: Decrypt the payment
            // Use server's private key to unlock!
            // If tampered → exception thrown!
            //
            // Separate try-catch here because:
            // Decryption failure = INVALID (tampered)
            // Different from general error!
            // We want specific error message!
            // ─────────────────────────────────────
            PaymentInstruction instruction;
            try {
                instruction = crypto.decrypt(
                        packet.getCiphertext());

            } catch (Exception e) {
                // Decryption failed!
                // Could mean:
                // → Ciphertext was tampered!
                // → Wrong key used!
                // → Corrupted data!
                log.warn(
                        "Decryption failed for packet {}: {}",
                        packetHash.substring(0, 12) + "...",
                        e.getMessage());

                // Return INVALID result
                // "decryption_failed" tells us why!
                return IngestResult.invalid(
                        packetHash, "decryption_failed");
            }

            // ─────────────────────────────────────
            // STEP 4: Freshness check
            // REPLAY ATTACK PROTECTION!
            //
            // How old is this payment?
            // signedAt = when Alice created it (offline)
            // now = current time
            // age = difference in seconds
            //
            // Instant.now().toEpochMilli() = now in millis
            // instruction.getSignedAt() = signed time in millis
            // Difference / 1000 = age in seconds
            // ─────────────────────────────────────
            long ageSeconds = (Instant.now().toEpochMilli()
                    - instruction.getSignedAt()) / 1000;

            // Too OLD = replay attack!
            // Attacker captured payment weeks ago
            // Trying to replay it now
            // signedAt is too far in the past!
            // 86400 seconds = 24 hours
            if (ageSeconds > maxAgeSeconds) {
                log.warn(
                        "Packet {} too old ({}s), rejected",
                        packetHash.substring(0, 12) + "...",
                        ageSeconds);
                return IngestResult.invalid(
                        packetHash, "stale_packet");
            }

            // Too NEW = future dated!
            // signedAt is in the FUTURE
            // This is impossible normally!
            // Could mean clock is wrong on sender
            // or someone is trying to manipulate!
            //
            // -300 = allow 5 minutes clock difference
            // between sender phone and server
            // (phones clocks are never perfectly synced!)
            if (ageSeconds < -300) {
                return IngestResult.invalid(
                        packetHash, "future_dated");
            }

            // ─────────────────────────────────────
            // STEP 5: Settle the payment!
            // All checks passed!
            // Actually move the money now!
            //
            // settlement.settle() will:
            // → Check sender has enough balance
            // → Debit sender
            // → Credit receiver
            // → Save transaction record
            // → Return transaction object
            // ─────────────────────────────────────
            Transaction tx = settlement.settle(
                    instruction,
                    packetHash,
                    bridgeNodeId,
                    hopCount);

            // Return SETTLED result with transaction ID!
            // BridgePhone gets this response back!
            return IngestResult.settled(packetHash, tx);

        } catch (Exception e) {
            // Something unexpected went wrong!
            // Log full error with stack trace
            // Return INVALID so app keeps running!
            log.error(
                    "Ingestion error: {}",
                    e.getMessage(), e);
            return IngestResult.invalid(
                    "?",
                    "internal_error: " + e.getMessage());
        }
    }

    // ═══════════════════════════════════════════════
    // INNER CLASS: IngestResult
    // What gets returned after processing a packet
    // Uses Java 'record' — automatic getters, equals,
    // toString generated by Java!
    // Like a simple data container!
    //
    // Three possible outcomes:
    // SETTLED          = payment went through!
    // DUPLICATE_DROPPED = already processed before
    // INVALID          = tampered/old/error
    // ═══════════════════════════════════════════════
    public record IngestResult(
            String outcome,      // SETTLED/DUPLICATE_DROPPED/INVALID
            String packetHash,   // 64 char fingerprint
            String reason,       // why rejected (null if settled)
            Long transactionId   // DB id (null if not settled)
    ) {

        // Factory method for successful payment
        // outcome = "SETTLED"
        // reason = null (no reason needed for success)
        // transactionId = from database
        public static IngestResult settled(
                String hash, Transaction tx) {
            return new IngestResult(
                    "SETTLED", hash, null, tx.getId());
        }

        // Factory method for duplicate packet
        // outcome = "DUPLICATE_DROPPED"
        // reason = null
        // transactionId = null (not settled)
        public static IngestResult duplicate(String hash) {
            return new IngestResult(
                    "DUPLICATE_DROPPED", hash, null, null);
        }

        // Factory method for invalid packet
        // outcome = "INVALID"
        // reason = WHY invalid
        //   "decryption_failed" = tampered
        //   "stale_packet" = too old
        //   "future_dated" = wrong timestamp
        //   "internal_error" = unexpected crash
        // transactionId = null (not settled)
        public static IngestResult invalid(
                String hash, String reason) {
            return new IngestResult(
                    "INVALID", hash, reason, null);
        }
    }
}