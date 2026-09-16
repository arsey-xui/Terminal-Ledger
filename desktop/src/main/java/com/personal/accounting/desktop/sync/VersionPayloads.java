package com.personal.accounting.desktop.sync;

/** GET /api/version 的响应体（与手机端 VersionPayloads 对应）。 */
public final class VersionPayloads {

    private VersionPayloads() {
    }

    public static class Response {
        public String latestVersion;
        public String minSupportedVersion;
        public String downloadUrl;
        public String notes;
        public long releasedAt;
    }
}
