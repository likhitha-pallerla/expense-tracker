package com.expensetracker.api.transactions;

import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.expensetracker.api.security.CurrentUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

    private final TransactionService transactions;

    public TransactionController(TransactionService transactions) {
        this.transactions = transactions;
    }

    @GetMapping
    public TransactionService.Page list(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) UUID accountId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) UUID merchantId,
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) Boolean includeExcluded,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {

        TransactionFilter filter = new TransactionFilter(
                from, to, accountId, categoryId, merchantId, kind, search,
                minAmount, maxAmount, includeExcluded, limit, offset);

        return transactions.list(CurrentUser.id(), filter);
    }

    @GetMapping("/{id}")
    public TransactionView get(@PathVariable UUID id) {
        return transactions.get(CurrentUser.id(), id);
    }

    @PostMapping
    public ResponseEntity<TransactionView> create(@Valid @RequestBody TransactionRequest request) {
        TransactionView created = transactions.create(CurrentUser.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping("/{id}")
    public TransactionView update(@PathVariable UUID id, @Valid @RequestBody TransactionRequest request) {
        return transactions.update(CurrentUser.id(), id, request);
    }

    /** Soft delete; both legs go together when the row is part of a transfer. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        transactions.delete(CurrentUser.id(), id);
    }

    /** Creates both legs of a transfer and returns them, debit leg first. */
    @PostMapping("/transfers")
    public ResponseEntity<List<TransactionView>> createTransfer(@Valid @RequestBody TransferRequest request) {
        List<TransactionView> legs = transactions.createTransfer(CurrentUser.id(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(legs);
    }
}
