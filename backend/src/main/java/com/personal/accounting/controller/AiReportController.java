package com.personal.accounting.controller;

import com.personal.accounting.dto.ApiResponse;
import com.personal.accounting.dto.report.AiReportRequest;
import com.personal.accounting.dto.report.AiReportResponse;
import com.personal.accounting.service.AiReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/report")
@RequiredArgsConstructor
public class AiReportController {

    private final AiReportService aiReportService;

    @PostMapping("/ai")
    public ApiResponse<AiReportResponse> ai(@RequestBody AiReportRequest request) {
        return ApiResponse.ok(aiReportService.generate(request));
    }
}
