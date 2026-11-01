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
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // 共享密钥统一在这里加：为空（后端没开校验）就原样放行。
            // 这里的请求全部指向自家后端，不会带到外部站点上。
            .addInterceptor(chain -> {
                Request original = chain.request();
                String token = SyncConfig.token();
                if (token.isEmpty()) return chain.proceed(original);
                return chain.proceed(original.newBuilder()
                        .header("X-Sync-Token", token)
                        .build());
            })
            .build();

    public <T> T post(String path, Object body, Class<T> cls) throws IOException {
        Request req = new Request.Builder()
                .url(SyncConfig.BASE_URL + path)
                .post(RequestBody.create(Json.MAPPER.writeValueAsString(body), JSON))
                .build();
        return execute(req, cls);
    }

    /** GET 请求（例如查版本号），同样解包后端统一响应里的 data。 */
    public <T> T get(String path, Class<T> cls) throws IOException {
        Request req = new Request.Builder()
                .url(SyncConfig.BASE_URL + path)
                .get()
                .build();
        return execute(req, cls);
    }

    private <T> T execute(Request req, Class<T> cls) throws IOException {
        try (Response resp = CLIENT.newCall(req).execute()) {
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
