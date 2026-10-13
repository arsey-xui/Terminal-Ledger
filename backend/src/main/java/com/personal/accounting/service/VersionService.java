package com.personal.accounting.service;

import com.personal.accounting.config.VersionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 版本信息：优先从 **GitHub Releases API** 自动获取（tag 名当版本号、release 正文当更新说明、
 * 按资产名匹配生成下载地址）；查不到时回退到 application.yml 里的手工配置。
 *
 * <p>这样"发版"只需要在 GitHub 上创建一个 Release 并上传产物，不用改任何配置。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VersionService {

    private final VersionProperties props;
    private final RestClient restClient = RestClient.create();

    private volatile Cached cached;

    /** 版本信息快照，source 说明来源（github / config）。 */
    public record Info(String latestVersion, String minSupportedVersion, String downloadUrl,
                       String notes, long releasedAt, String source) {
    }

    private record Cached(Info info, long fetchedAt) {
    }

    public Info current() {
        Info fromConfig = fromConfig();
        if (isBlank(props.getGithubRepo())) return fromConfig;

        Cached snapshot = cached;
        long ttlMillis = Math.max(0, props.getCacheSeconds()) * 1000L;
        if (snapshot != null && System.currentTimeMillis() - snapshot.fetchedAt() < ttlMillis) {
            return snapshot.info();
        }
        synchronized (this) {
            snapshot = cached; // re-check
            if (snapshot != null && System.currentTimeMillis() - snapshot.fetchedAt() < ttlMillis) {
                return snapshot.info();
            }
            Info fromGithub = fetchFromGithub();
            Info result = fromGithub != null ? fromGithub : fromConfig;
            cached = new Cached(result, System.currentTimeMillis());
            return result;
        }
    }

    private Info fromConfig() {
        return new Info(props.getLatest(), props.getMinSupported(), props.getDownloadUrl(),
                props.getNotes(), props.getReleasedAt(), "config");
    }

    /** 查询 GitHub 最新 release（含预发布？不含：只取正式 release）。 */
    @SuppressWarnings("unchecked")
    private Info fetchFromGithub() {
        String repo = props.getGithubRepo().trim();
        String url = "https://api.github.com/repos/" + repo + "/releases/latest";
        try {
            RestClient.RequestHeadersSpec<?> request = restClient.get().uri(url)
                    .header(HttpHeaders.ACCEPT, "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28");
            String token = props.getGithubToken();
            if (!isBlank(token)) request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim());

            Map<String, Object> body = request.retrieve().body(Map.class);
            if (body == null) return null;

            String tag = str(body.get("tag_name"));
            String version = normalizeVersion(tag);
            if (isBlank(version)) {
                log.warn("[version] GitHub release 缺少可用 tag_name: {}", tag);
                return null;
            }
            String notes = str(body.get("body"));
            long releasedAt = parseInstant(str(body.get("published_at")));

            String apkUrl = null;
            String desktopUrl = null;
            Object assets = body.get("assets");
            if (assets instanceof List<?> list) {
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> asset)) continue;
                    String name = str(asset.get("name"));
                    String downloadUrl = str(asset.get("browser_download_url"));
                    if (name == null || downloadUrl == null) continue;
                    String lower = name.toLowerCase();
                    if (lower.endsWith(".apk")
                            && apkUrl == null
                            && lower.contains(props.getApkAssetPattern().toLowerCase())) {
                        apkUrl = downloadUrl;
                    } else if (lower.endsWith(".zip")
                            && desktopUrl == null
                            && lower.contains(props.getDesktopAssetPattern().toLowerCase())) {
                        desktopUrl = downloadUrl;
                    }
                }
            }
            // 客户端自己按平台挑地址；这里优先给 apk（手机端下载安装用），桌面端也能用同一条
            String downloadUrl = apkUrl != null ? apkUrl : desktopUrl;
            log.info("[version] 从 GitHub 获取最新版本 {} (apk={}, desktop={})", version, apkUrl != null, desktopUrl != null);
            return new Info(version, props.getMinSupported(), downloadUrl, notes, releasedAt, "github");
        } catch (Exception e) {
            log.warn("[version] 查询 GitHub 失败，回退到本地配置：{}", e.getMessage());
            return null;
        }
    }

    /** tag 可能是 v1.0.2 / 1.0.2 / release-1.0.2，统一取出数字版本号。 */
    private static String normalizeVersion(String tag) {
        if (tag == null) return null;
        var m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)*)").matcher(tag.trim());
        return m.find() ? m.group(1) : null;
    }

    private static long parseInstant(String iso) {
        if (isBlank(iso)) return 0L;
        try {
            return Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
