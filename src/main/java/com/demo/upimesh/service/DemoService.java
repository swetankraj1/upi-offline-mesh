// This file is in the 'service' package
package com.demo.upimesh.service;

// Crypto services
import com.demo.upimesh.crypto.HybridCryptoService;
import com.demo.upimesh.crypto.ServerKeyHolder;
// Models
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
// PostConstruct — run method on startup
import jakarta.annotation.PostConstruct;
// Logger
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Spring
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
// Java utilities
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.UUID;

// @Service = Spring manages this class
// Does two things:
// 1. Seeds demo accounts on startup
// 2. Simulates Alice's phone creating a payment
//
// In real Android app:
// createPacket() would run ON THE PHONE
// Phone would have server's public key cached
// from previous online session!
@Service
public class DemoService {

    private static final Logger log =
            LoggerFactory.getLogger(DemoService.class);

    // Repository to save/find accounts in database
    @Autowired
    private AccountRepository accounts;

    // Crypto service to encrypt payment
    @Autowired
    private HybridCryptoService crypto;

    // ServerKeyHolder to get public key
    // Alice's phone needs public key to encrypt!
    @Autowired
    private ServerKeyHolder serverKey;

    // ═══════════════════════════════════════════════
    // METHOD: seedAccounts()
    // WHEN: Runs AUTOMATICALLY when app starts!
    // WHAT: Creates 4 demo accounts in database
    //
    // Why check accounts.count() == 0?
    // If app restarts:
    // Don't create duplicates!
    // Only seed if database is empty!
    // ═══════════════════════════════════════════════
    @PostConstruct
    public void seedAccounts() {

        // Only seed if no accounts exist yet!
        // Prevents duplicates on restart!
        if (accounts.count() == 0) {

            // Create 4 demo accounts
            // new Account(vpa, holderName, balance)
            accounts.save(new Account(
                    "alice@demo", "Alice",
                    new BigDecimal("5000.00")));

            accounts.save(new Account(
                    "bob@demo", "Bob",
                    new BigDecimal("1000.00")));

            accounts.save(new Account(
                    "carol@demo", "Carol",
                    new BigDecimal("2500.00")));

            accounts.save(new Account(
                    "dave@demo", "Dave",
                    new BigDecimal("500.00")));

            log.info("Seeded 4 demo accounts");
        }
    }

    // ═══════════════════════════════════════════════
    // METHOD: createPacket()
    // WHO CALLS: ApiController when "Send Payment"
    //            button clicked on dashboard
    // WHAT: Simulates Alice's phone creating
    //       an encrypted payment packet
    //
    // 3 steps:
    // 1. Build PaymentInstruction
    // 2. Encrypt with server's public key
    // 3. Wrap in MeshPacket
    //
    // IN:  senderVpa, receiverVpa, amount, pin, ttl
    // OUT: MeshPacket ready to inject into mesh!
    // ═══════════════════════════════════════════════
    public MeshPacket createPacket(
            String senderVpa,
            String receiverVpa,
            BigDecimal amount,
            String pin,
            int ttl) throws Exception {

        // STEP 1: Build PaymentInstruction
        // This is the ACTUAL payment details
        // Goes INSIDE the encrypted envelope!
        PaymentInstruction instruction =
                new PaymentInstruction(
                        // Who is sending
                        senderVpa,

                        // Who is receiving
                        receiverVpa,

                        // How much
                        amount,

                        // PIN hash — never store raw PIN!
                        // sha256Hex("1234") → "03ac674..."
                        // Even if DB is hacked → PIN is safe!
                        sha256Hex(pin),

                        // Nonce = random UUID for uniqueness
                        // Each payment gets completely unique ID!
                        // Even if Alice sends ₹500 twice:
                        // Different nonce → different ciphertext
                        // → different hash → both settle! ✅
                        UUID.randomUUID().toString(),

                        // signedAt = RIGHT NOW in epoch millis
                        // When Alice "clicked send" on her phone
                        // Used for freshness check on server!
                        Instant.now().toEpochMilli()
                );

        // STEP 2: Encrypt the PaymentInstruction
        // Uses server's PUBLIC key
        // Hybrid RSA + AES encryption
        // Strangers cannot read this!
        // Only server can decrypt!
        String ciphertext = crypto.encrypt(
                instruction,
                serverKey.getPublicKey());

        // STEP 3: Wrap in MeshPacket
        // This is the ENVELOPE that travels
        // phone to phone through mesh!
        MeshPacket packet = new MeshPacket();

        // Unique ID for this packet
        // Used by phones to avoid gossip loops!
        // "Have I seen this packet before?"
        packet.setPacketId(UUID.randomUUID().toString());

        // TTL = how many hops allowed
        // Starts at 5
        // Decrements each hop
        // Dies at 0 → no more forwarding!
        packet.setTtl(ttl);

        // When packet was created
        // Current time in epoch millis
        packet.setCreatedAt(Instant.now().toEpochMilli());

        // The encrypted payment!
        // This is the most important field!
        // Strangers carry this but cannot read it!
        packet.setCiphertext(ciphertext);

        // Return complete packet!
        // Ready to inject into mesh via
        // MeshSimulatorService.inject()!
        return packet;
    }

    // ═══════════════════════════════════════════════
    // METHOD: sha256Hex()
    // PRIVATE — only used inside DemoService
    // WHAT: Converts PIN to SHA-256 hash
    //
    // Why hash the PIN?
    // PIN = "1234" (sensitive!)
    // Hash = "03ac674..." (safe to store!)
    //
    // If database is hacked:
    // Attacker sees "03ac674..." not "1234"
    // Cannot reverse hash → PIN is safe!
    //
    // Same logic used for passwords everywhere!
    // ═══════════════════════════════════════════════
    private String sha256Hex(String input) throws Exception {

        // Get SHA-256 hasher
        MessageDigest md =
                MessageDigest.getInstance("SHA-256");

        // Hash the PIN bytes
        // "1234" → [3, 172, 103, 78...] (32 bytes)
        byte[] hash = md.digest(input.getBytes());

        // Convert bytes to hex string
        // Same as hashCiphertext in HybridCryptoService!
        // 32 bytes → 64 character hex string
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }

        // Returns: "03ac674216f3e15c761ee1a5e255f067..."
        // This gets stored in PaymentInstruction.pinHash
        return hex.toString();
    }
}