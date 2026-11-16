package sqlancer.tidb.mixed;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Real v1 preparation transport. No fallback response, implicit retries, or credential logging. */
public final class TiDBDeepSeekPreparationClient implements TiDBMixedPreparationHarness.Responder {
    private final TiDBDeepSeekAdvisor.Transport transport;
    private final String model;
    private final BigDecimal hitPrice;
    private final BigDecimal missPrice;
    private final BigDecimal outputPrice;
    private final int maxTokens;
    private int remaining;
    public final JsonArray audit = new JsonArray();

    public TiDBDeepSeekPreparationClient(TiDBDeepSeekAdvisor.Transport transport, String model, int calls,
            int maxTokens, BigDecimal hitPrice, BigDecimal missPrice, BigDecimal outputPrice) {
        if (transport == null || model.isBlank() || calls < 0 || maxTokens < 1
                || List.of(hitPrice, missPrice, outputPrice).stream().anyMatch(p -> p.signum() < 0)) {
            throw new IllegalArgumentException("invalid preparation client configuration");
        }
        this.transport = transport;
        this.model = model;
        this.remaining = calls;
        this.maxTokens = maxTokens;
        this.hitPrice = hitPrice;
        this.missPrice = missPrice;
        this.outputPrice = outputPrice;
    }

    public static TiDBDeepSeekPreparationClient fromEnvironment(int calls) throws IOException {
        String raw = System.getenv("DEEPSEEK_API_KEY");
        if ((raw == null || raw.isBlank()) && System.getenv("DEEPSEEK_API_KEY_FILE") != null) {
            raw = Files.readString(Path.of(System.getenv("DEEPSEEK_API_KEY_FILE"))).trim();
        }
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("DEEPSEEK_API_KEY or DEEPSEEK_API_KEY_FILE is required; no AI fallback");
        }
        String key = raw;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        TiDBDeepSeekAdvisor.Transport transport = body -> {
            JsonObject requestBody = JsonParser.parseString(body).getAsJsonObject();
            long timeout = requestBody.get("_timeout_ms").getAsLong();
            requestBody.remove("_timeout_ms");
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
                    .timeout(Duration.ofMillis(timeout)).header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString())).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            return new TiDBDeepSeekAdvisor.Reply(response.statusCode(), response.body());
        };
        return new TiDBDeepSeekPreparationClient(transport,
                System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-flash"), calls, 3000,
                price("DEEPSEEK_HIT_USD_PER_M", "0.003"), price("DEEPSEEK_MISS_USD_PER_M", "0.15"),
                price("DEEPSEEK_OUTPUT_USD_PER_M", "0.60"));
    }

    private static BigDecimal price(String key, String fallback) {
        return new BigDecimal(System.getenv().getOrDefault(key, fallback));
    }

    public static BigDecimal cost(JsonObject usage, BigDecimal hitPrice, BigDecimal missPrice, BigDecimal outputPrice) {
        long prompt = usage.get("prompt_tokens").getAsLong();
        long output = usage.get("completion_tokens").getAsLong();
        long hit = usage.has("prompt_cache_hit_tokens") ? usage.get("prompt_cache_hit_tokens").getAsLong() : 0;
        long miss = usage.has("prompt_cache_miss_tokens") ? usage.get("prompt_cache_miss_tokens").getAsLong()
                : prompt - hit;
        if (prompt < 0 || output < 0 || hit < 0 || miss < 0 || hit + miss != prompt) {
            throw new IllegalArgumentException("inconsistent token usage");
        }
        return hitPrice.multiply(BigDecimal.valueOf(hit)).add(missPrice.multiply(BigDecimal.valueOf(miss)))
                .add(outputPrice.multiply(BigDecimal.valueOf(output))).movePointLeft(6);
    }

    @Override
    public synchronized String respond(List<Map<String, String>> messages) {
        if (remaining <= 0) {
            throw new IllegalArgumentException("DeepSeek call budget exhausted");
        }
        JsonObject packet = JsonParser.parseString(messages.get(messages.size() - 1).get("content")).getAsJsonObject();
        long timeout = Math.min(60_000, packet.getAsJsonObject("remaining_budget").get("time_ms").getAsLong());
        if (timeout < 1) {
            throw new IllegalArgumentException("DeepSeek time budget exhausted");
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", false);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", 0);
        body.add("thinking", new Gson().toJsonTree(Map.of("type", "disabled")));
        body.add("response_format", new Gson().toJsonTree(Map.of("type", "json_object")));
        body.add("messages", new Gson().toJsonTree(messages));
        body.addProperty("_timeout_ms", timeout);
        JsonObject event = new JsonObject();
        event.addProperty("requested_model", model);
        event.addProperty("request_id", packet.get("request_id").getAsString());
        event.addProperty("started_at", Instant.now().toString());
        event.addProperty("price_hit_usd_per_m", hitPrice);
        event.addProperty("price_miss_usd_per_m", missPrice);
        event.addProperty("price_output_usd_per_m", outputPrice);
        event.add("estimated_usd", JsonNull.INSTANCE);
        audit.add(event);
        remaining--;
        long started = System.nanoTime();
        try {
            TiDBDeepSeekAdvisor.Reply reply = transport.send(body.toString());
            event.addProperty("http_status", reply.status);
            if (reply.status != 200) {
                throw new IllegalArgumentException("DeepSeek HTTP " + reply.status);
            }
            JsonObject envelope = JsonParser.parseString(reply.body).getAsJsonObject();
            if (envelope.has("usage") && !envelope.get("usage").isJsonNull()) {
                JsonObject usage = envelope.getAsJsonObject("usage");
                event.add("usage", usage.deepCopy());
                event.addProperty("estimated_usd", cost(usage, hitPrice, missPrice, outputPrice));
            }
            event.add("response_id", envelope.get("id"));
            event.add("returned_model", envelope.get("model"));
            JsonObject choice = envelope.getAsJsonArray("choices").get(0).getAsJsonObject();
            event.add("finish_reason", choice.get("finish_reason"));
            if (!"stop".equals(choice.get("finish_reason").getAsString())) {
                throw new IllegalArgumentException("incomplete DeepSeek response");
            }
            String content = choice.getAsJsonObject("message").get("content").getAsString();
            if (content.isBlank() || content.length() > 32_000) {
                throw new IllegalArgumentException("empty or oversized DeepSeek response");
            }
            event.addProperty("status", "RECEIVED");
            return content;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            event.addProperty("status", "INTERRUPTED");
            throw new IllegalArgumentException("DeepSeek call interrupted");
        } catch (IOException | RuntimeException error) {
            event.addProperty("status", "FAILED");
            // Do not persist transport exception messages or HTTP bodies, which can contain credentials.
            event.addProperty("error_class", error.getClass().getSimpleName());
            throw new IllegalArgumentException("DeepSeek call failed; inspect redacted API audit");
        } finally {
            event.addProperty("elapsed_ms", (System.nanoTime() - started) / 1_000_000);
        }
    }
}
