package com.personal.accounting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 版本发布信息（对应 application.yml 的 {@code accounting.version}）。
 *
 * <p>发布新版本时改这里（或用环境变量 APP_LATEST_VERSION / APP_DOWNLOAD_URL 覆盖），
 * 客户端「检查更新」就会看到新版本。</p>
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "accounting.version")
public class VersionProperties {
    /** 最新版本号，如 1.0.2。 */
    private String latest = "1.0.1";

    /** 低于该版本的客户端建议强制升级（当前仅展示提示）。 */
    private String minSupported = "1.0.0";

    /** 安装包下载地址（放在 outputs 里发布到 GitHub Releases / 自有静态地址后填入）。 */
    private String downloadUrl = "";

    /** 更新说明，可多行。 */
    private String notes = "";

    /** 该版本发布时间（毫秒时间戳），0 表示未设置。 */
    private long releasedAt = 0L;

    // ---------- GitHub Releases 自动获取（推荐：发版后无需改任何配置） ----------

    /** 仓库，形如 your-name/Terminal-Ledger；留空则不启用 GitHub 查询。 */
    private String githubRepo = "";

    /** 私有仓库或想提高速率限制时可填 GitHub Token（公开仓库留空即可）。 */
    private String githubToken = "";

    /** 资产名匹配片段：按文件名里的架构片段生成下载地址（`*{片段}*.apk`）。 */
    private String apkAssetPattern = "arm64-v8a";

    /** 资产名匹配模式：桌面端 zip（`*{模式}*.zip`）。 */
    private String desktopAssetPattern = "Desktop";

    /** GitHub 结果缓存秒数，避免每次客户端检查都打 GitHub 接口（未鉴权限 60 次/小时）。 */
    private long cacheSeconds = 300L;
}
