package com.professoros.service;

import com.google.gson.*;
import com.professoros.model.DomainValidationException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;

/** Optional online boundary. Only publishable configuration and short-lived user tokens are used.
 * Passwords/tokens are never persisted or logged. Redirects are never followed. */
public final class PortalPublishingClient {
    private final URI base;
    private final String key;
    private final HttpClient http;
    private final Gson json = new Gson();
    private String token;

    public PortalPublishingClient(String url, String publishableKey) {
        this(URI.create(url == null ? "" : url), publishableKey,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build());
        if (!"https".equals(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null
                || base.getQuery() != null || base.getFragment() != null || !base.getPath().isEmpty()
                || publishableKey == null || !publishableKey.startsWith("sb_publishable_")) {
            throw new DomainValidationException("Configure the HTTPS Supabase project origin and publishable key.");
        }
    }
    // Package-private transport injection for isolated HTTP contract tests, never used by the UI.
    PortalPublishingClient(URI origin, String publishableKey, HttpClient transport) {
        base = origin; key = publishableKey; http = transport;
    }
    private JsonElement request(String method, String path, Object body, String prefer) throws Exception {
        var builder = HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(30))
                .header("apikey", key).header("Content-Type", "application/json");
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (prefer != null) builder.header("Prefer", prefer);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.toJson(body)));
        var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new DomainValidationException("Portal request rejected (HTTP " + response.statusCode() + "). Check sign-in, MFA, mapping and connectivity; no success is assumed.");
        return response.body().isBlank() ? JsonNull.INSTANCE : JsonParser.parseString(response.body());
    }
    public void signIn(String email, String password, String code) throws Exception {
        if (token != null) throw new IllegalStateException("Sign out before signing in again.");
        if (email == null || email.isBlank() || password == null || password.isBlank() || code == null || !code.matches("[0-9]{6}"))
            throw new DomainValidationException("Enter professor email, password and current six-digit code.");
        var signed = request("POST", "/auth/v1/token?grant_type=password", Map.of("email", email.strip(), "password", password), null).getAsJsonObject();
        token = signed.get("access_token").getAsString();
        var user = request("GET", "/auth/v1/user", null, null).getAsJsonObject();
        String factor = null;
        if (user.has("factors")) for (var item : user.getAsJsonArray("factors")) {
            var f = item.getAsJsonObject();
            if ("totp".equals(f.get("factor_type").getAsString()) && "verified".equals(f.get("status").getAsString())) {
                factor = f.get("id").getAsString(); break;
            }
        }
        if (factor == null) throw new DomainValidationException("Enroll a professor authenticator in the portal first.");
        var challenge = request("POST", "/auth/v1/factors/" + factor + "/challenge", Map.of(), null).getAsJsonObject();
        var verified = request("POST", "/auth/v1/factors/" + factor + "/verify", Map.of("challenge_id", challenge.get("id").getAsString(), "code", code), null).getAsJsonObject();
        token = verified.get("access_token").getAsString();
        if (!"professor".equals(request("POST", "/rest/v1/rpc/portal_access", Map.of(), null).getAsString()))
            throw new DomainValidationException("An MFA-verified professor role is required.");
    }
    public void publish(GradePublication publication) throws Exception {
        if (token == null) throw new DomainValidationException("Sign in with MFA before publishing.");
        var payload = Map.of("offering_id", publication.offering().toString(), "student_id", publication.student().toString(),
                "score", publication.score(), "letter", publication.letter());
        var rows = request("POST", "/rest/v1/portal_published_grades?on_conflict=offering_id,student_id", payload,
                "resolution=merge-duplicates,return=representation").getAsJsonArray();
        if (rows.size() != 1) throw new DomainValidationException("Publication was not confirmed. Retry the same mapping safely.");
    }
    public void signOut() throws Exception {
        if (token == null) return;
        try { request("POST", "/auth/v1/logout?scope=local", Map.of(), null); }
        finally { token = null; }
    }
}
