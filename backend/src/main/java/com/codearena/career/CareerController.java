package com.codearena.career;

import com.codearena.security.Tokens;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/career")
class CareerController {
    private final CareerService career;

    CareerController(CareerService career) { this.career = career; }

    record SkillUpdate(@NotNull Boolean completed) {}

    @GetMapping("/roadmaps")
    ResponseEntity<CareerService.Roadmaps> roadmaps(@AuthenticationPrincipal Jwt jwt) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(career.roadmaps(Tokens.userId(jwt)));
    }

    @PutMapping("/skills/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void update(@AuthenticationPrincipal Jwt jwt, @PathVariable String id, @Valid @RequestBody SkillUpdate request) {
        career.updateSkill(Tokens.userId(jwt), id, request.completed());
    }

    @GetMapping("/plans/{track}")
    CareerService.Plan plan(@AuthenticationPrincipal Jwt jwt, @PathVariable String track) {
        return career.plan(Tokens.userId(jwt), track);
    }

    @DeleteMapping("/plans/{track}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deletePlan(@AuthenticationPrincipal Jwt jwt, @PathVariable String track) {
        career.deletePlan(Tokens.userId(jwt), track);
    }
}
