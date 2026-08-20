package com.expensetracker.api.budgets;

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
@RequestMapping("/api/budgets")
public class BudgetController {

    private final BudgetService budgets;

    public BudgetController(BudgetService budgets) {
        this.budgets = budgets;
    }

    @GetMapping
    public List<BudgetView> list(
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return budgets.list(CurrentUser.id(), includeInactive);
    }

    @GetMapping("/{id}")
    public BudgetView get(@PathVariable UUID id) {
        return budgets.get(CurrentUser.id(), id);
    }

    @PostMapping
    public ResponseEntity<BudgetView> create(@Valid @RequestBody BudgetRequest request) {
        BudgetView created = budgets.create(CurrentUser.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public BudgetView update(@PathVariable UUID id, @Valid @RequestBody BudgetRequest request) {
        return budgets.update(CurrentUser.id(), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        budgets.delete(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }
}
