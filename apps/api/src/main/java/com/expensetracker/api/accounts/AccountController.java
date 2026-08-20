package com.expensetracker.api.accounts;

import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public List<AccountView> list(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return accounts.list(CurrentUser.id(), includeArchived);
    }

    @GetMapping("/{id}")
    public AccountView get(@PathVariable UUID id) {
        return accounts.get(CurrentUser.id(), id);
    }

    @PostMapping
    public ResponseEntity<AccountView> create(@Valid @RequestBody AccountRequest request) {
        AccountView created = accounts.create(CurrentUser.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public AccountView update(@PathVariable UUID id, @Valid @RequestBody AccountRequest request) {
        return accounts.update(CurrentUser.id(), id, request);
    }

    /**
     * Removes the account, or archives it when transactions still reference it.
     * The response says which happened so the UI can explain the outcome.
     */
    @DeleteMapping("/{id}")
    public Map<String, Object> delete(@PathVariable UUID id) {
        AccountService.DeleteResult result = accounts.delete(CurrentUser.id(), id);
        return Map.of(
                "deleted", result.deleted(),
                "archived", !result.deleted(),
                "transactionCount", result.transactionCount());
    }
}
