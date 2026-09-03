package com.personal.accounting.desktop.sync;

import com.fasterxml.jackson.databind.JsonNode;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ApiClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    public <T> T post(String path, Object body, Class<T> cls) throws IOException {
        Request req = new Request.Builder()
                .url(SyncConfig.BASE_URL + path)
                .post(RequestBody.create(Json.MAPPER.writeValueAsString(body), JSON))
                .build();
        try (Response resp = client.newCall(req).execute()) {
            String s = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + s);
            }
            // 后端统一响应为 {success,message,data:{...}}，需要解包 data 再反序列化。
            JsonNode root = Json.MAPPER.readTree(s);
            JsonNode data = root.path("data");
            if (!data.isMissingNode() && !data.isNull()) {
                return Json.MAPPER.treeToValue(data, cls);
            }
            return Json.MAPPER.readValue(s, cls);
        }
    }
}
