package com.demo.upimesh.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

// This is not an Entity, It is a travelling packet which moves phone to phone through mesh. Think of it as an
// envelope carrying the encrypted payment, it dies after reaching the backend and being settled
public class MeshPacket {

    // Used by intermediate phone to avoid carrying the same packet twice
    @NotBlank
    private String packetId;

    // TTL = Time to live, when TTL hits 0, packet dies, no more hopping!
    @Min(0)
    private int ttl;

    // When sender originally created this packet
    @NotNull
    private Long createdAt;

    // This is the encrypted payment - nobody can read it except the server. Contains the RSA encrypted AES key + AES
    // encrypted payment details
    @NotBlank
    private String ciphertext;

    public MeshPacket(){ }

    // Getters and Setters
    // Spring uses these to convert incoming JSON → MeshPacket object
    // and MeshPacket object → outgoing JSON

    // packetId getter and setter
    public String getPacketId() { return packetId; }
    public void setPacketId(String packetId) { this.packetId = packetId; }

    // ttl getter and setter
    // intermediate phones call setTtl(getTtl() - 1) on every hop
    public int getTtl() { return ttl; }
    public void setTtl(int ttl) { this.ttl = ttl; }

    // createdAt getter and setter
    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    // ciphertext getter and setter
    // bridge phone reads this and POSTs to backend
    // decrypts this to get payment details
    public String getCiphertext() { return ciphertext; }
    public void setCiphertext(String ciphertext) { this.ciphertext = ciphertext; }
}
