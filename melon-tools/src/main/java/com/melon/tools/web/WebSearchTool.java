package com.melon.tools.web;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Searches public web pages without delegating research to a shell command. */
public class WebSearchTool {

    private static final int DEFAULT_RESULTS = 5;
    private static final int MAX_RESULTS = 10;
    private static final int MAX_RESPONSE_CHARS = 1_500_000;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; MelonPaw-Company/1.0; +public-web-search)";

    private final HttpClient client;

    public WebSearchTool() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    WebSearchTool(HttpClient client) {
        this.client = client;
    }

    @Tool(name = "web_search", description = "Search the public web for current sources and return titles, snippets, and URLs. "
            + "Use web_fetch to read a selected result. Do not use shell commands for web search.")
    public String search(
            @ToolParam(name = "query", description = "Search query") String query,
            @ToolParam(name = "max_results", required = false,
                    description = "Number of results from 1 to 10; defaults to 5") Integer maxResults,
            @ToolParam(name = "allowed_domains", required = false,
                    description = "Optional domain allow-list, for example [\"docs.spring.io\"]")
            List<String> allowedDomains) {
        if (query == null || query.isBlank()) throw new IllegalArgumentException("Search query is required");
        String normalizedQuery = query.trim();
        if (normalizedQuery.length() > 500) throw new IllegalArgumentException("Search query is too long");
        int limit = Math.max(1, Math.min(MAX_RESULTS, maxResults != null ? maxResults : DEFAULT_RESULTS));
        Set<String> domains = normalizeDomains(allowedDomains);

        try {
            List<SearchResult> results = searchDuckDuckGo(normalizedQuery, limit, domains);
            if (results.isEmpty()) results = searchBingRss(normalizedQuery, limit, domains);
            return render(normalizedQuery, results);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Web search was interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Web search failed: " + e.getMessage(), e);
        }
    }

    private List<SearchResult> searchDuckDuckGo(String query, int limit, Set<String> domains)
            throws IOException, InterruptedException {
        String requestQuery = applyDomainFilter(query, domains);
        URI uri = URI.create("https://html.duckduckgo.com/html/?q=" + encode(requestQuery));
        String body = get(uri, "text/html,application/xhtml+xml");
        if (body.isBlank()) return List.of();
        Document document = Jsoup.parse(body, uri.toString());
        List<SearchResult> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element result : document.select(".result")) {
            Element anchor = result.selectFirst("a.result__a");
            if (anchor == null) continue;
            String target = decodeDuckDuckGoUrl(anchor.absUrl("href"));
            if (!isAcceptedResult(target, domains) || !seen.add(target)) continue;
            Element snippet = result.selectFirst(".result__snippet");
            results.add(new SearchResult(anchor.text().trim(), target,
                    snippet != null ? snippet.text().trim() : ""));
            if (results.size() >= limit) break;
        }
        return results;
    }

    private List<SearchResult> searchBingRss(String query, int limit, Set<String> domains)
            throws IOException, InterruptedException {
        URI uri = URI.create("https://www.bing.com/search?format=rss&q="
                + encode(applyDomainFilter(query, domains)));
        String body = get(uri, "application/rss+xml,application/xml,text/xml");
        if (body.isBlank()) return List.of();
        Document document = Jsoup.parse(body, "", Parser.xmlParser());
        List<SearchResult> results = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element item : document.select("item")) {
            String target = item.select("link").text().trim();
            if (!isAcceptedResult(target, domains) || !seen.add(target)) continue;
            results.add(new SearchResult(item.select("title").text().trim(), target,
                    item.select("description").text().trim()));
            if (results.size() >= limit) break;
        }
        return results;
    }

    private String get(URI uri, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .header("Accept", accept)
                .header("Accept-Encoding", "identity")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) return "";
        String body = response.body() != null ? response.body() : "";
        if (body.length() > MAX_RESPONSE_CHARS) {
            throw new IllegalStateException("Search provider response exceeded the size limit");
        }
        return body;
    }

    private static String render(String query, List<SearchResult> results) {
        if (results.isEmpty()) return "No public web results found for: " + query;
        StringBuilder out = new StringBuilder("Web search results for: ").append(query).append("\n\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult result = results.get(i);
            out.append(i + 1).append(". ").append(result.title()).append('\n');
            out.append("   URL: ").append(result.url()).append('\n');
            if (!result.snippet().isBlank()) out.append("   ").append(result.snippet()).append('\n');
            out.append('\n');
        }
        return out.toString().trim();
    }

    private static String decodeDuckDuckGoUrl(String raw) {
        if (raw == null || raw.isBlank()) return "";
        try {
            URI uri = URI.create(raw.startsWith("//") ? "https:" + raw : raw);
            if (uri.getHost() != null && uri.getHost().endsWith("duckduckgo.com")
                    && uri.getRawQuery() != null) {
                for (String pair : uri.getRawQuery().split("&")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2 && "uddg".equals(parts[0])) {
                        return URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
                    }
                }
            }
            return uri.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean isAcceptedResult(String rawUrl, Set<String> domains) {
        try {
            URI uri = URI.create(rawUrl);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null) return false;
            if (domains.isEmpty()) return true;
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            return domains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static Set<String> normalizeDomains(List<String> allowedDomains) {
        Set<String> domains = new LinkedHashSet<>();
        if (allowedDomains == null) return domains;
        for (String value : allowedDomains) {
            if (value == null || value.isBlank()) continue;
            String domain = value.trim().toLowerCase(Locale.ROOT);
            if (domain.startsWith("http://") || domain.startsWith("https://")) {
                try {
                    domain = URI.create(domain).getHost();
                } catch (RuntimeException ignored) {
                    continue;
                }
            }
            if (domain != null) {
                domain = domain.replaceFirst("^\\*\\.", "").replaceAll("\\.$", "");
                if (domain.matches("[a-z0-9.-]+") && !domain.contains("..")) domains.add(domain);
            }
        }
        return domains;
    }

    private static String applyDomainFilter(String query, Set<String> domains) {
        if (domains.isEmpty()) return query;
        return query + " (" + domains.stream().map(domain -> "site:" + domain)
                .reduce((left, right) -> left + " OR " + right).orElse("") + ")";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record SearchResult(String title, String url, String snippet) {
    }
}
