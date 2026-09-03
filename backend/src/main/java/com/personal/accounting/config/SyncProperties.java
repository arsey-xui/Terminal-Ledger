package com.personal.accounting.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 同步相关配置（对应 application.yml 的 {@code accounting.sync}）。 */
@Data
@Configuration
@ConfigurationProperties(prefix = "accounting.sync")
public class SyncProperties {
    /** 首次/增量拉取的时间窗口（月）；null 表示不限。 */
    private Integer pullWindowMonths = 3;

    /** 单次分页大小。 */
    private Integer pullPageSize = 500;
}
