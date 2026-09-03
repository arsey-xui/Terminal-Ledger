package com.personal.accounting.controller;

import com.personal.accounting.dto.ApiResponse;
import com.personal.accounting.dto.sync.SyncPullRequest;
import com.personal.accounting.dto.sync.SyncPullResponse;
import com.personal.accounting.dto.sync.SyncPushRequest;
import com.personal.accounting.dto.sync.SyncResult;
import com.personal.accounting.service.SyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
public class SyncController {

    private final SyncService syncService;

    @PostMapping("/push")
    public ApiResponse<SyncResult> push(@RequestBody SyncPushRequest request) {
        return ApiResponse.ok(syncService.push(request), "合并完成");
    }

    @PostMapping("/pull")
    public ApiResponse<SyncPullResponse> pull(@RequestBody SyncPullRequest request) {
        return ApiResponse.ok(syncService.pull(request));
    }

    @PostMapping("/pull-window")
    public ApiResponse<SyncPullResponse> pullWindow(@RequestBody SyncPullRequest request) {
        return ApiResponse.ok(syncService.pullWindow(request));
    }
}
