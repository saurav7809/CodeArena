package com.codearena.ai;

import com.codearena.security.Tokens;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ai")
class AiController {
    private final AiCoach coach;

    AiController(AiCoach coach) { this.coach = coach; }

    record Status(boolean enabled) {}

    @GetMapping("/status")
    ResponseEntity<Status> status() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Status(coach.enabled()));
    }

    @PostMapping("/practice")
    AiCoach.Reply practice(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AiCoach.PracticeRequest request) {
        return coach.practice(Tokens.userId(jwt), request);
    }

    @PostMapping("/career")
    AiCoach.Reply career(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AiCoach.CareerRequest request) {
        return coach.career(Tokens.userId(jwt), request);
    }
}
