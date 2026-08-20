package com.expensetracker.api.cards;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.expensetracker.api.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * Cards are addressed by their account id: a credit card *is* an account, and
 * giving the detail row its own identifier would let the two drift apart.
 */
@RestController
@RequestMapping("/api/cards")
public class CardController {

    private final CardService cards;

    public CardController(CardService cards) {
        this.cards = cards;
    }

    @GetMapping
    public List<CardView> list(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return cards.list(CurrentUser.id(), includeArchived);
    }

    @GetMapping("/{accountId}")
    public CardView get(@PathVariable UUID accountId) {
        return cards.get(CurrentUser.id(), accountId);
    }

    @PutMapping("/{accountId}")
    public CardView save(@PathVariable UUID accountId, @Valid @RequestBody CardRequest request) {
        return cards.save(CurrentUser.id(), accountId, request);
    }

    @DeleteMapping("/{accountId}")
    public ResponseEntity<Void> clear(@PathVariable UUID accountId) {
        cards.clear(CurrentUser.id(), accountId);
        return ResponseEntity.noContent().build();
    }
}
