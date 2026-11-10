package com.codearena.contest;

import com.codearena.api.PageResult;
import com.codearena.security.Tokens;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ContestController {
    private final ContestService contests;

    public ContestController(ContestService contests) { this.contests = contests; }

    @GetMapping("/contests")
    PageResult<ContestService.Card> browse(@AuthenticationPrincipal Jwt jwt,
        @RequestParam(defaultValue = "ALL") String status, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "12") int size) {
        return contests.browse(Tokens.userId(jwt), status, page, size, false);
    }

    @GetMapping("/contests/{id}")
    ContestService.Detail detail(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return contests.detail(id, Tokens.userId(jwt), false);
    }

    @PostMapping("/contests/{id}/register")
    ContestService.Detail register(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return contests.register(id, Tokens.userId(jwt));
    }

    @GetMapping("/contests/{id}/leaderboard")
    PageResult<ContestService.Rank> leaderboard(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "15") int size) {
        return contests.leaderboard(id, page, size);
    }

    @GetMapping("/admin/contests")
    PageResult<ContestService.Card> adminBrowse(@RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "12") int size) {
        return contests.browse(0, "ALL", page, size, true);
    }

    @GetMapping("/admin/contests/{id}")
    ContestService.Detail adminDetail(@PathVariable long id) { return contests.detail(id, 0, true); }

    @GetMapping("/admin/contests/{id}/participants")
    PageResult<ContestService.Participant> participants(@PathVariable long id, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "15") int size) {
        return contests.participants(id, page, size);
    }

    @PostMapping("/admin/contests")
    @ResponseStatus(HttpStatus.CREATED)
    ContestService.Detail create(@Valid @RequestBody ContestService.Edit edit) { return contests.create(edit); }

    @PutMapping("/admin/contests/{id}")
    ContestService.Detail update(@PathVariable long id, @Valid @RequestBody ContestService.Edit edit) {
        return contests.update(id, edit);
    }
}
