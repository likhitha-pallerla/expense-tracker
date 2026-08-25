package com.expensetracker.api.entry;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expensetracker.api.security.CurrentUser;

/**
 * Typing a payment in words.
 *
 * <p>There is one endpoint and it does not create anything. The client posts a
 * sentence, gets back a filled-in draft, and creates the transaction through
 * the ordinary {@code POST /api/transactions} once the user has confirmed it.
 *
 * <p>Keeping creation out of here is deliberate. It means natural entry cannot
 * develop its own quietly different rules about defaults, duplicate detection
 * or balances: every transaction in the system, however it was described, goes
 * through the same door. It also means this endpoint is safe to call on every
 * keystroke if the client ever wants a live preview.
 */
@RestController
@RequestMapping("/api/entry")
public class EntryController {

    private final NaturalEntryService entries;

    public EntryController(NaturalEntryService entries) {
        this.entries = entries;
    }

    /**
     * @param text what the user typed; capped well above a real sentence so a
     *             pasted email is rejected as input rather than read as one
     */
    public record ParseRequest(
            @NotBlank(message = "Type what you spent, like \"250 lunch\".")
            @Size(max = 500, message = "That is longer than one payment.")
            String text) {
    }

    @PostMapping("/parse")
    public EntrySuggestion parse(@jakarta.validation.Valid @RequestBody ParseRequest request) {
        return entries.suggest(CurrentUser.id(), request.text());
    }
}
