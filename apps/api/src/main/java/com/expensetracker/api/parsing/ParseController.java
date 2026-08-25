package com.expensetracker.api.parsing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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

    /** Senders whose messages are being held, grouped so each is one decision. */
    @GetMapping("/held")
    public List<ParseService.HeldSender> held() {
        return parsing.heldSenders(CurrentUser.id());
    }

    @GetMapping("/trusted")
    public List<ParseService.TrustedSender> trusted() {
        return parsing.trustedSenders(CurrentUser.id());
    }

    /**
     * Accepts a sender and releases everything held from it.
     *
     * <p>Refusal is a 400 with the reason in it, because the reason is the
     * point: the user asked for something that would undo the protection, and
     * a bare error code would read like a bug.
     */
    @PostMapping("/trusted")
    public Map<String, Object> trust(@RequestBody TrustRequest request) {
        try {
            int released = parsing.trustSender(CurrentUser.id(), request.domain(),
                    request.note());
            return Map.of("domain", request.domain(), "released", released);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @DeleteMapping("/trusted/{domain}")
    public Map<String, Object> untrust(@PathVariable String domain) {
        if (!parsing.untrustSender(CurrentUser.id(), domain)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "That sender is not on your list.");
        }
        return Map.of("removed", true);
    }

    /** Throws away everything held from a sender, without trusting it. */
    @PostMapping("/held/discard")
    public Map<String, Object> discardHeld(@RequestBody DiscardRequest request) {
        return Map.of("discarded",
                parsing.discardHeld(CurrentUser.id(), request.sender()));
    }

    /** @param note optional, e.g. the name of the bank, for the user's own recall */
    public record TrustRequest(String domain, String note) {
    }

    public record DiscardRequest(String sender) {
    }
}
