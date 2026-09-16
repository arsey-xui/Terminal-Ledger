package com.personal.accounting.controller;

import com.personal.accounting.dto.ApiResponse;
import com.personal.accounting.service.VersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 版本信息接口：供手机端「检查更新」使用。
 *
 * <p>只返回版本号与下载地址，客户端自行比较版本、决定是否提示升级。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class VersionController {

    private final VersionService versionService;

    @GetMapping("/version")
    public ApiResponse<Map<String, Object>> version() {
        // 优先用 GitHub Releases 自动获取的版本（发版后无需改配置），失败自动回退本地配置
        VersionService.Info info = versionService.current();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("latestVersion", info.latestVersion());
        data.put("minSupportedVersion", info.minSupportedVersion());
        data.put("downloadUrl", info.downloadUrl());
        data.put("notes", info.notes());
        data.put("releasedAt", info.releasedAt());
        // 便于排查：版本信息来自 GitHub 还是本地配置
        data.put("source", info.source());
        return ApiResponse.ok(data);
    }
}
