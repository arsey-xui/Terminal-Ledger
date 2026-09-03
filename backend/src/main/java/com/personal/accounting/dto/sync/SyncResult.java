package com.personal.accounting.dto.sync;

import lombok.Data;

/** 一次 push 的结果统计。 */
@Data
public class SyncResult {
    private int upserted;
    private int skipped;
    private long pushedAt;
}
