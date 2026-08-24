package com.expensetracker.api.sms;

import com.expensetracker.api.security.CurrentUser;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Where the Android app sends what it found.
 *
 * <p>Only the phone posts here. The web application has no equivalent, because
 * a browser cannot read text messages — which is precisely why this route
 * exists rather than mail sync simply being pointed at a second source.
 */
@RestController
@RequestMapping("/api/sms")
public class SmsController {

    private final SmsService sms;

    public SmsController(SmsService sms) {
        this.sms = sms;
    }

    /**
     * Accepts a batch of candidate messages.
     *
     * <p>{@code parse} defaults to true so that a phone which uploads a single
     * alert as it arrives sees the transaction immediately. An app doing a
     * first full scan should pass {@code false} on every batch but the last:
     * parsing after each of twenty batches repeats the same work twenty times
     * for no benefit.
     */
    @PostMapping
    public SmsIngestResult ingest(
            @RequestBody SmsBatchRequest request,
            @RequestParam(defaultValue = "true") boolean parse) {
        try {
            return sms.ingest(CurrentUser.id(), request, parse);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * The rules the phone is expected to apply before uploading.
     *
     * <p>Published so the app can show the user exactly what will and will not
     * leave their device, in the app's own words rather than a policy document
     * nobody reads. It also gives the mobile test suite a way to notice that
     * the server has grown a rule the client has not.
     */
    @GetMapping("/policy")
    public Map<String, Object> policy() {
        return Map.of(
                "maxMessagesPerBatch", SmsBatchRequest.MAX_MESSAGES,
                "maxBodyLength", SmsFilter.MAX_BODY_LENGTH,
                "personalNumberDigits", SmsFilter.PERSONAL_NUMBER_LENGTH,
                "rejectionReasons", SmsFilter.rejectionReasons().stream().map(Enum::name).toList(),
                "collects", List.of("sender", "body", "receivedAt"),
                "neverCollects", List.of("contact names", "thread ids", "device serial numbers",
                        "messages from phone numbers", "one-time passwords"));
    }
}
