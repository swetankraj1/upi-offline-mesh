package com.demo.upimesh.model;

import java.math.BigDecimal;

// It is what server sees after decrypting MeshPacket.ciphertext. Think of it as the actual letter inside the sealed
// envelope! It exists only in server memory for a few milliseconds during settlement
public class PaymentInstruction {

    private String senderVpa;
    private String receiverVpa;
    private BigDecimal amount;
    private String pinHash;

    // UUID unique to this specific payment
    // THIS IS CRITICAL FOR SECURITY!
    // Example: "123e4567-e89b-12d3-a456-426614174000"
    //
    // Why needed?
    // Alice sends Bob ₹100 on Monday  → nonce = "abc123"
    // Alice sends Bob ₹100 on Tuesday → nonce = "xyz789"
    // Same amount, same sender, same receiver
    // BUT different nonce = different ciphertext = different hash
    // So BOTH payments settle correctly!
    // Without nonce: second payment would look like duplicate and get rejected!
    private String nonce;

    // When Alice originally signed/created this payment
    // Stored as epoch millis (same as MeshPacket.createdAt)
    // Example: 1747411480000
    //
    // WHY CRITICAL FOR SECURITY?
    // Attacker captures ciphertext today
    // Tries to replay it after 1 week
    // Server checks: signedAt = 1 week ago!
    // "Older than 24 hours → REJECTED!" ✅
    // Alice's money is safe!
    private Long signedAt;

    public PaymentInstruction() { }

    public PaymentInstruction(String senderVpa, String receiverVpa, BigDecimal amount,
                              String pinHash, String nonce, Long signedAt) {
        this.senderVpa = senderVpa;
        this.receiverVpa = receiverVpa;
        this.amount = amount;
        this.pinHash = pinHash;
        this.nonce = nonce;
        this.signedAt = signedAt;
    }

    // Getters and Setters
    // Used by:
    // 1. Jackson (JSON library) to convert decrypted JSON → object
    // 2. SettlementService to read payment details
    // 3. BridgeIngestionService to verify freshness using signedAt
    public String getSenderVpa() { return senderVpa; }
    public void setSenderVpa(String senderVpa) { this.senderVpa = senderVpa; }

    public String getReceiverVpa() { return receiverVpa; }
    public void setReceiverVpa(String receiverVpa) { this.receiverVpa = receiverVpa; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }

    // nonce getter — used to make each payment unique
    public String getNonce() { return nonce; }
    public void setNonce(String nonce) { this.nonce = nonce; }

    // signedAt getter — used by BridgeIngestionService
    // to check if payment is older than 24 hours
    public Long getSignedAt() { return signedAt; }
    public void setSignedAt(Long signedAt) { this.signedAt = signedAt; }
}
