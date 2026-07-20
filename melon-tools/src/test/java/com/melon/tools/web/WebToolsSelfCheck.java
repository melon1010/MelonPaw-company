package com.melon.tools.web;

/** Lightweight security and optional live-network verification for the public web tools. */
public final class WebToolsSelfCheck {

    private WebToolsSelfCheck() {
    }

    public static void main(String[] args) {
        assertBlocked("http://127.0.0.1/");
        assertBlocked("http://169.254.169.254/latest/meta-data/");
        assertBlocked("http://198.18.0.1/");
        assertBlocked("http://[::1]/");
        assertBlocked("http://user:password@example.com/");
        assertBlocked("https://example.com:8443/");

        if (args.length > 0 && "--network".equals(args[0])) {
            String page = new WebFetchTool().fetch("https://example.com", 2_000);
            if (!page.contains("Example Domain")) {
                throw new AssertionError("web_fetch did not return readable public page content");
            }
            String results = new WebSearchTool().search("Java 17 documentation", 3, null);
            if (!results.contains("URL: http")) {
                throw new AssertionError("web_search did not return public result URLs");
            }
        }
    }

    private static void assertBlocked(String url) {
        try {
            WebAccessPolicy.requirePublicHttpUri(url);
            throw new AssertionError("expected URL to be blocked: " + url);
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
