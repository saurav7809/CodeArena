package com.codearena.account;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "users")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    Long id;
    String username;
    String email;
    String passwordHash;
    String role;
    boolean enabled;
    int tokenVersion;
    Instant createdAt;
    Instant updatedAt;

    protected Account() {}

    Account(String username, String email, String passwordHash, String role) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    public long id() { return id; }
    public String role() { return role; }
    public boolean enabled() { return enabled; }
    public int tokenVersion() { return tokenVersion; }

    void revokeTokens() {
        tokenVersion++;
        updatedAt = Instant.now();
    }
}
