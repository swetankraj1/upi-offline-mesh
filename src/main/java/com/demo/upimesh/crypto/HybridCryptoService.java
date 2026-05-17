// This file is in the 'crypto' package
package com.demo.upimesh.crypto;

// ObjectMapper — converts Java objects to JSON and back
import com.fasterxml.jackson.databind.ObjectMapper;
import com.demo.upimesh.model.PaymentInstruction;
// Autowired — Spring automatically injects ServerKeyHolder
import org.springframework.beans.factory.annotation.Autowired;
// Service — tells Spring this class has business logic
import org.springframework.stereotype.Service;

// Java crypto classes for AES and RSA operations
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

// @Service tells Spring:
// "This class has business logic"
// "Create one instance and keep it ready"
// "Other classes can use it via @Autowired"
//
// WHY HYBRID ENCRYPTION?
// Problem 1: RSA can only encrypt 245 bytes max
//            Our payment JSON is ~300 bytes — too big!
// Problem 2: AES can encrypt any size
//            But how to share AES key safely?
//
// SOLUTION — Hybrid (same as TLS, PGP, Signal, WhatsApp):
// → AES encrypts the BIG payment data (fast!)
// → RSA encrypts the SMALL AES key (safe!)
// → Best of both worlds!
//
// WIRE FORMAT (what travels in MeshPacket):
// [256 bytes RSA locked AES key]
// [12 bytes random IV/salt]
// [AES encrypted payment + 16 byte tamper seal]
@Service
public class HybridCryptoService {

    // RECIPE for RSA encryption
    // RSA     = algorithm
    // ECB     = mode
    // OAEP    = padding (more secure than basic RSA)
    // SHA-256 = hash inside OAEP
    // MGF1    = mask generation function
    // Must match EXACTLY between encrypt and decrypt!
    private static final String RSA_TRANSFORMATION =
            "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";

    // RECIPE for AES encryption
    // AES       = algorithm (the safe/lock)
    // GCM       = mode that adds tamper detection seal
    //             Any tampering = seal breaks = exception!
    // NoPadding = GCM doesn't need padding
    private static final String AES_TRANSFORMATION =
            "AES/GCM/NoPadding";

    // AES key size = 256 bits
    // 128 = good, 192 = better, 256 = best
    // 256 bits = cannot crack by brute force
    // in age of universe!
    private static final int AES_KEY_BITS = 256;

    // IV = random salt size = 12 bytes
    // IV makes same payment encrypt differently each time!
    // Alice sends ₹500 Monday  → IV="x9m2" → ciphertext="a3Yx"
    // Alice sends ₹500 Tuesday → IV="p7n1" → ciphertext="k8Zm"
    // Same payment = completely different ciphertext!
    // 12 bytes is GCM standard recommended size
    private static final int GCM_IV_BYTES = 12;

    // GCM authentication tag size = 128 bits = 16 bytes
    // This tag is the TAMPER DETECTION SEAL
    // Automatically added during encryption
    // Automatically checked during decryption
    // Tag broken = someone tampered = exception!
    private static final int GCM_TAG_BITS = 128;

    // RSA 2048-bit key always produces exactly 256 bytes output
    // Used during decryption to know where RSA key ends
    // and IV begins in the packed bytes
    private static final int RSA_ENCRYPTED_KEY_BYTES = 256;

    // Cryptographically secure random number generator
    // Used to generate random IV for each payment
    // Regular Random is NOT safe for cryptography!
    // SecureRandom uses OS-level entropy:
    // mouse movements, keyboard timing, network noise
    // Truly unpredictable!
    private final SecureRandom rng = new SecureRandom();

    // Converts between Java objects and JSON
    // PaymentInstruction → JSON bytes (before encrypting)
    // JSON bytes → PaymentInstruction (after decrypting)
    private final ObjectMapper json = new ObjectMapper();

    // Spring automatically injects ServerKeyHolder here
    // Needed to get private key during decryption
    // Without this: serverKey = null → app crashes!
    @Autowired
    private ServerKeyHolder serverKey;

