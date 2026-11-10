package com.codearena.security;

import com.codearena.account.Account;
import com.codearena.account.AccountRepository;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecretKey jwtKey(@Value("${arena.jwt-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalArgumentException("Set CODEARENA_JWT_SECRET to at least 32 bytes.");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtKey) { return new NimbusJwtEncoder(new ImmutableSecret<>(jwtKey)); }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtKey) {
        var decoder = NimbusJwtDecoder.withSecretKey(jwtKey).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(Tokens.ISSUER));
        return decoder;
    }

    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    CorsConfigurationSource corsConfiguration(@Value("${arena.frontend-origin}") String origin) {
        var cors = new CorsConfiguration();
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AccountRepository accounts,
                                    @Qualifier("corsConfiguration") CorsConfigurationSource cors, Environment env)
        throws Exception {
        if (env.acceptsProfiles(Profiles.of("prod"))) http.addFilterAfter(new AuthThrottle(), CorsFilter.class);
        http.csrf(AbstractHttpConfigurer::disable)
            .cors(config -> config.configurationSource(cors))
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(rules -> rules
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers("/api/users/me", "/api/users/me/**", "/api/auth/me", "/api/auth/logout").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/health", "/api/stats/public", "/api/problems", "/api/problems/*",
                    "/api/problems/slug/*", "/api/contests", "/api/contests/*", "/api/contests/*/leaderboard",
                    "/api/leaderboard/*", "/api/users/*", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .oauth2ResourceServer(resource -> resource
                .authenticationEntryPoint((request, response, error) -> unauthorized(response))
                .jwt(jwt -> jwt.jwtAuthenticationConverter(token -> authenticated(token, accounts))))
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, error) -> unauthorized(response))
                .accessDeniedHandler((request, response, error) ->
                    jsonError(response, 403, "You do not have access to this resource.")));
        return http.build();
    }

    private static JwtAuthenticationToken authenticated(Jwt token, AccountRepository accounts) {
        Object version = token.getClaim("version");
        Account account;
        try {
            account = accounts.findById(Tokens.userId(token)).orElse(null);
        } catch (NumberFormatException error) {
            throw invalid();
        }
        if (account == null || !account.enabled() || !(version instanceof Number number)
            || number.intValue() != account.tokenVersion()) {
            throw invalid();
        }
        return new JwtAuthenticationToken(token, List.of(new SimpleGrantedAuthority("ROLE_" + account.role())));
    }

    private static OAuth2AuthenticationException invalid() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_token"));
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setHeader("WWW-Authenticate", "Bearer");
        jsonError(response, 401, "Sign in to continue.");
    }

    private static void jsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"message\":\"" + message + "\"}");
    }
}
