package com.demo.upimesh.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

// @Entity tells Spring "Create a database table for this class"
// @Table names the table and creates a unique index on packetHash
@Entity
@Table(name = "transactions",
        indexes = {@Index(name = "idx_packet_hash", columnList = "packetHash", unique = true)})
public class Transaction {

    // @Id = primary Key
    // @GeneratedValue = database auto generates 1,2,3,4 ...
    // Different from account where we set the ID (vpa), here DB sets it automatically
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // SHA-256 of the encrypted packet, length is 64 because SHA-256 always produces exactly 64 characters
    // Unique = true means same payment can never be recorded twice. This is the idempotency key
    @Column(nullable = false, unique = true, length = 64)
    private String packetHash;

    // who sent the payment(e.g, alice@demo)
    @Column(nullable = false)
    private String senderVpa;

    // who received the payment (e.g, bob@demo)
    @Column(nullable = false)
    private String receiverVpa;

    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    // When the sender originally created the payment (offline, in basement). This is before the internet was available
    @Column(nullable = false)
    private Instant signedAt;

    // When the backend actually processed the payment. Gap b/w signedAt and settledAt = how long the payment was stuck
    // offline. Could be seconds, minutes or hours
    @Column(nullable = false)
    private  Instant settledAt;

    // Which phone in the mesh finally delivered this packet to backend. e.g, "phone-bridge-1"
    @Column(nullable = false)
    private String bridgeNodeId;

    // How many phones this packet passed through in the mesh
    // e.g, 3 means: alice's phone -> stranger -> bridge -> backend
    @Column(nullable = false)
    private int hopCount;

    // status can only be SETTLED OR REJECTED - nothing else
    // @Enumerated(EnumType.STRING) stores as text "SETTLED" or "REJECTED" in DB. without this it could be 0 or 1.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    public enum Status {SETTLED, REJECTED}

    // Empty constructor JPA required this to create objects when reading from the DB.
    // JPA created blank Object first, then fills fields using setters
    public Transaction() { }

    // Getters and Setters
    // JPA uses getters to read values before saving to DB
    // JPA uses setters to fill values after reading from DB
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPacketHash() { return packetHash; }
    public void setPacketHash(String packetHash) { this.packetHash = packetHash; }

    public String getSenderVpa() { return senderVpa; }
    public void setSenderVpa(String senderVpa) { this.senderVpa = senderVpa; }

    public String getReceiverVpa() { return receiverVpa; }
    public void setReceiverVpa(String receiverVpa) { this.receiverVpa = receiverVpa; }

    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }

    public Instant getSignedAt() { return signedAt; }
    public void setSignedAt(Instant signedAt) { this.signedAt = signedAt; }

    public Instant getSettledAt() { return settledAt; }
    public void setSettledAt(Instant settledAt) { this.settledAt = settledAt; }

    public String getBridgeNodeId() { return bridgeNodeId; }
    public void setBridgeNodeId(String bridgeNodeId) { this.bridgeNodeId = bridgeNodeId; }

    public int getHopCount() { return hopCount; }
    public void setHopCount(int hopCount) { this.hopCount = hopCount; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
}
/*

TABLE: transactions
+----+------------+------------+-------------+---------+---------------------+---------------------+--------------+----------+---------+
| id | packetHash | senderVpa  | receiverVpa | amount  | signedAt            | settledAt           | bridgeNodeId | hopCount | status  |
+----+------------+------------+-------------+---------+---------------------+---------------------+--------------+----------+---------+
| 1  | a3f8c9...  | alice@demo | bob@demo    | 500.00  | 2026-05-16T17:00:00 | 2026-05-16T17:45:00 | phone-bridge |    3     | SETTLED |
+----+------------+------------+-------------+---------+---------------------+---------------------+--------------+----------+---------+
  ↑       ↑             ↑            ↑           ↑              ↑                     ↑                   ↑            ↑          ↑
auto    SHA-256        who          who         money       when alice           when backend         which phone   how many   SETTLED
gener   fingerprint    sent         got         BigDecimal  clicked send         processed it         delivered it   hops      or
ated    unique=true                             precision=19 (OFFLINE)           (ONLINE)                                      REJECTED
by DB   64 chars                                scale=2

Gap between signedAt and settledAt = 45 minutes stuck offline!

*/