package com.personal.accounting.android.sync;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** 封装 OkHttp + Gson 的 REST 客户端。 */
public class ApiClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private final Gson gson = GsonAdapters.build();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build();

    public <T> T post(String path, Object body, Class<T> responseClass) throws IOException {
        String url = SyncConfig.BASE_URL + path;
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(gson.toJson(body), JSON))
                .build();
        try (Response resp = client.newCall(request).execute()) {
            String bodyStr = resp.body().string();
            if (!resp.isSuccessful()) {
                throw new IOException("HTTP " + resp.code() + ": " + bodyStr);
            }
            // 后端统一响应为 {success,message,data:{...}}，需要解包 data 再反序列化。
            JsonElement root = gson.fromJson(bodyStr, JsonElement.class);
            if (root != null && root.isJsonObject()) {
                JsonObject obj = root.getAsJsonObject();
                if (obj.has("data") && !obj.get("data").isJsonNull()) {
                    return gson.fromJson(obj.get("data"), responseClass);
                }
            }
            return gson.fromJson(bodyStr, responseClass);
        }
    }
}
