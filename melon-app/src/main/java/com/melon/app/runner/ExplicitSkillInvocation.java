package com.melon.app.runner;

/** Separates the model-facing skill prompt from the user-visible slash command. */
public final class ExplicitSkillInvocation {

    private static final String PREFIX =
            "The user explicitly invoked the workspace skill named '";
    private static final String NAME_SUFFIX = "'. Treat the following SKILL.md as mandatory instructions.";
    private static final String INSTRUCTIONS_OPEN = "<skill_instructions>";
    private static final String USER_OPEN = "<user_request>";
    private static final String USER_CLOSE = "</user_request>";
    private static final String DEFAULT_REQUEST = "Apply this skill and report the result.";

    private ExplicitSkillInvocation() {
    }

    public static String modelPrompt(String skillName, String instructions, String request) {
        String effectiveRequest = request == null || request.isBlank() ? DEFAULT_REQUEST : request.trim();
        return PREFIX + skillName + NAME_SUFFIX
                + " Load any referenced skill resources when needed, then complete the user request.\n\n"
                + INSTRUCTIONS_OPEN + "\n" + instructions + "\n</skill_instructions>\n\n"
                + USER_OPEN + "\n" + effectiveRequest + "\n" + USER_CLOSE;
    }

    /** Restores a persisted model-facing prompt to the command the user intended to see. */
    public static String userVisibleText(String text) {
        if (text == null || !text.startsWith(PREFIX)) return text;
        int nameEnd = text.indexOf(NAME_SUFFIX, PREFIX.length());
        int requestStart = text.lastIndexOf(USER_OPEN);
        int requestEnd = text.lastIndexOf(USER_CLOSE);
        if (nameEnd < 0 || requestStart < 0 || requestEnd < requestStart) return text;

        String skillName = text.substring(PREFIX.length(), nameEnd).trim();
        String request = text.substring(requestStart + USER_OPEN.length(), requestEnd).trim();
        if (skillName.isBlank()) return request;
        if (request.isBlank() || DEFAULT_REQUEST.equals(request)) return "/" + skillName;
        return "/" + skillName + " " + request;
    }

    public static boolean isModelPrompt(String text) {
        return text != null && text.startsWith(PREFIX)
                && text.contains(INSTRUCTIONS_OPEN)
                && text.contains(USER_OPEN);
    }
}
