package com.melon.tools.web;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Fetches readable content from a public URL without executing page scripts. */
public class WebFetchTool {

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_DOWNLOAD_BYTES = 2_000_000;
    private static final int DEFAULT_MAX_CHARS = 24_000;
    private static final int MAX_OUTPUT_CHARS = 50_000;
    private static final Pattern CHARSET = Pattern.compile("(?i)charset=([a-zA-Z0-9._-]+)");
    private static final String USER_AGENT = "MelonPaw-Company/1.0 (+public-web-fetch)";

    private final HttpClient client;

    public WebFetchTool() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build());
    }

    WebFetchTool(HttpClient client) {
        this.client = client;
    }

    @Tool(name = "web_fetch", description = "Fetch readable text from one known public HTTP/HTTPS URL. "
            + "The tool blocks local/private networks, does not execute scripts, and limits response size. "
            + "Use this instead of shell commands such as curl or wget.")
    public String fetch(
            @ToolParam(name = "url", description = "Public http or https URL to retrieve") String url,
            @ToolParam(name = "max_chars", required = false,
                    description = "Maximum returned characters, from 1000 to 50000; defaults to 24000")
            Integer maxChars) {
        int outputLimit = clamp(maxChars != null ? maxChars : DEFAULT_MAX_CHARS, 1_000, MAX_OUTPUT_CHARS);
        URI original = WebAccessPolicy.requirePublicHttpUri(url);
        URI current = original;

        try {
            for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/json,text/plain,application/xml;q=0.9,*/*;q=0.2")
                        .header("Accept-Encoding", "identity")
                        .GET()
                        .build();
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    int status = response.statusCode();
                    if (status >= 300 && status < 400) {
                        if (redirect == MAX_REDIRECTS) {
                            throw new IllegalArgumentException("Too many redirects");
                        }
                        current = WebAccessPolicy.requireSafeRedirect(
                                original, current, response.headers().firstValue("location").orElse(""));
                        continue;
                    }
                    if (status < 200 || status >= 300) {
                        return "Web fetch failed with HTTP " + status + " for " + current;
                    }
                    long declaredLength = response.headers().firstValueAsLong("content-length").orElse(-1);
                    if (declaredLength > MAX_DOWNLOAD_BYTES) {
                        throw new IllegalArgumentException("Response exceeds the 2 MB download limit");
                    }
                    byte[] bytes = readLimited(body, MAX_DOWNLOAD_BYTES);
                    String contentType = response.headers().firstValue("content-type")
                            .orElse("application/octet-stream");
                    return render(current, contentType, bytes, outputLimit);
                }
            }
            throw new IllegalArgumentException("Too many redirects");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Web fetch was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Web fetch failed: " + e.getMessage(), e);
        }
    }

    private String render(URI source, String rawContentType, byte[] bytes, int maxChars) throws IOException {
        String contentType = rawContentType.toLowerCase(Locale.ROOT);
        if (contentType.contains("text/html") || contentType.contains("application/xhtml+xml")) {
            Document document = Jsoup.parse(new ByteArrayInputStream(bytes), null, source.toString());
            document.select("script,style,noscript,iframe,object,embed,svg,canvas,form,nav,footer").remove();
            return truncate(renderHtml(source, rawContentType, document), maxChars);
        }
        if (contentType.startsWith("text/") || contentType.contains("json")
                || contentType.contains("xml") || contentType.contains("javascript")) {
            Charset charset = detectCharset(rawContentType);
            String text = new String(bytes, charset).replace("\u0000", "").trim();
            return truncate("Source: " + source + "\nContent-Type: " + rawContentType + "\n\n" + text, maxChars);
        }
        return "Source: " + source + "\nContent-Type: " + rawContentType + "\nSize: " + bytes.length
                + " bytes\n\nThis URL returned binary content. web_fetch only returns readable text and does not execute or decode binary files.";
    }

    private String renderHtml(URI source, String contentType, Document document) {
        StringBuilder out = new StringBuilder();
        out.append("Source: ").append(source).append('\n');
        out.append("Content-Type: ").append(contentType).append('\n');
        if (!document.title().isBlank()) {
            out.append("Title: ").append(document.title().trim()).append('\n');
        }
        out.append('\n');

        Element root = document.selectFirst("main, article");
        if (root == null || root.text().length() < 200) root = document.body();
        if (root == null) return out.append("No readable page content was found.").toString();

        Set<String> emitted = new LinkedHashSet<>();
        Elements blocks = root.select("h1,h2,h3,h4,p,li,pre,blockquote");
        for (Element block : blocks) {
            String text = block.text().replaceAll("\\s+", " ").trim();
            if (text.isBlank() || !emitted.add(text)) continue;
            String tag = block.normalName();
            if (tag.matches("h[1-4]")) {
                int level = tag.charAt(1) - '0';
                out.append("#".repeat(level)).append(' ').append(text).append("\n\n");
            } else if ("li".equals(tag)) {
                out.append("- ").append(text).append('\n');
            } else if ("pre".equals(tag)) {
                out.append("```\n").append(block.wholeText().trim()).append("\n```\n\n");
            } else if ("blockquote".equals(tag)) {
                out.append("> ").append(text).append("\n\n");
            } else {
                out.append(text).append("\n\n");
            }
        }
        if (blocks.isEmpty()) out.append(root.text().trim()).append('\n');

        Set<String> links = new LinkedHashSet<>();
        for (Element anchor : root.select("a[href]")) {
            if (links.size() >= 20) break;
            String absolute = anchor.absUrl("href");
            String label = anchor.text().replaceAll("\\s+", " ").trim();
            if ((absolute.startsWith("https://") || absolute.startsWith("http://")) && !label.isBlank()) {
                links.add(label + "\t" + absolute);
            }
        }
        if (!links.isEmpty()) {
            out.append("\n## Page links\n");
            for (String link : links) {
                String[] parts = link.split("\\t", 2);
                out.append("- [").append(parts[0]).append("](").append(parts[1]).append(")\n");
            }
        }
        return out.toString().trim();
    }

    private static byte[] readLimited(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IllegalArgumentException("Response exceeds the 2 MB download limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static Charset detectCharset(String contentType) {
        Matcher matcher = CHARSET.matcher(contentType);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (RuntimeException ignored) {
                // Fall through to UTF-8.
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static String truncate(String value, int limit) {
        if (value.length() <= limit) return value;
        return value.substring(0, limit) + "\n\n[Content truncated at " + limit + " characters]";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
