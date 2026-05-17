package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// Transaction = which entity to manage
// Long = type of ID (auto generated number 1,2,3...)
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // Custom Method 1:
    // Get last 20 transactions for dashboard
    // Spring reads method name and generates SQL:
    // SELECT * FROM transactions ORDER BY id DESC LIMIT 20
    // findTop20  = only 20 rows
    // OrderById  = sort by id
    // Desc       = newest first
    List<Transaction> findTop20ByOrderByIdDesc();

    // Custom Method 2:
    // Check if payment already settled (defense in depth)
    // Spring reads method name and generates SQL:
    // SELECT COUNT(*) FROM transactions WHERE packetHash = ?
    // Returns true  = duplicate! reject payment!
    // Returns false = new payment! proceed!
    // This is Layer 2 idempotency after ConcurrentHashMap
    boolean existsByPacketHash(String packetHash);
}
