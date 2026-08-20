package com.expensetracker.api.recurring;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.expensetracker.api.security.CurrentUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/recurring")
public class RecurringController {

    private final RecurringService recurring;

    public RecurringController(RecurringService recurring) {
        this.recurring = recurring;
    }

    @GetMapping
    public List<RecurringView> list(
            @RequestParam(name = "includeDismissed", defaultValue = "false") boolean includeDismissed) {
        return recurring.list(CurrentUser.id(), includeDismissed);
    }

    @GetMapping("/{id}")
    public RecurringView get(@PathVariable UUID id) {
        return recurring.get(CurrentUser.id(), id);
    }

    @PostMapping
    public ResponseEntity<RecurringView> create(@Valid @RequestBody RecurringRequest request) {
        RecurringView created = recurring.create(CurrentUser.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * A suggestion has no id to address, so dismissing one goes by the key the
     * detector filed it under.
     */
    @PostMapping("/dismiss")
    public RecurringView dismiss(@RequestBody DismissRequest request) {
        return recurring.dismiss(CurrentUser.id(), request.matchKey());
    }

    @PutMapping("/{id}")
    public RecurringView update(@PathVariable UUID id, @Valid @RequestBody RecurringRequest request) {
        return recurring.update(CurrentUser.id(), id, request);
    }

    /** Deleting a dismissal is how a suggestion comes back. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        recurring.delete(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    public record DismissRequest(String matchKey) {
    }
}
