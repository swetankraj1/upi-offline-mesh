// This file is in the 'service' package
package com.demo.upimesh.service;

// All the models we need
import com.demo.upimesh.model.Account;
import com.demo.upimesh.model.AccountRepository;
import com.demo.upimesh.model.PaymentInstruction;
import com.demo.upimesh.model.Transaction;
import com.demo.upimesh.model.TransactionRepository;

// Logger — prints to console
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Autowired — Spring injects dependencies
import org.springframework.beans.factory.annotation.Autowired;
// Service — tells Spring this has business logic
import org.springframework.stereotype.Service;
// Transactional — THE MOST IMPORTANT ANNOTATION HERE!
import org.springframework.transaction.annotation.Transactional;
// BigDecimal — for money calculations
import java.math.BigDecimal;
// Instant — for timestamps
import java.time.Instant;

@Service
public class SettlementService {

    // Logger for printing settlement results
    // and warnings (insufficient balance etc.)
    private static final Logger log =
            LoggerFactory.getLogger(SettlementService.class);

    // Spring injects AccountRepository automatically
    // Used to find sender and receiver accounts
    // and save updated balances
    @Autowired
    private AccountRepository accounts;

    // Spring injects TransactionRepository automatically
    // Used to save the permanent payment record
    @Autowired
    private TransactionRepository transactions;

