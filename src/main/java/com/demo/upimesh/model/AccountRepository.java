package com.demo.upimesh.model;

import org.springframework.data.jpa.repository.JpaRepository;

// interface not class — we never write the implementation
// Spring automatically creates the implementation at startup
// Account = which entity to manage
// String = type of the ID field (vpa is a String)
public interface AccountRepository extends JpaRepository<Account, String> {
    // Empty! Spring gives us all operations for free
    // No need to write findById, save, findAll etc.
    // Spring generates all of them automatically
}
