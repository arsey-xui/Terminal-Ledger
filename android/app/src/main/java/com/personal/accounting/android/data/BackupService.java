package com.personal.accounting.android.data;

import android.content.Context;

import com.google.gson.Gson;
import com.personal.accounting.android.model.Account;
import com.personal.accounting.android.model.Budget;
import com.personal.accounting.android.model.Category;
import com.personal.accounting.android.model.RecurringRule;
import com.personal.accounting.android.model.Template;
import com.personal.accounting.android.model.Transaction;
import com.personal.accounting.android.sync.GsonAdapters;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 备份与恢复。
 *
 * <p><b>格式与电脑端 {@code BackupService} 完全一致，两边可以互相恢复</b>：
 * 明文是一个只含 {@code backup.json} 的 zip，再对整个 zip 做 AES-256-GCM 加密，
 * 文件结构 = {@code salt(16) + iv(12) + 密文(含 GCM tag)}；密钥用 PBKDF2WithHmacSHA256
 * 从口令派生（65536 轮、256 位）。载荷里的字段名也与电脑端一致，日期都是 ISO 字符串。</p>
 *
 * <p>这里刻意不复用同步用的「水位」概念：导出取全量（含软删除墓碑），
 * 这样恢复之后删除状态也是对的。</p>
 */
public final class BackupService {

    /** 与电脑端 BackupPayload 对应；字段名一致才能互相恢复。 */
    static final class Payload {
        List<Transaction> transactions;
        List<Category> categories;
        List<Account> accounts;
        List<Budget> budgets;
        List<Template> templates;
        /** 电脑端没有这个字段，但它关闭了 FAIL_ON_UNKNOWN_PROPERTIES，会安全忽略。 */
        List<RecurringRule> recurringRules;
        long exportedAt;
    }

    /** 导入结果统计，用于给用户一句实话。 */
    public static final class RestoreResult {
        public int transactions;
        public int categories;
        public int accounts;
        public int budgets;
        public int templates;
        public int recurringRules;

        public String summary() {
            return "流水 " + transactions + " 条 · 分类 " + categories + " 个 · 账户 " + accounts
                    + " 个 · 预算 " + budgets + " 条 · 模板 " + templates + " 条 · 周期规则 "
                    + recurringRules + " 条";
        }
    }

    /** 口令不对/文件损坏：GCM 校验失败时抛这个，好让界面说人话。 */
    public static class WrongPasswordException extends Exception {
        WrongPasswordException() {
            super("口令不对，或者这个文件不是本应用导出的备份");
        }
    }

    private static final Gson GSON = GsonAdapters.build();

    private BackupService() {
    }

    // ---------- CSV（列与电脑端一致） ----------

