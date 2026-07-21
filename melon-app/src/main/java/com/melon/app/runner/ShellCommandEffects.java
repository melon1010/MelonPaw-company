package com.melon.app.runner;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Conservatively identifies filesystem writes performed through a shell command. */
final class ShellCommandEffects {

    private static final Set<String> WRITERS = Set.of(
            "cp", "install", "mkdir", "mv", "tee", "touch", "truncate");
    private static final Set<String> SEPARATORS = Set.of("&&", "||", ";", "|", "\n", "&");

    private ShellCommandEffects() {
    }

    record Analysis(boolean writesFiles, boolean ambiguous, List<String> targets) {
    }

    static Analysis analyze(String command) {
        if (command == null || command.isBlank()) {
            return new Analysis(false, false, List.of());
        }
        List<String> tokens = tokenize(command);
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        boolean writes = false;
        boolean ambiguous = false;

        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (">".equals(token) || ">>".equals(token) || "&>".equals(token)
                    || "&>>".equals(token)) {
                if (i + 1 < tokens.size() && !isOperator(tokens.get(i + 1))) {
                    String target = tokens.get(++i);
                    if (!isSafeSink(target)) {
                        writes = true;
                        targets.add(target);
                    }
                } else {
                    writes = true;
                    ambiguous = true;
                }
            }
        }

