package com.example.enrollment.auth;

import com.example.enrollment.common.TokenPrincipal;
import com.example.enrollment.common.TokenSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final String jwtSecret;
    private final int accessMinutes;
    private final int refreshDays;
    private final String googleClientId;
    private final RestClient restClient = RestClient.create();

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
            @Value("${app.jwt-secret}") String jwtSecret,
            @Value("${app.access-token-minutes:10}") int accessMinutes,
            @Value("${app.refresh-token-days:7}") int refreshDays,
            @Value("${app.google-client-id:}") String googleClientId) {
        this.jdbc = jdbc; this.passwordEncoder = passwordEncoder; this.jwtSecret = jwtSecret;
        this.accessMinutes = accessMinutes; this.refreshDays = refreshDays; this.googleClientId = googleClientId;
    }

    public record RegisterRequest(String username, String email, String password) { }
    public record LoginRequest(String usernameOrEmail, String password) { }
    public record GoogleRequest(String credential) { }
    public record RefreshRequest(String refreshToken) { }
    public record LogoutRequest(String refreshToken) { }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody RegisterRequest request) {
        if (blank(request.username()) || blank(request.email()) || request.password() == null || request.password().length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Username, email, and a password of at least 8 characters are required.");
        }
        log.info("[DB SELECT] Check username/email registration conflict username={}", request.username().trim());
        if (jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(username)=lower(?) OR lower(email)=lower(?)", Integer.class, request.username().trim(), request.email().trim()) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That username or email is already registered.");
        }
        Long id = jdbc.queryForObject("INSERT INTO users(username,email,full_name,password_hash,role) VALUES (?,?,?,?,'STUDENT') RETURNING id",
                Long.class, request.username().trim(), request.email().trim().toLowerCase(), request.username().trim(), passwordEncoder.encode(request.password()));
        log.info("[DB INSERT] users id={} role=STUDENT", id);
        return Map.of("message", "Student account created. You can now sign in.", "userId", id);
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        log.info("[DB SELECT] user for login identifier={}", request.usernameOrEmail());
        var users = jdbc.query("SELECT id, username, email, full_name, password_hash, role FROM users WHERE lower(username)=lower(?) OR lower(email)=lower(?) LIMIT 1",
                (rs, row) -> new User(rs.getLong("id"), rs.getString("username"), rs.getString("email"), rs.getString("full_name"), rs.getString("password_hash"), rs.getString("role")),
                request.usernameOrEmail(), request.usernameOrEmail());
        if (users.isEmpty() || request.password() == null || !passwordEncoder.matches(request.password(), users.get(0).passwordHash())) {
            log.warn("[AUTH REJECT] Login rejected for identifier={}", request.usernameOrEmail());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Username/email or password is incorrect.");
        }
        return createSession(users.get(0));
    }

    @PostMapping("/google")
    public Map<String, Object> google(@RequestBody GoogleRequest request) {
        if (googleClientId == null || googleClientId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Google sign-in is not configured. Set GOOGLE_CLIENT_ID and rebuild the web image.");
        }
        if (request.credential() == null || request.credential().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Google credential is required.");
        try {
            @SuppressWarnings("unchecked") Map<String, Object> claims = restClient.get()
                    .uri("https://oauth2.googleapis.com/tokeninfo?id_token={token}", request.credential())
                    .retrieve().body(Map.class);
            if (claims == null || !googleClientId.equals(claims.get("aud")) || !"true".equalsIgnoreCase(String.valueOf(claims.get("email_verified")))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google identity token is invalid or its email is not verified.");
            }
            String email = Objects.toString(claims.get("email"), "").trim().toLowerCase();
            String googleSub = Objects.toString(claims.get("sub"), "").trim();
            if (email.isBlank() || !email.contains("@") || googleSub.isBlank()) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Google did not provide a valid verified email and account identifier.");
            }
            String fullName = Objects.toString(claims.get("name"), "").trim();
            if (fullName.isBlank()) fullName = email.substring(0, email.indexOf('@'));
            log.info("[DB SELECT] Find account for verified Google email={}", email);
            var users = jdbc.query("SELECT id,username,email,full_name,password_hash,role FROM users WHERE lower(email)=? OR google_sub=? LIMIT 1",
                    (rs, row) -> new User(rs.getLong("id"), rs.getString("username"), rs.getString("email"), rs.getString("full_name"), rs.getString("password_hash"), rs.getString("role")), email, googleSub);
            User user;
            if (users.isEmpty()) {
                String suggestedUsername = fullName.toLowerCase().replaceAll("[^a-z0-9]+", ".").replaceAll("^\\.|\\.$", "");
                String username = suggestedUsername.isBlank() ? "google.student" : suggestedUsername;
                if (jdbc.queryForObject("SELECT count(*) FROM users WHERE lower(username)=lower(?)", Integer.class, username) > 0) {
                    username = username + "." + googleSub.substring(0, Math.min(6, googleSub.length()));
                }
                String randomPassword = UUID.randomUUID().toString() + UUID.randomUUID();
                Long id = jdbc.queryForObject("INSERT INTO users(username,email,full_name,password_hash,role,google_sub) VALUES (?,?,?,?, 'STUDENT', ?) RETURNING id",
                        Long.class, username, email, fullName, passwordEncoder.encode(randomPassword), googleSub);
                user = new User(id, username, email, fullName, "", "STUDENT");
                log.info("[DB INSERT] Google student user id={}", id);
            } else {
                user = users.get(0);
                jdbc.update("UPDATE users SET google_sub=?, full_name=? WHERE id=?", googleSub, fullName, user.id());
                user = new User(user.id(), user.username(), user.email(), fullName, user.passwordHash(), user.role());
            }
            return createSession(user);
        } catch (ResponseStatusException ex) { throw ex; }
        catch (Exception ex) {
            log.warn("Google token verification failed", ex);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Could not verify the Google sign-in response.");
        }
    }

    @PostMapping("/refresh")
    public Map<String, Object> refresh(@RequestBody RefreshRequest request) {
        if (request.refreshToken() == null || request.refreshToken().isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refresh token is required.");
        String hash = sha256(request.refreshToken());
        log.info("[DB SELECT] refresh session by token hash");
        var rows = jdbc.query("SELECT s.session_id,s.user_id,u.username,u.email,u.full_name,u.role,s.expires_at,s.revoked FROM auth_sessions s JOIN users u ON u.id=s.user_id WHERE s.refresh_hash=?",
                (rs, row) -> new SessionUser(rs.getString("session_id"), rs.getLong("user_id"), rs.getString("username"), rs.getString("email"), rs.getString("full_name"), rs.getString("role"), rs.getTimestamp("expires_at").toInstant(), rs.getBoolean("revoked")), hash);
        if (rows.isEmpty() || rows.get(0).revoked() || rows.get(0).expiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Session is expired or has been revoked. Please sign in again.");
        }
        SessionUser user = rows.get(0);
        return rotateSession(user.sessionId(), new User(user.userId(), user.username(), user.email(), user.fullName(), "", user.role()));
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(@RequestBody LogoutRequest request, HttpServletRequest httpRequest) {
        int changed;
        if (request.refreshToken() != null && !request.refreshToken().isBlank()) {
            // The refresh-token hash is the session credential, so logout still works when the short access JWT has expired.
            changed = jdbc.update("UPDATE auth_sessions SET revoked=true WHERE refresh_hash=?", sha256(request.refreshToken()));
        } else {
            TokenPrincipal principal = TokenSupport.require(httpRequest, jwtSecret);
            changed = jdbc.update("UPDATE auth_sessions SET revoked=true WHERE user_id=? AND jti=?", principal.userId(), principal.tokenId());
        }
        log.info("[DB UPDATE] auth_sessions revoked rows={}", changed);
        return Map.of("message", "Session revoked. This browser will be signed out; an already-issued access JWT remains valid only until its short expiry.");
    }

    private Map<String, Object> createSession(User user) {
        UUID sessionId = UUID.randomUUID();
        String refresh = UUID.randomUUID() + "." + UUID.randomUUID();
        Instant expires = Instant.now().plus(Duration.ofDays(refreshDays));
        String access = TokenSupport.issue(user.id(), user.username(), user.role(), jwtSecret, Duration.ofMinutes(accessMinutes));
        String jti = TokenSupport.parse(access, jwtSecret).tokenId();
        jdbc.update("INSERT INTO auth_sessions(session_id,user_id,jti,refresh_hash,expires_at) VALUES (?,?,?,?,?)",
                sessionId, user.id(), jti, sha256(refresh), java.sql.Timestamp.from(expires));
        log.info("[DB INSERT] auth_sessions userId={} sessionId={}", user.id(), sessionId);
        return response(user, access, refresh);
    }

    private Map<String, Object> rotateSession(String sessionId, User user) {
        String refresh = UUID.randomUUID() + "." + UUID.randomUUID();
        String access = TokenSupport.issue(user.id(), user.username(), user.role(), jwtSecret, Duration.ofMinutes(accessMinutes));
        String jti = TokenSupport.parse(access, jwtSecret).tokenId();
        jdbc.update("UPDATE auth_sessions SET jti=?, refresh_hash=?, expires_at=?, revoked=false WHERE session_id=?",
                jti, sha256(refresh), java.sql.Timestamp.from(Instant.now().plus(Duration.ofDays(refreshDays))), UUID.fromString(sessionId));
        log.info("[DB UPDATE] auth_sessions rotated sessionId={}", sessionId);
        return response(user, access, refresh);
    }

    private Map<String, Object> response(User user, String access, String refresh) {
        return Map.of("accessToken", access, "refreshToken", refresh, "tokenType", "Bearer",
                "expiresInSeconds", accessMinutes * 60, "user", Map.of("id", user.id(), "username", user.username(), "fullName", user.fullName() == null || user.fullName().isBlank() ? user.username() : user.fullName(), "email", user.email(), "role", user.role()));
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private record User(long id, String username, String email, String fullName, String passwordHash, String role) { }
    private record SessionUser(String sessionId, long userId, String username, String email, String fullName, String role, Instant expiresAt, boolean revoked) { }
}
