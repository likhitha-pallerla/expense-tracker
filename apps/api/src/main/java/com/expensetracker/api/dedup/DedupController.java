package com.expensetracker.api.dedup;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.expensetracker.api.security.CurrentUser;

/**
 * The duplicate review queue: pairs the engine thought were probably, but not
 * certainly, the same payment.
 */
@RestController
@RequestMapping("/api/duplicates")
public class DedupController {

    private final DedupService dedup;

    public DedupController(DedupService dedup) {
        this.dedup = dedup;
    }

    @GetMapping
    public List<DedupService.PendingPair> pending(@RequestParam(defaultValue = "50") int limit) {
        return dedup.pending(CurrentUser.id(), limit);
    }

    /** Drives the nav badge, so the user notices a queue without visiting it. */
    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("pending", dedup.pendingCount(CurrentUser.id()));
    }

    /** "Yes, these are the same payment." */
    @PostMapping("/{id}/merge")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void merge(@PathVariable UUID id) {
        dedup.resolveMerge(CurrentUser.id(), id);
    }

    /** "No, I really did pay twice." */
    @PostMapping("/{id}/keep-both")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void keepBoth(@PathVariable UUID id) {
        dedup.resolve(CurrentUser.id(), id, "kept_both");
    }

    /** "Stop asking me about this pair." */
    @PostMapping("/{id}/dismiss")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void dismiss(@PathVariable UUID id) {
        dedup.resolve(CurrentUser.id(), id, "dismissed");
    }
}
