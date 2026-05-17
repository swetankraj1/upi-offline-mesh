package com.demo.upimesh.model;

// JPA annotations to map this class to a database table
import jakarta.persistence.*;

import java.math.BigDecimal;

@Entity // @Entity tells Spring "Create a database table for this class"
@Table(name = "accounts") //@Table names the actual table in the DB - without this it defaults to the class name
public class Account {

    // @Id = primary key of the table
    // vpa = virtual payment address (like alice@demo - same as our UPI Id)
    @Id
    private String vpa;

    // @Column(nullable = false) = this field cannot be empty in database
    @Column(nullable = false)
    private String holderName;

    // precision = total digits allowed(19), scale = decimal places(2) - so, 500.00
    // BigDecimal is used because floating point types like double cause rounding errors which are unacceptable in
    // financial systems
    @Column(nullable = false, precision = 19, scale = 2)
    private BigDecimal balance;

    // @Version = optimistic Locking
    // Prevents double sending if two payments hit simultaneously,
    // second one sees version changed and throws error - money is safe
    // Example: Alice has Rs500, two Rs300 payments come in - second one fails
    @Version
    private Long version;

    // Empty constructor - JPA requires this to create objects when reading from DB
    public Account(){}

    // our constructor - used when seeding accounts (alice, bob, charlie) on startup
    public Account(String vpa, String holderName, BigDecimal balance) {
        this.vpa = vpa;
        this.holderName = holderName;
        this.balance = balance;
    }

    // Getters and Setters - JPA and Spring use these to read/write field values
    // Without these, JPA cannot access the fields
    public String getVpa() { return vpa; }
    public void setVpa(String vpa) { this.vpa = vpa;}

    public String getHolderName() { return holderName; }
    public void setHolderName(String holderName) { this.holderName = holderName; }

    public BigDecimal getBalance() { return balance;}
    public void setBalance(BigDecimal balance) { this.balance = balance; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version;}
}
/*

TABLE: accounts
+------------+-------------+---------+---------+
| vpa        | holderName  | balance | version |
+------------+-------------+---------+---------+
| alice@demo | Alice       | 5000.00 |    0    |  ← version 0 = never updated
| bob@demo   | Bob         | 3000.00 |    0    |  ← version 0 = never updated
| charlie@demo| Charlie    | 2000.00 |    0    |  ← version 0 = never updated
+------------+-------------+---------+---------+
      ↑              ↑           ↑          ↑
    @Id          nullable      money     optimistic
    primary       = false    BigDecimal    locking
    key          required   precision=19
    scale=2

*/