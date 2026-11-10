package com.codearena.account;

import com.codearena.api.PageResult;
import com.codearena.security.Tokens;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AccountService implements ApplicationRunner {
    private final AccountRepository accounts;
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    private final Tokens tokens;
    private final String adminPassword;
    private final String dummyPasswordHash;

    public AccountService(AccountRepository accounts, JdbcClient jdbc, PasswordEncoder passwords, Tokens tokens,
                          @Value("${arena.admin-password}") String adminPassword) {
        this.accounts = accounts;
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.adminPassword = adminPassword;
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    public record Registration(
        @NotBlank @Size(max = 80) String fullName,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_]{3,24}") String username,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotBlank @Size(max = 72) String confirmPassword) {}

    public record Login(@NotBlank @Size(max = 254) String identifier, @NotBlank @Size(max = 72) String password) {}
    public record ProfileUpdate(@NotBlank @Size(max = 80) String fullName, @Size(max = 280) String bio) {}
    public record PasswordUpdate(@NotBlank @Size(max = 72) String currentPassword,
                                 @NotBlank @Size(min = 8, max = 72) String newPassword) {}
    public record UserAccess(@NotBlank @Pattern(regexp = "USER|ADMIN") String role, boolean enabled) {}
    public record Profile(long id, String username, String email, String fullName, String bio, String role,
                          boolean enabled, Instant createdAt) {}
    public record PublicProfile(String username, String fullName, String bio, Instant joinedAt, long solved) {}
    public record AuthResult(String token, Profile user) {}

    @Transactional
    public AuthResult register(Registration request) {
        String username = normalized(request.username());
        String email = normalized(request.email());
        validatePassword(request.password());
        if (!request.password().equals(request.confirmPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The passwords do not match.");
        }
        if (username.equals("arena_admin") || email.equals("admin@codearena.local")
            || accounts.existsByUsernameOrEmail(username, email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username or email is already in use.");
        }
        return authenticated(insert(username, email, request.fullName().strip(), request.password(), "USER"));
    }

    public AuthResult login(Login request) {
        String identifier = normalized(request.identifier());
        Account account = accounts.findByUsernameOrEmail(identifier, identifier).orElse(null);
        String hash = account == null ? dummyPasswordHash : account.passwordHash;
        boolean matches = request.password().getBytes(StandardCharsets.UTF_8).length <= 72
            && passwords.matches(request.password(), hash);
        if (account == null || !account.enabled || !matches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Check your username or email and password.");
        }
        return authenticated(account);
    }

    private AuthResult authenticated(Account account) {
        return new AuthResult(tokens.issue(account), profile(account.id));
    }

    private Account insert(String username, String email, String fullName, String password, String role) {
        Account account = accounts.saveAndFlush(new Account(username, email, passwords.encode(password), role));
        jdbc.sql("INSERT INTO user_profiles (user_id, full_name) VALUES (:id, :name)")
            .param("id", account.id).param("name", fullName).update();
        return account;
    }

    public Profile profile(long id) {
        return jdbc.sql("""
            SELECT u.id, u.username, u.email, p.full_name, p.bio, u.role, u.enabled, u.created_at
            FROM users u JOIN user_profiles p ON p.user_id = u.id WHERE u.id = :id
            """).param("id", id).query(AccountService::mapProfile).optional().orElseThrow(AccountService::notFound);
    }

    public PublicProfile publicProfile(String username) {
        return jdbc.sql("""
            SELECT u.username, p.full_name, p.bio, u.created_at,
                (SELECT count(DISTINCT problem_id) FROM submissions
                 WHERE user_id = u.id AND mode = 'SUBMIT' AND verdict = 'ACCEPTED') AS solved
            FROM users u JOIN user_profiles p ON p.user_id = u.id
            WHERE u.username = :username AND u.enabled
            """).param("username", normalized(username)).query((rs, row) -> new PublicProfile(
                rs.getString("username"), rs.getString("full_name"), rs.getString("bio"),
                rs.getTimestamp("created_at").toInstant(), rs.getLong("solved"))).optional().orElseThrow(AccountService::notFound);
    }

    @Transactional
    public Profile update(long id, ProfileUpdate request) {
        jdbc.sql("UPDATE user_profiles SET full_name = :name, bio = :bio, updated_at = now() WHERE user_id = :id")
            .param("id", id).param("name", request.fullName().strip())
            .param("bio", request.bio() == null ? "" : request.bio().strip()).update();
        return profile(id);
    }

    @Transactional
    public void changePassword(long id, PasswordUpdate request) {
        Account account = accounts.findById(id).orElseThrow(AccountService::notFound);
        validatePassword(request.newPassword());
        if (request.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
            || !passwords.matches(request.currentPassword(), account.passwordHash)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The current password is incorrect.");
        }
        account.passwordHash = passwords.encode(request.newPassword());
        account.revokeTokens();
    }

    @Transactional
    public void logout(long id) {
        jdbc.sql("UPDATE users SET token_version = token_version + 1, updated_at = now() WHERE id = :id")
            .param("id", id).update();
    }

    public PageResult<Profile> list(String search, int page, int size) {
        int offset = PageResult.offset(page, size);
        String where = " WHERE strpos(lower(u.username || ' ' || u.email || ' ' || p.full_name), :search) > 0 ";
        String from = "FROM users u JOIN user_profiles p ON p.user_id = u.id" + where;
        String query = normalized(search);
        long total = jdbc.sql("SELECT count(*) " + from).param("search", query).query(Long.class).single();
        var items = jdbc.sql("SELECT u.*, p.full_name, p.bio " + from + " ORDER BY u.id DESC LIMIT :size OFFSET :offset")
            .param("search", query).param("size", size).param("offset", offset).query(AccountService::mapProfile).list();
        return new PageResult<>(items, page, size, total);
    }

    @Transactional
    public Profile access(long actor, long target, UserAccess update) {
        if (actor == target) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Manage another account to change its role or status.");
        }
        jdbc.sql("SELECT pg_advisory_xact_lock(534701)").query((rs, row) -> true).single();
        Account administrator = accounts.findById(actor).orElseThrow(AccountService::notFound);
        if (!administrator.enabled || !administrator.role.equals("ADMIN")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator access is required.");
        }
        Account account = accounts.findById(target).orElseThrow(AccountService::notFound);
        account.role = update.role();
        account.enabled = update.enabled();
        account.revokeTokens();
        accounts.flush();
        return profile(target);
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!adminPassword.isBlank() && !accounts.existsByUsernameOrEmail("arena_admin", "admin@codearena.local")) {
            validatePassword(adminPassword);
            insert("arena_admin", "admin@codearena.local", "Arena Admin", adminPassword, "ADMIN");
        }
    }

    private static Profile mapProfile(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new Profile(rs.getLong("id"), rs.getString("username"), rs.getString("email"),
            rs.getString("full_name"), rs.getString("bio"), rs.getString("role"), rs.getBoolean("enabled"),
            rs.getTimestamp("created_at").toInstant());
    }

    private static String normalized(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(String value) {
        if (value.length() < 8 || value.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Use at least eight characters and at most 72 UTF-8 bytes.");
        }
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found.");
    }
}
