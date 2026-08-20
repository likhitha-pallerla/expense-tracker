package com.expensetracker.api.connections;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

@RestController
@RequestMapping("/api/connections")
public class ConnectionController {

    private final ConnectionService connections;

    public ConnectionController(ConnectionService connections) {
        this.connections = connections;
    }

    /** Every supported provider, whether it is set up, and what is connected. */
    @GetMapping
    public List<Map<String, Object>> list() {
        return connections.providers(CurrentUser.id());
    }

    /**
     * Starts the handshake and hands back the URL to visit.
     *
     * <p>A POST that returns a URL rather than a redirect, because the caller
     * is the web app's server, not the browser: it holds the user's token,
     * which a redirect could not carry. The browser is sent on afterwards.
     */
    @PostMapping("/{provider}/start")
    public Map<String, String> start(
            @PathVariable String provider,
            @RequestBody(required = false) Map<String, String> body) {

        MailProvider mailProvider = MailProvider.from(provider)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Unknown provider: " + provider));

        String returnPath = body == null ? null : body.get("returnPath");
        return Map.of("authorizeUrl",
                connections.startAuthorization(CurrentUser.id(), mailProvider, returnPath));
    }

    /**
     * Where the provider sends the browser back to.
     *
     * <p>Unauthenticated by necessity — it is a plain navigation with no header
     * we control — so it trusts nothing in the request except the state, which
     * it looks up. Always answers with a redirect into the web app, including
     * on failure: leaving someone on a JSON error page at the API's domain,
     * mid-flow, with no navigation, is not an outcome worth having.
     */
    @GetMapping("/callback/{provider}")
    public ResponseEntity<Void> callback(
            @PathVariable String provider,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error) {

        String destination = connections.completeAuthorization(state, code, error);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(destination)).build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disconnect(@PathVariable UUID id) {
        connections.disconnect(CurrentUser.id(), id);
        return ResponseEntity.noContent().build();
    }
}
