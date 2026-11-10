package com.codearena.config;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Profile("prod")
class ProductionChecks {
    @Bean
    static BeanFactoryPostProcessor validateProductionSettings(Environment env) {
        return factory -> check(env);
    }

    static void check(Environment env) {
        String origin = required(env, "arena.frontend-origin", "CODEARENA_FRONTEND_ORIGIN");
        String originError = "Set CODEARENA_FRONTEND_ORIGIN to the exact HTTPS origin of the frontend.";
        URI frontend = parseUri(origin, originError);
        if (!"https".equals(frontend.getScheme()) || frontend.getHost() == null || frontend.getUserInfo() != null
            || !frontend.getPath().isEmpty() || frontend.getQuery() != null || frontend.getFragment() != null) {
            throw new IllegalArgumentException(originError);
        }

        checkDatabaseUrl(required(env, "spring.datasource.url", "CODEARENA_DB_URL"));
        required(env, "spring.datasource.username", "CODEARENA_DB_USER");
        required(env, "spring.datasource.password", "CODEARENA_DB_PASSWORD");
        String admin = env.getProperty("arena.admin-password", "");
        if (!admin.isBlank() && admin.length() < 16) {
            throw new IllegalArgumentException("Use at least 16 characters for CODEARENA_ADMIN_PASSWORD in production.");
        }
        for (String key : new String[]{"max-active", "per-user-per-minute", "daily-cases"}) {
            if (env.getProperty("arena.execution." + key, Integer.class, 0) < 1) {
                throw new IllegalArgumentException("Production requires a positive arena.execution." + key + " limit.");
            }
        }
    }

    private static void checkDatabaseUrl(String jdbcUrl) {
        String error = "Set CODEARENA_DB_URL to a PostgreSQL JDBC URL with sslmode=verify-full and separate credentials.";
        if (!jdbcUrl.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException(error);
        URI database = parseUri(jdbcUrl.substring(5), error);
        if (database.getHost() == null || database.getRawPath().length() < 2 || database.getRawQuery() == null
            || database.getRawUserInfo() != null || database.getRawFragment() != null) {
            throw new IllegalArgumentException(error);
        }
        var options = new HashMap<String, String>();
        for (String option : database.getRawQuery().split("&")) {
            String[] entry = option.split("=", 2);
            String key = URLDecoder.decode(entry[0], StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (entry.length != 2 || (key.equals("sslmode") && !entry[0].equals("sslmode"))
                || options.putIfAbsent(key, URLDecoder.decode(entry[1], StandardCharsets.UTF_8)) != null) {
                throw new IllegalArgumentException(error);
            }
        }
        if (!"verify-full".equals(options.get("sslmode")) || options.containsKey("sslhostnameverifier")
            || options.containsKey("socketfactory") || options.containsKey("user") || options.containsKey("password")
            || !options.getOrDefault("sslfactory", "org.postgresql.ssl.DefaultJavaSSLFactory")
                .equals("org.postgresql.ssl.DefaultJavaSSLFactory")) {
            throw new IllegalArgumentException(error);
        }
    }

    private static URI parseUri(String value, String message) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(message);
        }
    }

    private static String required(Environment env, String key, String variable) {
        String value = env.getProperty(key, "");
        if (value.isBlank()) throw new IllegalArgumentException("Set " + variable + " for production.");
        return value;
    }
}