    // ═══════════════════════════════════════════════════
    // METHOD 1: encrypt()
    // WHO CALLS: DemoService (simulating Alice's phone)
    // WHAT: Locks payment so strangers cannot read/change
    // IN:  PaymentInstruction + server's public key
    // OUT: base64 ciphertext string for MeshPacket
    // ═══════════════════════════════════════════════════
    public String encrypt(PaymentInstruction instruction,
                          PublicKey serverPublicKey) throws Exception {

        // STEP 1: Convert payment to JSON bytes
        // PaymentInstruction object
        // → {"senderVpa":"alice@demo","amount":500.00,...}
        // → [123, 34, 115, 101...] (raw bytes)
        // Encryption works on bytes not objects!
        byte[] plaintext = json.writeValueAsBytes(instruction);

        // STEP 2: Generate fresh AES key for THIS payment only
        // Like creating unique combination lock per payment!
        // New key every payment:
        // Even if one payment's key is stolen
        // Other payments are safe!
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(AES_KEY_BITS); // 256 bit key
        SecretKey aesKey = kg.generateKey();

        // STEP 3: Generate random IV (12 random bytes)
        // IV = salt that makes each encryption unique
        // Without IV: same payment → same ciphertext (bad!)
        // With IV: same payment → different ciphertext (good!)
        // rng.nextBytes fills array with random bytes
        byte[] iv = new byte[GCM_IV_BYTES];
        rng.nextBytes(iv);

        // STEP 4: Lock payment with AES-GCM
        // AES scrambles the data
        // GCM adds 16-byte tamper detection seal!
        // If anyone changes even ONE byte later:
        // Seal breaks during decryption → exception!
        Cipher aes = Cipher.getInstance(AES_TRANSFORMATION);
        aes.init(Cipher.ENCRYPT_MODE, aesKey,
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] aesCiphertext = aes.doFinal(plaintext);
        // aesCiphertext = [scrambled payment][16 byte seal]

        // STEP 5: Lock AES key with RSA using public key
        // AES key must travel with payment
        // But must be protected!
        // RSA locks it so ONLY server's private key can unlock!
        // Strangers cannot get AES key = cannot read payment!
        Cipher rsa = Cipher.getInstance(RSA_TRANSFORMATION);
        OAEPParameterSpec oaep = new OAEPParameterSpec(
                "SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT);
        rsa.init(Cipher.ENCRYPT_MODE, serverPublicKey, oaep);
        byte[] encryptedAesKey = rsa.doFinal(aesKey.getEncoded());
        // encryptedAesKey = always exactly 256 bytes!

        // STEP 6: Pack everything into one byte array
        // ORDER MATTERS! Decryption unpacks in same order!
        // [256 bytes RSA key][12 bytes IV][AES payment + seal]
        ByteBuffer buf = ByteBuffer.allocate(
                encryptedAesKey.length +
                        iv.length +
                        aesCiphertext.length);
        buf.put(encryptedAesKey); // first 256 bytes
        buf.put(iv);              // next 12 bytes
        buf.put(aesCiphertext);   // rest = payment data

        // STEP 7: Convert binary bytes to readable text
        // Binary cannot travel in JSON safely!
        // Base64 converts to safe characters (A-Z, a-z, 0-9)
        // This string = MeshPacket.ciphertext!
        // Travels phone to phone through mesh!
        return Base64.getEncoder().encodeToString(buf.array());
    }

    // ═══════════════════════════════════════════════════
    // METHOD 2: decrypt()
    // WHO CALLS: BridgeIngestionService
    // WHAT: Unlocks ciphertext to get payment details
    // IN:  base64 ciphertext string from MeshPacket
    // OUT: PaymentInstruction object for settlement
    // If tampered → throws exception → payment rejected!
    // ═══════════════════════════════════════════════════
    public PaymentInstruction decrypt(
            String base64Ciphertext) throws Exception {

        // STEP 1: Convert base64 text back to bytes
        // Reverse of Base64.getEncoder() in encrypt()
        // "a3Yx9mK2..." → [123, 34, 115, 101...]
        byte[] all = Base64.getDecoder().decode(base64Ciphertext);

        // STEP 2: Sanity check — is package big enough?
        // Minimum size = 256 (RSA) + 12 (IV) + 16 (GCM tag)
        //              = 284 bytes
        // If smaller = definitely corrupted or tampered!
        // Reject immediately without wasting CPU!
        if (all.length < RSA_ENCRYPTED_KEY_BYTES +
                GCM_IV_BYTES + GCM_TAG_BITS / 8) {
            throw new IllegalArgumentException("Ciphertext too short");
        }

        // STEP 3: Create empty arrays for each part
        // RSA key = exactly 256 bytes
        // IV      = exactly 12 bytes
        // AES data = everything remaining
        byte[] encryptedAesKey = new byte[RSA_ENCRYPTED_KEY_BYTES];
        byte[] iv = new byte[GCM_IV_BYTES];
        byte[] aesCiphertext = new byte[all.length
                - RSA_ENCRYPTED_KEY_BYTES - GCM_IV_BYTES];

        // STEP 4: Unpack the three parts
        // ByteBuffer reads bytes in order
        // Same order as we packed in encrypt()!
        ByteBuffer buf = ByteBuffer.wrap(all);
        buf.get(encryptedAesKey); // read first 256 bytes
        buf.get(iv);              // read next 12 bytes
        buf.get(aesCiphertext);   // read the rest

        // STEP 5: Unlock the AES key using PRIVATE key
        // Only server has private key!
        // Strangers could NEVER do this step!
        // If wrong key or tampered → exception here!
        Cipher rsa = Cipher.getInstance(RSA_TRANSFORMATION);
        OAEPParameterSpec oaep = new OAEPParameterSpec(
                "SHA-256", "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT);
        rsa.init(Cipher.DECRYPT_MODE,
                serverKey.getPrivateKey(), oaep);
        byte[] aesKeyBytes = rsa.doFinal(encryptedAesKey);

        // Rebuild AES key object from raw bytes
        // Raw bytes → SecretKey object ready to use!
        SecretKey aesKey = new SecretKeySpec(aesKeyBytes, "AES");

        // STEP 6: Unlock payment with AES-GCM
        // Uses AES key + IV from the packet
        // GCM AUTOMATICALLY checks tamper seal here!
        //
        // Seal intact  → payment not tampered → decrypt! ✅
        // Seal broken  → someone changed data → exception! ❌
        //
        // This is where malicious intermediates are caught!
        // Even ONE bit changed = exception = payment rejected!
        Cipher aes = Cipher.getInstance(AES_TRANSFORMATION);
        aes.init(Cipher.DECRYPT_MODE, aesKey,
                new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] plaintext = aes.doFinal(aesCiphertext);
        // If we reach this line = data was NOT tampered!

        // STEP 7: Convert JSON bytes back to object
        // [123, 34, 115, 101...] (bytes)
        // → {"senderVpa":"alice@demo","amount":500.00}
        // → PaymentInstruction object
        // Ready for SettlementService to move money!
        return json.readValue(plaintext, PaymentInstruction.class);
    }

    // ═══════════════════════════════════════════════════
    // METHOD 3: hashCiphertext()
    // WHO CALLS: BridgeIngestionService
    // WHEN: FIRST thing when packet arrives
    //       BEFORE even trying to decrypt!
    // WHAT: Creates 64 char fingerprint of ciphertext
    // WHY:  Same packet = same hash = duplicate!
    //       Check hash first (cheap) before decrypt (expensive)
    // IN:  base64 ciphertext string
    // OUT: 64 character hex string (SHA-256 hash)
    // ═══════════════════════════════════════════════════
    public String hashCiphertext(String base64Ciphertext)
            throws Exception {

        // SHA-256 = fingerprinting algorithm
        // Properties:
        // → Same input = ALWAYS same 64 char output
        // → Different input = completely different output
        // → Cannot reverse! Cannot get original from hash!
        // → Used in Bitcoin, Git, passwords everywhere!
        MessageDigest sha256 =
                MessageDigest.getInstance("SHA-256");

        // Run SHA-256 on ciphertext bytes
        // Produces 32 raw bytes (256 bits)
        byte[] hash = sha256.digest(
                base64Ciphertext.getBytes());

        // Convert raw bytes to readable hex string
        // Each byte → 2 hex characters
        // 32 bytes × 2 = always exactly 64 characters!
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            // %02x = format as 2-digit hex
            // byte 163 → "a3"
            // byte 248 → "f8"
            hex.append(String.format("%02x", b));
        }

        // Returns 64 character string like:
        // "a3f8c9d2e1b4f7c8d9e2a1b3c4d5e6f7..."
        // This gets stored in Transaction.packetHash!
        // Same packet arriving 100 times
        // = same hash 100 times
        // = duplicate detected 99 times!
        return hex.toString();
    }
}