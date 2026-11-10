package com.codearena.stats;

import com.codearena.account.AccountService;
import com.codearena.api.PageResult;
import com.codearena.security.Tokens;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class StatsController {
    private final StatsService stats;
    private final AccountService accounts;

    public StatsController(StatsService stats, AccountService accounts) {
        this.stats = stats;
        this.accounts = accounts;
    }

    @GetMapping("/stats/public")
    StatsService.Totals totals() { return stats.totals(); }

    @GetMapping("/leaderboard/{period}")
    PageResult<StatsService.Rank> leaderboard(@PathVariable String period, @RequestParam(defaultValue = "points") String sort,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int size) {
        return stats.leaderboard(period, sort, page, size);
    }

    @GetMapping("/users/me/stats")
    StatsService.Dashboard dashboard(@AuthenticationPrincipal Jwt jwt) { return stats.dashboard(Tokens.userId(jwt)); }

    @GetMapping({"/admin/dashboard", "/admin/analytics"})
    StatsService.Admin admin() { return stats.admin(); }

    @GetMapping("/admin/users")
    PageResult<AccountService.Profile> users(@RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int size) {
        return accounts.list(search, page, size);
    }

    @PutMapping("/admin/users/{id}/access")
    AccountService.Profile access(@AuthenticationPrincipal Jwt jwt, @PathVariable long id,
        @Valid @RequestBody AccountService.UserAccess request) {
        return accounts.access(Tokens.userId(jwt), id, request);
    }
}
