package com.expensetracker.api.goals;

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
@RequestMapping("/api/goals")
public class GoalController {

    private final GoalService goals;

    public GoalController(GoalService goals) {
        this.goals = goals;
    }

    @GetMapping
    public List<GoalView> list(
            @RequestParam(name = "includeClosed", defaultValue = "false") boolean includeClosed,
            @RequestParam(name = "withContributions", defaultValue = "false")
            boolean withContributions) {
        return goals.list(CurrentUser.id(), includeClosed, withContributions);
    }

    @GetMapping("/{id}")
    public GoalView get(@PathVariable UUID id) {
        return goals.get(CurrentUser.id(), id);
    }

    @PostMapping
    public ResponseEntity<GoalView> create(@Valid @RequestBody GoalRequest request) {
        GoalView created = goals.create(CurrentUser.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public GoalView update(@PathVariable UUID id, @Valid @RequestBody GoalRequest request) {
        return goals.update(CurrentUser.id(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        goals.delete(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Returns the whole goal rather than the new contribution, because the
     * interesting part of putting money aside is what it did to the projection.
     */
    @PostMapping("/{id}/contributions")
    public ResponseEntity<GoalView> contribute(
            @PathVariable UUID id, @Valid @RequestBody GoalContributionRequest request) {
        GoalView updated = goals.contribute(CurrentUser.id(), id, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(updated);
    }

    @DeleteMapping("/{id}/contributions/{contributionId}")
    public GoalView removeContribution(
            @PathVariable UUID id, @PathVariable UUID contributionId) {
        return goals.removeContribution(CurrentUser.id(), id, contributionId);
    }
}
