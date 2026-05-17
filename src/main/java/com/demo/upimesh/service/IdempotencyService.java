// This file is in the 'service' package
package com.demo.upimesh.service;

// @Value — reads values from application.properties
import org.springframework.beans.factory.annotation.Value;
// @Scheduled — runs a method automatically every X milliseconds
import org.springframework.scheduling.annotation.Scheduled;
// @Service — tells Spring this class has business logic
import org.springframework.stereotype.Service;
// Instant — exact moment in time
import java.time.Instant;
// Map — key-value storage (like a dictionary)
import java.util.Map;
// ConcurrentHashMap — thread-safe HashMap
// Multiple threads can use it simultaneously without breaking!
import java.util.concurrent.ConcurrentHashMap;

// @Service tells Spring:
// "Create ONE instance of this class"
// "Keep it ready for other classes"
//
// In production this would be Redis with SETNX + TTL
// Same exact behaviour, just distributed across servers!
// Here: ConcurrentHashMap works for single server demo
@Service
public class IdempotencyService {

    // THE MOST IMPORTANT LINE IN THIS CLASS!
    // ConcurrentHashMap = thread-safe key-value store
    //
    // Stores: packetHash → when it was first seen
    // Example:
    // "a3f8c9..." → 2026-05-16T17:04:40Z
    // "b4e9d1..." → 2026-05-16T17:05:12Z
    //
    // Why ConcurrentHashMap not regular HashMap?
    // Regular HashMap:
    // Thread 1 and Thread 2 write simultaneously
    // → Data corruption! Lost updates!
    //
    // ConcurrentHashMap:
    // Thread 1 and Thread 2 write simultaneously
    // → Safe! No corruption! Atomic operations!
    private final Map<String, Instant> seen =
            new ConcurrentHashMap<>();

    // Reads ttl value from application.properties:
    // upi.mesh.idempotency-ttl-seconds=86400
    // 86400 seconds = 24 hours
    //
    // :86400 = default value if not found in properties
    // How long to remember a payment hash
    // After 24 hours: hash forgotten
    // Replaying 25-hour-old payment → allowed again
    // But freshness check (signedAt) catches it!
    // Double protection!
    @Value("${upi.mesh.idempotency-ttl-seconds:86400}")
    private long ttlSeconds;

    // ═══════════════════════════════════════════════
    // METHOD 1: claim()
    // WHO CALLS: BridgeIngestionService
    // WHAT: Try to claim ownership of this payment hash
    // IN:  packetHash (64 char SHA-256 string)
    // OUT: true  = first claimer → process payment!
    //      false = duplicate → reject payment!
    //
    // THIS IS THE CORE OF IDEMPOTENCY!
    // ═══════════════════════════════════════════════
    public boolean claim(String packetHash) {

        // Get current time
        Instant now = Instant.now();

        // putIfAbsent = THE ATOMIC OPERATION!
        //
        // What it does:
        // "Put this hash in the map IF it's not already there"
        // "Return the PREVIOUS value (or null if new)"
        //
        // ATOMIC means:
        // Even if 100 threads call this simultaneously
        // Only ONE thread gets null back (first claimer)
        // All others get the existing Instant back
        //
        // This is JVM equivalent of Redis SETNX!
        // (SET if Not eXists)
        Instant prev = seen.putIfAbsent(packetHash, now);

        // prev == null means:
        // Nothing was there before
        // We are the FIRST to claim this hash!
        // → return true → process payment!
        //
        // prev != null means:
        // Something was already there
        // Someone else already claimed this hash!
        // → return false → DUPLICATE! reject!
        return prev == null;
    }

    // ═══════════════════════════════════════════════
    // METHOD 2: size()
    // WHO CALLS: Dashboard/testing
    // WHAT: How many hashes are currently remembered
    // Used to monitor memory usage
    // ═══════════════════════════════════════════════
    public int size() {
        return seen.size();
    }

    // ═══════════════════════════════════════════════
    // METHOD 3: evictExpired()
    // WHO CALLS: Spring automatically! Every 60 seconds!
    // WHAT: Clean up old hashes from memory
    //
    // WHY NEEDED?
    // Every payment adds one entry to the map
    // If never cleaned: map grows forever!
    // Eventually: OutOfMemoryError! App crashes!
    //
    // Solution: Remove entries older than 24 hours
    // Every 60 seconds, Spring calls this automatically
    // ═══════════════════════════════════════════════

    // @Scheduled = Spring calls this automatically
    // fixedDelay = 60_000 milliseconds = every 60 seconds
    // _ in numbers = just for readability (like 1,000)
    // 60_000 same as 60000
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {

        // Calculate cutoff time
        // anything older than 24 hours gets deleted
        // Instant.now() = right now
        // minusSeconds(ttlSeconds) = subtract 24 hours
        // cutoff = 24 hours ago
        Instant cutoff = Instant.now().minusSeconds(ttlSeconds);

        // removeIf = remove entries that match condition
        // e = each entry in the map
        // e.getValue() = the Instant (when hash was added)
        // isBefore(cutoff) = is this older than 24 hours?
        //
        // Example:
        // Entry: "a3f8c9" → 2026-05-15T10:00:00 (25 hours ago)
        // cutoff = 2026-05-16T09:00:00 (24 hours ago)
        // 2026-05-15T10:00:00 isBefore cutoff? YES!
        // → Entry removed! ✅
        //
        // Entry: "b4e9d1" → 2026-05-16T16:00:00 (1 hour ago)
        // cutoff = 2026-05-16T09:00:00 (24 hours ago)
        // 2026-05-16T16:00:00 isBefore cutoff? NO!
        // → Entry kept! Still within 24 hours!
        seen.entrySet().removeIf(e ->
                e.getValue().isBefore(cutoff));
    }

    // ═══════════════════════════════════════════════
    // METHOD 4: clear()
    // WHO CALLS: Dashboard reset button
    // WHAT: Wipe entire map for demo reset
    // Used when demoing idempotency:
    // Reset → Inject same packet → show it settles again!
    // ═══════════════════════════════════════════════
    public void clear() {
        seen.clear();
    }
}