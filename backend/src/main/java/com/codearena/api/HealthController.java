package com.codearena.api;

import com.codearena.submission.JudgeGateway;
import org.springframework.dao.DataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class HealthController {
    private final JdbcClient jdbc;
    private final JudgeGateway judge;

    HealthController(JdbcClient jdbc, JudgeGateway judge) {
        this.jdbc = jdbc;
        this.judge = judge;
    }

    record Health(String status, boolean judgeConfigured) {}

    @GetMapping("/api/health")
    ResponseEntity<Health> health() {
        try {
            jdbc.sql("SELECT 1").query(Integer.class).single();
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Health("UP", judge.configured()));
        } catch (DataAccessException error) {
            return ResponseEntity.status(503).cacheControl(CacheControl.noStore()).body(new Health("DOWN", false));
        }
    }
}
