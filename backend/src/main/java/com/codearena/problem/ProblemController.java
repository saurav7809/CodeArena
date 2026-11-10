package com.codearena.problem;

import com.codearena.api.PageResult;
import com.codearena.security.Tokens;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ProblemController {
    private final ProblemService problems;

    public ProblemController(ProblemService problems) { this.problems = problems; }

    @GetMapping("/problems")
    PageResult<ProblemService.Card> browse(
        @AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "") String difficulty, @RequestParam(defaultValue = "") String category,
        @RequestParam(defaultValue = "ALL") String solved, @RequestParam(defaultValue = "number") String sort,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "12") int size) {
        return problems.browse(Tokens.userId(jwt), search, difficulty, category, solved, sort, page, size, false);
    }

    @GetMapping("/problems/{id}")
    ProblemService.Detail byId(@PathVariable long id, @AuthenticationPrincipal Jwt jwt) {
        return problems.byId(id, Tokens.userId(jwt), false);
    }

    @GetMapping("/problems/slug/{slug}")
    ProblemService.Detail bySlug(@PathVariable String slug, @AuthenticationPrincipal Jwt jwt) {
        return problems.bySlug(slug, Tokens.userId(jwt));
    }

    @GetMapping("/admin/problems")
    PageResult<ProblemService.Card> adminList(@RequestParam(defaultValue = "") String search,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "12") int size) {
        return problems.browse(0, search, "", "", "ALL", "newest", page, size, true);
    }

    @GetMapping("/admin/problems/{id}")
    ProblemService.Detail adminDetail(@PathVariable long id) { return problems.byId(id, 0, true); }

    @PostMapping("/admin/problems")
    @ResponseStatus(HttpStatus.CREATED)
    ProblemService.Detail create(@Valid @RequestBody ProblemService.Edit request) { return problems.create(request); }

    @PutMapping("/admin/problems/{id}")
    ProblemService.Detail update(@PathVariable long id, @Valid @RequestBody ProblemService.Edit request) {
        return problems.update(id, request);
    }

    @DeleteMapping("/admin/problems/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void archive(@PathVariable long id) { problems.archive(id); }
}
