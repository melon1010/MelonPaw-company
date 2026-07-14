package com.melon.app.controller;

final class AgentRequestSupport {
    private AgentRequestSupport() {}

    static String agentId(String value) {
        String id = value == null || value.isBlank() ? "default" : value.trim();
        if (!id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException("Invalid agent id");
        }
        return id;
    }
}
