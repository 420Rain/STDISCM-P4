package com.example.enrollment.common;

public record TokenPrincipal(long userId, String username, String role, String tokenId) { }