    public static void exportCsv(OutputStream out, List<Transaction> txns) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("日期,类型,金额,分类,账户,备注,来源,clientId,lastModified\n");
        for (Transaction t : txns) {
            sb.append(esc(t.date == null ? "" : t.date.toString())).append(',')
                    .append(esc(t.type)).append(',')
                    .append(t.amount).append(',')
                    .append(esc(t.categoryClientId)).append(',')
                    .append(esc(t.accountClientId)).append(',')
                    .append(esc(t.note)).append(',')
                    .append(esc(t.source)).append(',')
                    .append(esc(t.clientId)).append(',')
                    .append(t.lastModified).append('\n');
        }
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String esc(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    // ---------- 导出 ----------

    /** 写出一份加密备份包；全量导出，含软删除墓碑。 */
    public static void writeBackup(Context context, OutputStream out, char[] password) throws Exception {
        AppData data = AppData.read(context);

        Payload payload = new Payload();
        payload.transactions = data.transactions;
        payload.categories = data.categories;
        payload.accounts = data.accounts;
        payload.budgets = data.budgets;
        payload.templates = data.templates;
        payload.recurringRules = data.recurringRules;
        payload.exportedAt = System.currentTimeMillis();

        // 1) 数据序列化进一个 zip
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(zipBytes)) {
            zos.putNextEntry(new ZipEntry("backup.json"));
            zos.write(GSON.toJson(payload).getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        // 2) 对整个 zip 加密
        out.write(BackupCrypto.encrypt(zipBytes.toByteArray(), password));
        out.flush();
    }

    // ---------- 导入 ----------

    /**
     * 读入备份包并把数据合并回本机。
     *
     * <p>合并规则用的是仓储层的 {@code upsertLww}：缺的记录补上，同一条按 lastModified 取新的。
     * 也就是「补齐 + 取新」，不会删除本机已有记录 —— 恢复备份最怕的是一把覆盖掉新数据。</p>
     */
    public static RestoreResult restore(Context context, InputStream in, char[] password) throws Exception {
        byte[] encrypted = readAll(in);
        byte[] plain = decrypt(encrypted, password);

        String json = unzipBackupJson(plain);
        if (json == null) throw new WrongPasswordException();
        Payload payload = GSON.fromJson(json, Payload.class);
        if (payload == null) throw new WrongPasswordException();

        RestoreResult result = new RestoreResult();
        AccountRepo accountRepo = new AccountRepo(context);
        CategoryRepo categoryRepo = new CategoryRepo(context);
        TransactionRepo txRepo = new TransactionRepo(context);
        BudgetRepo budgetRepo = new BudgetRepo(context);
        TemplateRepo templateRepo = new TemplateRepo(context);
        RecurringRepo recurringRepo = new RecurringRepo(context);

        if (payload.accounts != null) {
            for (Account a : payload.accounts) {
                if (a != null && a.clientId != null) {
                    accountRepo.upsertLww(a);
                    result.accounts++;
                }
            }
        }
        if (payload.categories != null) {
            for (Category c : payload.categories) {
                if (c != null && c.clientId != null) {
                    categoryRepo.upsertLww(c);
                    result.categories++;
                }
            }
        }
        if (payload.transactions != null) {
            for (Transaction t : payload.transactions) {
                if (t != null && t.clientId != null) {
                    txRepo.upsertLww(t);
                    result.transactions++;
                }
            }
        }
        if (payload.budgets != null) {
            for (Budget b : payload.budgets) {
                if (b != null && b.clientId != null) {
                    budgetRepo.upsertLww(b);
                    result.budgets++;
                }
            }
        }
        if (payload.templates != null) {
            for (Template t : payload.templates) {
                if (t != null && t.clientId != null) {
                    templateRepo.upsertLww(t);
                    result.templates++;
                }
            }
        }
        if (payload.recurringRules != null) {
            for (RecurringRule r : payload.recurringRules) {
                if (r != null && r.clientId != null) {
                    recurringRepo.upsertLww(r);
                    result.recurringRules++;
                }
            }
        }
        return result;
    }

    /** 全量读出（含软删除墓碑），用于导出。 */
    private static final class AppData {
        List<Transaction> transactions;
        List<Category> categories;
        List<Account> accounts;
        List<Budget> budgets;
        List<Template> templates;
        List<RecurringRule> recurringRules;

        static AppData read(Context context) {
            AppData d = new AppData();
            d.transactions = new TransactionRepo(context).listModifiedSince(0);
            d.categories = new CategoryRepo(context).listModifiedSince(0);
            d.accounts = new AccountRepo(context).listModifiedSince(0);
            d.budgets = new BudgetRepo(context).listModifiedSince(0);
            d.templates = new TemplateRepo(context).listModifiedSince(0);
            d.recurringRules = new RecurringRepo(context).listModifiedSince(0);
            return d;
        }
    }

    // ---------- 加解密：交给 BackupCrypto（与电脑端同一套参数） ----------

    private static byte[] decrypt(byte[] encrypted, char[] password) throws WrongPasswordException {
        try {
            return BackupCrypto.decrypt(encrypted, password);
        } catch (Exception e) {
            // 口令不对、文件被改过、根本不是本应用的备份，都会落在这里
            throw new WrongPasswordException();
        }
    }

    private static String unzipBackupJson(byte[] zipBytes) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!"backup.json".equals(entry.getName())) continue;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = zis.read(buffer)) > 0) out.write(buffer, 0, n);
                return new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toByteArray();
    }
}
