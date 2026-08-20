package com.expensetracker.api.parsing;

import java.util.List;
import java.util.Map;
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
 * Reading stored alerts.
 *
 * <p>Separate from {@code /api/sync} because they fail for different reasons
 * and are worth retrying at different times: fetching depends on a provider
 * being reachable, reading depends only on the rules. A user whose bank we do
 * not understand yet should be able to press "try again" without spending
 * another provider quota.
 */
@RestController
@RequestMapping("/api/parse")
public class ParseController {

    private final ParseService parsing;

    public ParseController(ParseService parsing) {
        this.parsing = parsing;
    }

    @PostMapping
    public ParseResult parse() {
        return parsing.parseAll(CurrentUser.id());
    }

    @GetMapping("/queue")
    public ParseQueue queue() {
        return parsing.queue(CurrentUser.id());
    }

    @GetMapping("/unread")
    public List<UnreadMessage> unread(@RequestParam(defaultValue = "50") int limit) {
        return parsing.unread(CurrentUser.id(), limit);
    }

    /**
     * Puts every failure back in the queue and reads them again straight away,
     * because "retry" that leaves the work for later is not what the word means
     * to the person pressing it.
     */
    @PostMapping("/retry")
    public ParseResult retry() {
        UUID userId = CurrentUser.id();
        parsing.retryFailed(userId);
        return parsing.parseAll(userId);
    }

    @PostMapping("/{messageId}/ignore")
    public Map<String, Object> ignore(@PathVariable UUID messageId) {
        if (!parsing.ignore(CurrentUser.id(), messageId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "That message is not waiting to be read.");
        }
        return Map.of("ignored", true);
    }
}
