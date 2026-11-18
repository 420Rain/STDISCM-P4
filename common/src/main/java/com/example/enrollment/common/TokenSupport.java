package com.example.enrollment.common;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/** Shared HS256 token code. Every service validates access tokens locally; no auth-service call is needed. */
public final class TokenSupport {
    private TokenSupport() { }

    private static SecretKey key(String secret) {
        if (secret == null || secret.length() < 32) throw new IllegalStateException("JWT_SECRET must contain at least 32 characters");
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public static String issue(long userId, String username, String role, String secret, Duration lifetime) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .claim("username", username)
                .claim("role", role)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(lifetime)))
                .signWith(key(secret), Jwts.SIG.HS256)
                .compact();
    }

    public static TokenPrincipal parse(String token, String secret) {
        Claims claims = Jwts.parser().verifyWith(key(secret)).build().parseSignedClaims(token).getPayload();
        return new TokenPrincipal(Long.parseLong(claims.getSubject()), claims.get("username", String.class),
                claims.get("role", String.class), claims.getId());
    }

    public static TokenPrincipal require(HttpServletRequest request, String secret) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "A Bearer access token is required.");
        }
        try {
            return parse(header.substring(7), secret);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Access token is invalid or expired.");
        }
    }

    public static TokenPrincipal requireRole(HttpServletRequest request, String secret, String expectedRole) {
        TokenPrincipal principal = require(request, secret);
        if (!expectedRole.equals(principal.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This operation is not allowed for your account role.");
        }
        return principal;
    }
}
