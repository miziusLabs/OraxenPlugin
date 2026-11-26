package io.th0rgal.oraxen.studio;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * HTTP client for the Oraxen Studio server-sync API v1 ({@code /api/v1/studio}).
 * <p>
 * Workspace files never pass through Studio's API: downloads and uploads go straight
 * to storage through short-lived signed URLs returned by {@link #downloadUrls} and
 * {@link #beginPush}. Errors are reported as {@link StudioApiException} with Studio's
 * stable error code.
 */
public final class StudioApiClient implements AutoCloseable {

    public static final int API_VERSION = 1;
    private static final Duration API_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofMinutes(5);
    private static final Gson GSON = new com.google.gson.GsonBuilder().serializeNulls().create();

    private final String baseUrl;
    private final HttpClient http;

    public StudioApiClient(String baseUrl) {
        this.baseUrl = StudioBundleUtils.normalizeStudioUrl(baseUrl);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public void close() {
        http.close();
    }

    /** Thrown for any non-2xx answer; {@link #code} is Studio's machine-readable error code. */
    public static final class StudioApiException extends IOException {
        private final int status;
        private final String code;
        private final JsonObject details;
        private final String requestId;

        StudioApiException(HttpResponse<?> response, String code, String message, JsonObject details) {
            super(message);
            this.status = response.statusCode();
            this.code = code;
            this.details = details;
            this.requestId = response.headers().firstValue("X-Request-ID").orElse("");
        }

        public int status() {
            return status;
        }

        public String code() {
            return code;
        }

        public JsonObject details() {
            return details;
        }

        String debugSummary() {
            String summary = "HTTP " + status;
            if (isErrorIdentifier(code)) summary += ", error code " + code;
            String storageCode = stringField(details, "storage_code");
            if ("upload_failed".equals(code) && isErrorIdentifier(storageCode)) {
                summary += ", storage error code " + storageCode;
            }
            if (requestId.matches("[a-zA-Z0-9_.-]{1,128}")) summary += ", request ID " + requestId;
            return summary;
        }

        String debugSummary(Set<String> submittedPaths) {
            String summary = debugSummary();
            if (!"invalid_paths".equals(code) && !"uploads_incomplete".equals(code)) return summary;
            JsonElement paths = details.get("paths");
            if (paths == null || !paths.isJsonArray()) return summary;

            Set<String> rejected = new LinkedHashSet<>();
            for (JsonElement item : paths.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) continue;
                String path = item.getAsString();
                // Only echo paths already sent by this server, not arbitrary response strings.
                if (submittedPaths.contains(path) && StudioBundleUtils.isLoggablePath(path)) rejected.add(path);
                if (rejected.size() > 10) break;
            }
            if (rejected.isEmpty()) return summary;
            return summary + ", affected paths " + String.join(", ", rejected.stream().limit(10).toList())
                    + (rejected.size() > 10 ? ", ..." : "");
        }
    }

    public record FileEntry(String sha256, long size) {
    }

    public record Pairing(String code, String pollToken, String connectUrl, int pollIntervalSeconds) {
    }

    public record PairingResult(String token, String workspaceId, String workspaceName) {
    }

    public record Connection(String workspaceId, String workspaceName, String serverName, int rev,
                             @Nullable String pushRequestedAt, String realtimeUrl, String channel,
                             @Nullable String storageApiKey) {
    }

    public record Manifest(int rev, Map<String, FileEntry> files) {
    }

    public record Upload(String path, String url) {
    }

    public record PushPlan(String pushId, java.util.List<Upload> uploads, java.util.List<String> deletes, int unchanged) {
    }

    public record PushResult(int rev, int uploaded, int deleted) {
    }

    /** Versions Studio supports, from the unversioned discovery endpoint. */
    public java.util.List<Integer> supportedVersions() throws IOException, InterruptedException {
        JsonObject meta = send(get("/api/studio/meta", null));
        java.util.List<Integer> versions = new java.util.ArrayList<>();
        for (JsonElement v : meta.getAsJsonArray("api_versions")) versions.add(v.getAsInt());
        return versions;
    }

    public Pairing startPairing(String serverName, String oraxenVersion) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("server_name", serverName);
        body.addProperty("oraxen_version", oraxenVersion);
        JsonObject res = send(post("/api/v1/studio/pairings", null, body));
        return new Pairing(res.get("code").getAsString(), res.get("poll_token").getAsString(),
                res.get("connect_url").getAsString(),
                res.has("poll_interval") ? res.get("poll_interval").getAsInt() : 2);
    }

    /** Returns null while the pairing is pending approval. */
    @Nullable
    public PairingResult pollPairing(Pairing pairing) throws IOException, InterruptedException {
        JsonObject res = send(get("/api/v1/studio/pairings/" + pairing.code(), pairing.pollToken()));
        if (!"approved".equals(string(res, "status"))) return null;
        return new PairingResult(res.get("token").getAsString(), string(res, "workspace_id"),
                string(res, "workspace_name"));
    }

    public Connection connection(String token) throws IOException, InterruptedException {
        JsonObject res = send(get("/api/v1/studio/connection", token));
        JsonObject realtime = res.getAsJsonObject("realtime");
        JsonObject storage = res.has("storage") ? res.getAsJsonObject("storage") : null;
        return new Connection(string(res, "workspace_id"), string(res, "workspace_name"),
                string(res, "server_name"), res.get("rev").getAsInt(),
                res.get("push_requested_at").isJsonNull() ? null : res.get("push_requested_at").getAsString(),
                string(realtime, "url"), string(realtime, "channel"),
                storage == null ? null : string(storage, "apikey"));
    }

    public void disconnect(String token) throws IOException, InterruptedException {
        send(HttpRequest.newBuilder(uri("/api/v1/studio/connection")).timeout(API_TIMEOUT)
                .header("Authorization", "Bearer " + token).DELETE().build());
    }

    public Manifest manifest(String token) throws IOException, InterruptedException {
        JsonObject res = send(get("/api/v1/studio/manifest", token));
        return new Manifest(res.get("rev").getAsInt(), parseFiles(res.getAsJsonObject("files")));
    }

    public Map<String, String> downloadUrls(String token, Collection<String> paths) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        JsonArray array = new JsonArray();
        paths.forEach(array::add);
        body.add("paths", array);
        JsonObject urls = send(post("/api/v1/studio/downloads", token, body)).getAsJsonObject("urls");
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : urls.entrySet()) out.put(e.getKey(), e.getValue().getAsString());
        return out;
    }

    public PushPlan beginPush(String token, Map<String, FileEntry> files, @Nullable Map<String, String> base,
                              boolean force) throws IOException, InterruptedException {
        JsonObject body = new JsonObject();
        body.add("files", GSON.toJsonTree(files));
        body.add("base", base == null ? null : GSON.toJsonTree(base));
        body.addProperty("force", force);
        JsonObject res = send(post("/api/v1/studio/pushes", token, body));
        java.util.List<Upload> uploads = new java.util.ArrayList<>();
        for (JsonElement e : res.getAsJsonArray("uploads")) {
            JsonObject o = e.getAsJsonObject();
            uploads.add(new Upload(string(o, "path"), string(o, "url")));
        }
        java.util.List<String> deletes = new java.util.ArrayList<>();
        for (JsonElement e : res.getAsJsonArray("deletes")) deletes.add(e.getAsString());
        return new PushPlan(string(res, "push_id"), uploads, deletes, res.get("unchanged").getAsInt());
    }

    /** Releases a push that will not be committed, so the workspace is not locked until it expires. */
    public void abortPush(String token, String pushId) throws IOException, InterruptedException {
        send(HttpRequest.newBuilder(uri("/api/v1/studio/pushes/" + pushId)).timeout(API_TIMEOUT)
                .header("Authorization", "Bearer " + token).DELETE().build());
    }

    public PushResult commitPush(String token, String pushId) throws IOException, InterruptedException {
        JsonObject res = send(post("/api/v1/studio/pushes/" + pushId + "/commit", token, new JsonObject()));
        return new PushResult(res.get("rev").getAsInt(), res.get("uploaded").getAsInt(), res.get("deleted").getAsInt());
    }

    /** Downloads a signed URL to {@code target}. */
    public void download(String signedUrl, Path target) throws IOException, InterruptedException {
        HttpResponse<Path> res = http.send(HttpRequest.newBuilder(URI.create(signedUrl)).timeout(TRANSFER_TIMEOUT)
                .GET().build(), HttpResponse.BodyHandlers.ofFile(target));
        if (res.statusCode() / 100 != 2) {
            throw new StudioApiException(res, "download_failed",
                    "Download failed with HTTP " + res.statusCode(), new JsonObject());
        }
    }

    /** Uploads {@code file} to a signed upload URL returned by {@link #beginPush}. */
    public void upload(String signedUrl, Path file, @Nullable String storageApiKey) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(signedUrl)).timeout(TRANSFER_TIMEOUT)
                .header("Content-Type", "application/octet-stream")
                .header("x-upsert", "true")
                .PUT(HttpRequest.BodyPublishers.ofFile(file));
        if (storageApiKey != null && !storageApiKey.isEmpty()) req.header("apikey", storageApiKey);
        HttpResponse<String> res = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() / 100 != 2) {
            JsonObject details = new JsonObject();
            JsonObject error = parseObject(res.body());
            String storageCode = stringField(error, "code");
            if (!isErrorIdentifier(storageCode)) storageCode = stringField(error, "error");
            if (isErrorIdentifier(storageCode)) details.addProperty("storage_code", storageCode);
            throw new StudioApiException(res, "upload_failed",
                    "Upload failed with HTTP " + res.statusCode(), details);
        }
    }

    private static Map<String, FileEntry> parseFiles(JsonObject files) {
        Map<String, FileEntry> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : files.entrySet()) {
            JsonObject f = e.getValue().getAsJsonObject();
            out.put(e.getKey(), new FileEntry(f.get("sha256").getAsString(), f.get("size").getAsLong()));
        }
        return out;
    }

    private static String string(JsonObject obj, String key) {
        JsonElement e = obj.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private HttpRequest get(String path, @Nullable String bearer) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).timeout(API_TIMEOUT).GET();
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return b.build();
    }

    private HttpRequest post(String path, @Nullable String bearer, JsonObject body) {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).timeout(API_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        return b.build();
    }

    private JsonObject send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> res = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonObject json = parseObject(res.body());
        if (res.statusCode() / 100 == 2) return json;
        JsonObject error = json.has("error") && json.get("error").isJsonObject()
                ? json.getAsJsonObject("error") : new JsonObject();
        String code = stringField(error, "code");
        if (code.isEmpty()) code = "http_" + res.statusCode();
        String message = stringField(error, "message");
        if (message.isEmpty()) message = "Oraxen Studio returned HTTP " + res.statusCode();
        throw new StudioApiException(res, code, message, error);
    }

    private static JsonObject parseObject(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
        } catch (JsonParseException | IllegalStateException ignored) {
        }
        return new JsonObject();
    }

    private static String stringField(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                ? value.getAsString() : "";
    }

    private static boolean isErrorIdentifier(String value) {
        return value.matches("[a-zA-Z0-9_-]{1,128}");
    }
}
