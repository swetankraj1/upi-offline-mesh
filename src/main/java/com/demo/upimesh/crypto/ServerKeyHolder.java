// This file is in the 'crypto' package — handles all cryptography
package com.demo.upimesh.crypto;

// PostConstruct — runs this method automatically when Spring starts the app
import jakarta.annotation.PostConstruct;
// Logger — prints messages to console so we can see what's happening
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Component — tells Spring "manage this class, I'll need it in other places"
import org.springframework.stereotype.Component;
// Java security classes for RSA key generation
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
// Base64 — converts binary key to readable text
import java.util.Base64;

// @Component tells Spring:
// "Create ONE instance of this class and keep it ready"
// "Other classes can ask for it using @Autowired"
// In production: private key would be in HSM (Hardware Security Module)
// or AWS KMS / HashiCorp Vault — NEVER in source code!
// In our demo: fresh keypair generated every time app starts
@Component
public class ServerKeyHolder {

    // Logger — prints to console when keypair is generated
    // Good practice to log important startup events
    private static final Logger log = LoggerFactory.getLogger(ServerKeyHolder.class);

    // Holds both public and private key together
    // KeyPair = a pair of mathematically linked keys
    // What public key encrypts, only private key can decrypt!
    private KeyPair keyPair;

    // @PostConstruct = run this method AUTOMATICALLY when app starts
    // Before any request comes in, keypair is ready!
    // Like a locksmith making lock+key before opening the shop
    @PostConstruct
    public void init() throws Exception {

        // KeyPairGenerator creates RSA keys
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");

        // 2048 bits = strong enough for banking
        // Bigger = more secure but slower
        // 2048 is industry standard for this use case
        gen.initialize(2048);

        // Generate the actual keypair!
        // This takes ~100ms — happens only once on startup
        this.keyPair = gen.generateKeyPair();

        // Log first 32 characters of public key
        // So we can see in console that keypair was generated
        // Never log private key!
        log.info("Server RSA keypair generated (2048-bit). Public key fingerprint: {}",
                getPublicKeyBase64().substring(0, 32) + "...");
    }

    // Returns public key object
    // Used by HybridCryptoService.encrypt()
    // Alice's phone uses this to encrypt the payment!
    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    // Returns private key object
    // Used by HybridCryptoService.decrypt()
    // ONLY server uses this to decrypt payments!
    // In production this would NEVER leave the HSM!
    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    // Returns public key as base64 text string
    // Used by ApiController to expose via /api/server-key endpoint
    // So simulated sender devices can download and use it
    // In production: devices would have this pre-installed
    public String getPublicKeyBase64() {
        return Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }
}