    // ═══════════════════════════════════════════════
    // METHOD: settle()
    // WHO CALLS: BridgeIngestionService
    //            after successful decrypt + checks
    // WHAT: Actually moves money between accounts
    //       and creates permanent record
    //
    // @Transactional = THE KEY ANNOTATION!
    // Means: ALL operations succeed or ALL fail!
    // No partial updates!
    //
    // Example without @Transactional:
    // Alice debited ₹500 ✅
    // App crashes!
    // Bob never credited! 😱
    // Alice lost ₹500!
    //
    // With @Transactional:
    // Alice debited ₹500 ✅
    // App crashes!
    // Database ROLLS BACK!
    // Alice gets ₹500 back! ✅
    // ═══════════════════════════════════════════════
    @Transactional
    public Transaction settle(
            PaymentInstruction instruction,
            String packetHash,
            String bridgeNodeId,
            int hopCount) {

        // STEP 1: Find sender's account in database
        // findById returns Optional (might not exist!)
        // orElseThrow = if not found, throw exception!
        // "Unknown sender VPA: alice@demo"
        // Prevents payment to non-existent account!
        Account sender = accounts
                .findById(instruction.getSenderVpa())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown sender VPA: " +
                                instruction.getSenderVpa()));

        // STEP 2: Find receiver's account in database
        // Same pattern as sender
        // If receiver doesn't exist → exception!
        // Payment rejected!
        Account receiver = accounts
                .findById(instruction.getReceiverVpa())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown receiver VPA: " +
                                instruction.getReceiverVpa()));

        // STEP 3: Get the amount
        BigDecimal amount = instruction.getAmount();

        // STEP 4: Check amount is positive
        // signum() returns:
        // -1 = negative number
        //  0 = zero
        //  1 = positive number
        //
        // signum() <= 0 means zero or negative!
        // Cannot send ₹0 or -₹500!
        // That makes no sense!
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Amount must be positive");
        }

        // STEP 5: Check sender has enough money!
        // compareTo returns:
        // -1 = balance < amount (NOT enough!)
        //  0 = balance = amount (exactly enough)
        //  1 = balance > amount (more than enough)
        //
        // < 0 means balance is LESS than amount
        // Cannot send more than you have!
        if (sender.getBalance().compareTo(amount) < 0) {

            // Log warning to console
            // "alice@demo has ₹200, tried to send ₹500"
            log.warn(
                    "Insufficient balance: {} has ₹{}, " +
                            "tried to send ₹{}",
                    sender.getVpa(),
                    sender.getBalance(),
                    amount);

            // Record this as REJECTED transaction
            // Still saves to database!
            // So we have history of failed payments!
            return recordRejected(
                    instruction, packetHash,
                    bridgeNodeId, hopCount);
        }

        // STEP 6: Deduct from sender
        // subtract = minus in BigDecimal
        // sender had ₹5000, amount ₹500
        // 5000 - 500 = 4500
        // setBalance updates the object in memory
        sender.setBalance(
                sender.getBalance().subtract(amount));

        // STEP 7: Add to receiver
        // add = plus in BigDecimal
        // receiver had ₹3000, amount ₹500
        // 3000 + 500 = 3500
        receiver.setBalance(
                receiver.getBalance().add(amount));

        // STEP 8: Save both accounts to database
        // This actually updates balances in DB!
        // @Version auto-increments here
        // (optimistic locking protection!)
        //
        // If two threads somehow both reach here:
        // First save → version 0 → 1 ✅
        // Second save → version 0 but DB has 1!
        // OptimisticLockException! ❌
        // Second payment rolled back!
        // Defense in depth!
        accounts.save(sender);
        accounts.save(receiver);

        // STEP 9: Create permanent transaction record
        // This is the receipt of the payment!
        // Written to transactions table forever!
        Transaction tx = new Transaction();

        // The SHA-256 fingerprint
        // Stored for idempotency defense-in-depth
        tx.setPacketHash(packetHash);

        // Who sent and received
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());

        // How much
        tx.setAmount(amount);

        // When Alice originally created payment (offline)
        // Instant.ofEpochMilli converts:
        // 1747411480000 (Long) → 2026-05-16T17:04:40Z (Instant)
        tx.setSignedAt(
                Instant.ofEpochMilli(instruction.getSignedAt()));

        // When backend actually processed it (now!)
        // Gap between signedAt and settledAt =
        // how long payment was stuck offline!
        tx.setSettledAt(Instant.now());

        // Which bridge phone delivered it
        tx.setBridgeNodeId(bridgeNodeId);

        // How many phones it passed through
        tx.setHopCount(hopCount);

        // Mark as SETTLED ✅
        tx.setStatus(Transaction.Status.SETTLED);

        // Save to database!
        // @Transactional ensures if this fails:
        // Alice's debit is also rolled back!
        transactions.save(tx);

        // STEP 10: Log success to console
        // Shows first 12 chars of hash (not full 64)
        // Keeps log readable!
        log.info(
                "SETTLED ₹{} from {} to {} " +
                        "(packetHash={}, bridge={}, hops={})",
                amount,
                sender.getVpa(),
                receiver.getVpa(),
                packetHash.substring(0, 12) + "...",
                bridgeNodeId,
                hopCount);

        // Return the transaction record
        // BridgeIngestionService uses this
        // to build the response to the bridge phone
        return tx;
    }

    // ═══════════════════════════════════════════════
    // METHOD: recordRejected()
    // PRIVATE — only used inside this class
    // WHO CALLS: settle() when insufficient balance
    // WHAT: Saves REJECTED transaction to database
    //
    // Why save rejected payments?
    // 1. Audit trail — history of everything
    // 2. Receiver knows payment was attempted
    // 3. Sender can see why payment failed
    // 4. Engineers can analyze rejection patterns
    // ═══════════════════════════════════════════════
    private Transaction recordRejected(
            PaymentInstruction instruction,
            String packetHash,
            String bridgeNodeId,
            int hopCount) {

        // Same as settle() but:
        // NO balance changes!
        // Status = REJECTED not SETTLED!
        Transaction tx = new Transaction();
        tx.setPacketHash(packetHash);
        tx.setSenderVpa(instruction.getSenderVpa());
        tx.setReceiverVpa(instruction.getReceiverVpa());
        tx.setAmount(instruction.getAmount());
        tx.setSignedAt(
                Instant.ofEpochMilli(instruction.getSignedAt()));
        tx.setSettledAt(Instant.now());
        tx.setBridgeNodeId(bridgeNodeId);
        tx.setHopCount(hopCount);

        // Mark as REJECTED ❌
        // Money did NOT move!
        tx.setStatus(Transaction.Status.REJECTED);

        // Save rejected record to database
        // Returns saved transaction
        return transactions.save(tx);
    }
}