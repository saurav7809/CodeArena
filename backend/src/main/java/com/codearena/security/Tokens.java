package com.codearena.security;

import com.codearena.account.Account;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

@Component
public class Tokens {
    public static final String ISSUER = "codearena";
    private final JwtEncoder encoder;

    public Tokens(JwtEncoder encoder) { this.encoder = encoder; }

    public String issue(Account account) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer(ISSUER).subject(Long.toString(account.id()))
            .issuedAt(now).expiresAt(now.plus(2, ChronoUnit.HOURS)).claim("version", account.tokenVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public static long userId(Jwt jwt) { return jwt == null ? 0 : Long.parseLong(jwt.getSubject()); }
}
