package com.expensetracker.api.sync;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.expensetracker.api.security.CurrentUser;

/**
 * Asking for new mail.
 *
 * <p>These are {@code POST}s even though nothing is being created in the usual
 * sense, because they are emphatically not safe to repeat blindly: each one
 * spends provider quota and advances a cursor. A {@code GET} would be
 * prefetched by a browser and retried by every proxy in the way.
 */
@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncService sync;

    public SyncController(SyncService sync) {
        this.sync = sync;
    }

    @PostMapping
    public List<SyncRunView> syncAll() {
        return sync.syncAll(CurrentUser.id());
    }

    @PostMapping("/{connectionId}")
    public SyncRunView syncOne(@PathVariable UUID connectionId) {
        try {
            return sync.sync(CurrentUser.id(), connectionId);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @GetMapping("/runs")
    public List<SyncRunView> runs(@RequestParam(defaultValue = "10") int limit) {
        return sync.history(CurrentUser.id(), limit);
    }
}
