package com.personal.accounting.service;

import com.personal.accounting.dto.report.AiReportRequest;
import com.personal.accounting.dto.report.AiReportResponse;

public interface AiReportService {
    AiReportResponse generate(AiReportRequest request);
}
