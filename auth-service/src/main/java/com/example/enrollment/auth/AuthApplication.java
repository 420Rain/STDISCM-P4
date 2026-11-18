package com.example.enrollment.auth;

import com.example.enrollment.common.ApiExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootApplication
@Import(ApiExceptionHandler.class)
public class AuthApplication {
    public static void main(String[] args) { SpringApplication.run(AuthApplication.class, args); }

    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean CommandLineRunner initializeAuthDatabase(JdbcTemplate jdbc, PasswordEncoder encoder) {
        return args -> {
            jdbc.execute("CREATE TABLE IF NOT EXISTS users (id BIGSERIAL PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE, email VARCHAR(180) NOT NULL UNIQUE, password_hash TEXT NOT NULL, role VARCHAR(20) NOT NULL CHECK (role IN ('STUDENT','FACULTY')), google_sub VARCHAR(255) UNIQUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            jdbc.execute("ALTER TABLE users ADD COLUMN IF NOT EXISTS full_name VARCHAR(160) NOT NULL DEFAULT ''");
            jdbc.execute("CREATE TABLE IF NOT EXISTS auth_sessions (session_id UUID PRIMARY KEY, user_id BIGINT NOT NULL REFERENCES users(id), jti VARCHAR(80) NOT NULL, refresh_hash VARCHAR(128) NOT NULL UNIQUE, expires_at TIMESTAMPTZ NOT NULL, revoked BOOLEAN NOT NULL DEFAULT false, created_at TIMESTAMPTZ NOT NULL DEFAULT now())");
            // Deterministic demo identities. IDs 1-5 are referenced by the sample sections in course-service.
            seed(jdbc, encoder, "student1", "student1@demo.local", "Student One", "STUDENT", "Student123!");
            seed(jdbc, encoder, "faculty1", "faculty1@demo.local", "Alex Morgan", "FACULTY", "Faculty123!");
            seed(jdbc, encoder, "faculty2", "faculty2@demo.local", "Jamie Reyes", "FACULTY", "Faculty123!");
            seed(jdbc, encoder, "faculty3", "faculty3@demo.local", "Taylor Santos", "FACULTY", "Faculty123!");
            seed(jdbc, encoder, "student2", "student2@demo.local", "Student Two", "STUDENT", "Student123!");
        };
    }

    private static void seed(JdbcTemplate jdbc, PasswordEncoder encoder, String username, String email, String fullName, String role, String password) {
        Integer found = jdbc.queryForObject("SELECT count(*) FROM users WHERE username = ?", Integer.class, username);
        if (found == null || found == 0) {
            jdbc.update("INSERT INTO users(username,email,full_name,password_hash,role) VALUES (?,?,?,?,?)", username, email, fullName, encoder.encode(password), role);
        } else {
            jdbc.update("UPDATE users SET full_name=? WHERE username=? AND (full_name IS NULL OR full_name='')", fullName, username);
        }
    }
}