        int segmentStart = 0;
        for (int i = 0; i <= tokens.size(); i++) {
            if (i == tokens.size() || SEPARATORS.contains(tokens.get(i))) {
                SegmentAnalysis segment = analyzeSegment(tokens.subList(segmentStart, i));
                writes |= segment.writes();
                ambiguous |= segment.ambiguous();
                targets.addAll(segment.targets());
                segmentStart = i + 1;
            }
        }
        return new Analysis(writes, ambiguous, List.copyOf(targets));
    }

    private static SegmentAnalysis analyzeSegment(List<String> rawTokens) {
        List<String> words = rawTokens.stream()
                .filter(token -> !isOperator(token))
                .filter(token -> !token.isBlank())
                .toList();
        if (words.isEmpty()) return SegmentAnalysis.NONE;

        int commandIndex = 0;
        while (commandIndex < words.size() && isAssignment(words.get(commandIndex))) commandIndex++;
        if (commandIndex >= words.size()) return SegmentAnalysis.NONE;
        String command = programName(words.get(commandIndex));
        List<String> args = words.subList(commandIndex + 1, words.size());

        if ("sed".equals(command) && args.stream().anyMatch(ShellCommandEffects::isInPlaceSedFlag)) {
            List<String> targets = positionalArguments(args);
            return new SegmentAnalysis(true, targets.isEmpty(), targets);
        }
        if ("dd".equals(command)) {
            List<String> targets = args.stream()
                    .filter(arg -> arg.startsWith("of=") && arg.length() > 3)
                    .map(arg -> arg.substring(3))
                    .toList();
            return targets.isEmpty() ? SegmentAnalysis.NONE : new SegmentAnalysis(true, false, targets);
        }
        if (!WRITERS.contains(command)) return SegmentAnalysis.NONE;

        List<String> rawPositional = positionalArguments(args);
        List<String> positional = rawPositional.stream().filter(target -> !isSafeSink(target)).toList();
        if (!rawPositional.isEmpty() && positional.isEmpty()) return SegmentAnalysis.NONE;
        if ("cp".equals(command) || "install".equals(command) || "mv".equals(command)) {
            positional = positional.isEmpty()
                    ? List.of()
                    : List.of(positional.get(positional.size() - 1));
        }
        return new SegmentAnalysis(true, positional.isEmpty(), positional);
    }

    private static List<String> positionalArguments(List<String> args) {
        List<String> result = new ArrayList<>();
        boolean optionsEnded = false;
        for (String arg : args) {
            if ("--".equals(arg)) {
                optionsEnded = true;
                continue;
            }
            if (!optionsEnded && arg.startsWith("-")) continue;
            result.add(arg);
        }
        return result;
    }

    private static boolean isInPlaceSedFlag(String value) {
        return "-i".equals(value) || value.startsWith("-i") || "--in-place".equals(value)
                || value.startsWith("--in-place=");
    }

    private static boolean isAssignment(String value) {
        int equals = value.indexOf('=');
        return equals > 0 && value.substring(0, equals).matches("[A-Za-z_][A-Za-z0-9_]*");
    }

    private static String programName(String value) {
        String normalized = value.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return (slash >= 0 ? normalized.substring(slash + 1) : normalized).toLowerCase(Locale.ROOT);
    }

    private static boolean isOperator(String value) {
        return SEPARATORS.contains(value) || Set.of(">", ">>", "<", "<<", "<<<", ">&", "<&", "&>", "&>>")
                .contains(value);
    }

    private static boolean isSafeSink(String value) {
        String normalized = value.replace('\\', '/').toLowerCase(Locale.ROOT);
        return "/dev/null".equals(normalized) || "/dev/stdout".equals(normalized)
                || "/dev/stderr".equals(normalized) || "nul".equals(normalized);
    }

    /** Tokenizes only the syntax needed for effect classification; substitutions remain ambiguous words. */
    private static List<String> tokenize(String command) {
        List<String> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean singleQuoted = false;
        boolean doubleQuoted = false;
        boolean escaped = false;

        for (int i = 0; i < command.length(); i++) {
            char ch = command.charAt(i);
            if (escaped) {
                word.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\' && !singleQuoted) {
                escaped = true;
                continue;
            }
            if (ch == '\'' && !doubleQuoted) {
                singleQuoted = !singleQuoted;
                continue;
            }
            if (ch == '"' && !singleQuoted) {
                doubleQuoted = !doubleQuoted;
                continue;
            }
            if (singleQuoted || doubleQuoted) {
                word.append(ch);
                continue;
            }
            if (ch == '\n') {
                flush(tokens, word);
                tokens.add("\n");
                continue;
            }
            if (Character.isWhitespace(ch)) {
                flush(tokens, word);
                continue;
            }
            if (ch == '&' && i + 1 < command.length() && command.charAt(i + 1) == '>') {
                flush(tokens, word);
                i++;
                if (i + 1 < command.length() && command.charAt(i + 1) == '>') {
                    i++;
                    tokens.add("&>>");
                } else {
                    tokens.add("&>");
                }
                continue;
            }
            if (ch == '>' || ch == '<') {
                flush(tokens, word);
                char next = i + 1 < command.length() ? command.charAt(i + 1) : '\0';
                if (next == ch) {
                    i++;
                    if (ch == '<' && i + 1 < command.length() && command.charAt(i + 1) == '<') {
                        i++;
                        tokens.add("<<<");
                    } else {
                        tokens.add(ch == '>' ? ">>" : "<<");
                    }
                } else if (next == '&') {
                    i++;
                    tokens.add(ch == '>' ? ">&" : "<&");
                } else {
                    tokens.add(String.valueOf(ch));
                }
                continue;
            }
            if (ch == ';' || ch == '|') {
                flush(tokens, word);
                if (i + 1 < command.length() && command.charAt(i + 1) == ch) {
                    tokens.add("" + ch + ch);
                    i++;
                } else {
                    tokens.add(String.valueOf(ch));
                }
                continue;
            }
            if (ch == '&') {
                flush(tokens, word);
                if (i + 1 < command.length() && command.charAt(i + 1) == '&') {
                    tokens.add("&&");
                    i++;
                } else {
                    tokens.add("&");
                }
                continue;
            }
            word.append(ch);
        }
        flush(tokens, word);
        return tokens;
    }

    private static void flush(List<String> tokens, StringBuilder word) {
        if (!word.isEmpty()) {
            tokens.add(word.toString());
            word.setLength(0);
        }
    }

    private record SegmentAnalysis(boolean writes, boolean ambiguous, List<String> targets) {
        private static final SegmentAnalysis NONE = new SegmentAnalysis(false, false, List.of());
    }
}
