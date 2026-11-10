package com.codearena.account;

import com.codearena.security.Tokens;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) { this.accounts = accounts; }

    @PostMapping("/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    AccountService.AuthResult register(@Valid @RequestBody AccountService.Registration request) {
        return accounts.register(request);
    }

    @PostMapping("/auth/login")
    AccountService.AuthResult login(@Valid @RequestBody AccountService.Login request) {
        return accounts.login(request);
    }

    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@AuthenticationPrincipal Jwt jwt) { accounts.logout(Tokens.userId(jwt)); }

    @GetMapping({"/auth/me", "/users/me"})
    AccountService.Profile profile(@AuthenticationPrincipal Jwt jwt) { return accounts.profile(Tokens.userId(jwt)); }

    @PutMapping("/users/me")
    AccountService.Profile update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AccountService.ProfileUpdate request) {
        return accounts.update(Tokens.userId(jwt), request);
    }

    @PutMapping("/users/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AccountService.PasswordUpdate request) {
        accounts.changePassword(Tokens.userId(jwt), request);
    }

    @GetMapping("/users/{username}")
    AccountService.PublicProfile publicProfile(@PathVariable String username) { return accounts.publicProfile(username); }
}
