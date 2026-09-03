package com.personal.accounting.dto.sync;

import lombok.Data;

@Data
public class SyncPullRequest {
    private String deviceId;
    /** 上次同步水位（epoch 毫秒）；首次为 0 */
    private Long since = 0L;
    /** 可选上界（epoch 毫秒），用于按窗口回补历史数据；null 表示不限 */
    private Long until;
    /** 从匹配游标开始分页拉取（用于加载更早数据） */
    private String cursor;
}
