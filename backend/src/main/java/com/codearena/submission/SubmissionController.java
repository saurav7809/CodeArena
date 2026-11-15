package com.codearena.submission;

import com.codearena.api.PageResult;
import com.codearena.security.Tokens;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SubmissionController {
    private final SubmissionService submissions;

    public SubmissionController(SubmissionService submissions) { this.submissions = submissions; }

    @PostMapping("/submissions/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SubmissionService.Card run(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubmissionService.Request request,
        @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return submissions.queue(Tokens.userId(jwt), request, false, key);
    }

    @PostMapping("/submissions/submit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SubmissionService.Card submit(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SubmissionService.Request request,
        @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        return submissions.queue(Tokens.userId(jwt), request, true, key);
    }

    @GetMapping("/submissions")
    PageResult<SubmissionService.Card> browse(@AuthenticationPrincipal Jwt jwt, @RequestParam(required = false) Long problemId,
        @RequestParam(defaultValue = "") String language, @RequestParam(defaultValue = "") String verdict,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int size) {
        return submissions.browse(Tokens.userId(jwt), problemId, language, verdict, page, size, false, null);
    }

    @GetMapping("/problems/{problemId}/submissions")
    PageResult<SubmissionService.Card> problemHistory(@AuthenticationPrincipal Jwt jwt, @PathVariable long problemId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "15") int size) {
        return submissions.browse(Tokens.userId(jwt), problemId, "", "", page, size, false, null);
    }

    @GetMapping("/submissions/{id}")
    SubmissionService.Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return submissions.detail(id, Tokens.userId(jwt), false);
    }

    @GetMapping("/submissions/{id}/result")
    SubmissionService.Result result(@AuthenticationPrincipal Jwt jwt, @PathVariable long id) {
        return submissions.result(id, Tokens.userId(jwt));
    }

    @GetMapping("/admin/submissions")
    PageResult<SubmissionService.Card> adminBrowse(@RequestParam(required = false) Long problemId,
        @RequestParam(required = false) Long userId, @RequestParam(defaultValue = "") String language,
        @RequestParam(defaultValue = "") String verdict, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "15") int size) {
        return submissions.browse(0, problemId, language, verdict, page, size, true, userId);
    }

    @GetMapping("/admin/submissions/{id}")
    SubmissionService.Detail adminDetail(@PathVariable long id) { return submissions.detail(id, 0, true); }
}
