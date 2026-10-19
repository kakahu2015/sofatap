package com.chekusu.keytrans;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.regex.Pattern;

/** HTTPS client for OpenAI-compatible chat-completions and responses endpoints. */
public final class TranslationClient {
    private static final int MAX_RESPONSE_BYTES = 512 * 1024;
    private static final Pattern THINK_BLOCK = Pattern.compile("<think>.*?</think>", Pattern.DOTALL);

    private TranslationClient() { }

    public static final class Cancellation {
        private volatile boolean cancelled;
        private volatile HttpURLConnection connection;

        public void cancel() {
            cancelled = true;
            HttpURLConnection active = connection;
            if (active != null) active.disconnect();
        }

        private void attach(HttpURLConnection active) {
            connection = active;
            if (cancelled) active.disconnect();
        }

        private boolean isCancelled() { return cancelled || Thread.currentThread().isInterrupted(); }
    }

    public static URL endpoint(String configuredUrl) throws IOException {
        String raw = configuredUrl == null ? "" : configuredUrl.trim();
        if (raw.isEmpty()) throw new IOException("Enter an API endpoint");
        if (!raw.contains("://")) raw = "https://" + raw;
        try {
            URI input = new URI(raw);
            if (!"https".equalsIgnoreCase(input.getScheme()) || input.getHost() == null || input.getUserInfo() != null) {
                throw new IOException("Only valid HTTPS endpoints are supported");
            }
            String path = input.getPath() == null ? "" : input.getPath().replaceAll("/+$", "");
            String lowerPath = path.toLowerCase(Locale.ROOT);
            if (!lowerPath.endsWith("/chat/completions") && !lowerPath.endsWith("/responses")) {
                path += "/chat/completions";
            }
            URI endpoint = new URI("https", null, input.getHost(), input.getPort(), path,
                input.getQuery(), null);
            return endpoint.toURL();
        } catch (URISyntaxException e) {
            throw new IOException("Invalid HTTPS endpoint");
        }
    }

    public static String translate(String endpointValue, String model, String apiKey,
                                   String sourceLanguage, String targetLanguage, String text,
                                   Cancellation cancellation)
            throws IOException {
        if (cancellation != null && cancellation.isCancelled()) throw new CancellationException();
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IOException("API key is missing");
        if (model == null || model.trim().isEmpty()) throw new IOException("Model is missing");
        URL endpoint = endpoint(endpointValue);
        boolean responses = endpoint.getPath().toLowerCase(Locale.ROOT).contains("/responses");
        boolean azure = endpoint.getHost().toLowerCase(Locale.ROOT).contains("openai.azure.com");
        String prompt = "Translate the following text from " + sourceLanguage + " to " + targetLanguage + ".\n"
            + "Preserve the original meaning, intent, and tone. Make the result sound natural in " + targetLanguage
            + ", but do not add, omit, or embellish information. Return only the translation.\n\nText:\n" + text;

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) endpoint.openConnection();
            if (cancellation != null) cancellation.attach(connection);
            if (cancellation != null && cancellation.isCancelled()) throw new CancellationException();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (azure) connection.setRequestProperty("api-key", apiKey.trim());
            else connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

            JSONObject body = new JSONObject();
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "user").put("content", prompt));
            body.put("model", model.trim());
            if (responses) {
                body.put("input", messages);
                body.put(azure ? "max_tokens" : "max_output_tokens", 2000);
            } else {
                body.put("messages", messages);
                body.put("temperature", 0.3);
                body.put("max_tokens", 2000);
            }

            byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(request.length);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                if (cancellation != null && cancellation.isCancelled()) throw new CancellationException();
                output.write(request);
            }

            if (cancellation != null && cancellation.isCancelled()) throw new CancellationException();
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String response = stream == null ? "" : readLimited(stream);
            if (cancellation != null && cancellation.isCancelled()) throw new CancellationException();
            if (status < 200 || status >= 300) throw new IOException("Provider returned HTTP " + status);
            return parseResult(response);
        } catch (JSONException e) {
            throw new IOException("Could not encode translation request");
        } finally {
            if (connection != null) {
                if (cancellation != null) cancellation.connection = null;
                connection.disconnect();
            }
        }
    }

    private static String readLimited(InputStream input) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = source.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IOException("Provider response is too large");
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8);
        }
    }

    private static String parseResult(String response) throws IOException {
        try {
            JSONObject json = new JSONObject(response);
            String result = null;
            JSONArray choices = json.optJSONArray("choices");
            if (choices != null && choices.length() > 0) {
                JSONObject message = choices.optJSONObject(0) == null ? null : choices.optJSONObject(0).optJSONObject("message");
                if (message != null) result = message.optString("content", null);
            }
            if (result == null) result = responsesText(json);
            if (result == null || result.trim().isEmpty()) throw new IOException("Provider returned no translation");
            return THINK_BLOCK.matcher(result).replaceAll("").trim();
        } catch (JSONException e) {
            throw new IOException("Provider returned an invalid response");
        }
    }

    private static String responsesText(JSONObject json) {
        String direct = json.optString("output_text", null);
        if (direct != null && !direct.isEmpty()) return direct;
        JSONArray output = json.optJSONArray("output");
        if (output == null) return null;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject message = output.optJSONObject(i);
            if (message == null || !"message".equals(message.optString("type"))) continue;
            JSONArray content = message.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part != null && "output_text".equals(part.optString("type"))) text.append(part.optString("text", ""));
            }
        }
        return text.length() == 0 ? null : text.toString();
    }
}
