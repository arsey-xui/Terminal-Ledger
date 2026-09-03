package com.personal.accounting.android.sync;

import com.personal.accounting.android.model.Account;
import com.personal.accounting.android.model.Budget;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.model.Template;
import com.personal.accounting.android.model.Transaction;

import java.util.ArrayList;
import java.util.List;

/** 与后端同步接口对应的传输对象。 */
public final class SyncPayloads {

    private SyncPayloads() {
    }

    public static class PushRequest {
        public String deviceId;
        public List<Transaction> transactions = new ArrayList<>();
        public List<Category> categories = new ArrayList<>();
        public List<Account> accounts = new ArrayList<>();
        public List<Budget> budgets = new ArrayList<>();
        public List<Template> templates = new ArrayList<>();
    }

    public static class PullRequest {
        public String deviceId;
        public Long since = 0L;
        public Long until;
        public String cursor;
    }

    public static class PullResponse {
        public long serverTime;
        public boolean hasMore;
        public String nextCursor;
        public List<Transaction> transactions = new ArrayList<>();
        public List<Category> categories = new ArrayList<>();
        public List<Account> accounts = new ArrayList<>();
        public List<Budget> budgets = new ArrayList<>();
        public List<Template> templates = new ArrayList<>();
    }

    public static class SyncResult {
        public int upserted;
        public int skipped;
        public long pushedAt;
    }
}
