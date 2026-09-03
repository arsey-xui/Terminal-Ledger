package com.personal.accounting.service;

import com.personal.accounting.dto.sync.SyncPullRequest;
import com.personal.accounting.dto.sync.SyncPullResponse;
import com.personal.accounting.dto.sync.SyncPushRequest;
import com.personal.accounting.dto.sync.SyncResult;

/**
 * 数据同步核心接口。
 *
 * <p>协议采用「客户端 push + 客户端 pull」的双向增量同步：</p>
 * <ul>
 *   <li>push：客户端把本地 last_modified 晚于上次水位的变更推上来；</li>
 *   <li>pull：客户端带上次同步水位 since，拉取云端 last_modified 大于 since 的变更；</li>
 *   <li>冲突：同一 clientId 记录，比较 last_modified，较大者获胜（LWW）。</li>
 * </ul>
 *
 * <p>删除以软删除墓碑（deleted=true）表达，保证删除也能被正确同步。</p>
 */
public interface SyncService {

    /** 将客户端变更合并进云端（LWW）。 */
    SyncResult push(SyncPushRequest request);

    /** 增量拉取：返回云端 last_modified 大于 since 的变更，按 (last_modified, id) 游标分页。 */
    SyncPullResponse pull(SyncPullRequest request);

    /** 窗口回补：用于首次同步只拉最近 N 个月后，再按需分页回补更早历史。 */
    SyncPullResponse pullWindow(SyncPullRequest request);
}
