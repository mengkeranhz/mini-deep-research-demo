package org.example.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** JDK HttpClient GET 小工具（字节 / 字符串）。 */
public final class Http {
    public static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public record Response(int status, String contentType, byte[] body) {}

    public static Response get(String url) throws Exception {
        HttpResponse<byte[]> resp = CLIENT.send(
                HttpRequest.newBuilder(URI.create(url.strip()))
                        .header("User-Agent", USER_AGENT)
                        .timeout(Duration.ofSeconds(60))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        return new Response(resp.statusCode(),
                resp.headers().firstValue("Content-Type").orElse("").toLowerCase(),
                resp.body());
    }

    public static Response post(String url, Map<String, String> headers, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url.strip()))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        headers.forEach(builder::header);
        HttpResponse<byte[]> resp = CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Response(resp.statusCode(),
                resp.headers().firstValue("Content-Type").orElse("").toLowerCase(),
                resp.body());
    }

    public static String getString(String url) throws Exception {
        Response r = get(url);
        if (r.status() != 200) {
            throw new IllegalStateException("HTTP " + r.status() + " <- " + url);
        }
        return new String(r.body(), charsetOf(r.contentType()));
    }

    /** 从 Content-Type 解析 charset，缺省 UTF-8。 */
    public static Charset charsetOf(String contentType) {
        if (contentType == null) return StandardCharsets.UTF_8;
        int i = contentType.indexOf("charset=");
        if (i < 0) return StandardCharsets.UTF_8;
        String name = contentType.substring(i + "charset=".length()).split(";")[0].strip();
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    private Http() {}
}
