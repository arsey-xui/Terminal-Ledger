package com.personal.accounting.android.data;

import android.content.Context;

import com.google.gson.Gson;
import com.personal.accounting.android.analysis.AiAnalysis;
import com.personal.accounting.android.analysis.AiSettingsRules;
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
 *
 * <p>载荷里除账本数据外还带一份 {@link AiSettings}（含 API Key）。加它的原因：AI 设置原先只存在
 * 本机偏好里，而且 Key 是用本机 Keystore 加密的，卸载重装或换机之后必然丢 ——
 * 账本能从云端同步回来，Key 却回不来。放进这个已被口令加密的备份包，恢复一次就能带回来。</p>
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
        /**
         * AI 消费分析的设置（含 API Key）。整个备份包由口令派生的 AES-256-GCM 加密，
         * Key 受同一层保护，所以这里存**明文**而不是本机那套 Keystore 密文 ——
         * 后者是用本机不可导出的密钥加密的，换台设备就再也打不开了。
         * 字段名与电脑端一致，两边可互相恢复；老版本读到这个未知字段会安全忽略。
         */
        AiSettings aiSettings;
        long exportedAt;
    }

    /** 备份包里的 AI 设置；字段名与电脑端一致。 */
    static final class AiSettings {
        String apiKey;
        String baseUrl;
        String model;
        String prompt;
    }

    /** 导入结果统计，用于给用户一句实话。 */
    public static final class RestoreResult {
        public int transactions;
        public int categories;
        public int accounts;
        public int budgets;
        public int templates;
        public int recurringRules;
        /** 备份里带着 API Key，且已写回本机。 */
        public boolean aiSettings;

        public String summary() {
            return "流水 " + transactions + " 条 · 分类 " + categories + " 个 · 账户 " + accounts
                    + " 个 · 预算 " + budgets + " 条 · 模板 " + templates + " 条 · 周期规则 "
                    + recurringRules + " 条" + (aiSettings ? " · AI 设置已恢复" : "");
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
        payload.aiSettings = readAiSettings(context);
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
        result.aiSettings = applyAiSettings(context, payload.aiSettings);
        return result;
    }

    /**
     * 导出当前 AI 设置。Key 取的是明文（{@link AiAnalysis#apiKey} 已经解好密）：
     * 备份包整体由口令加密，正好用来装这种「换机之后还要能打开」的机密。
     */
    private static AiSettings readAiSettings(Context context) {
        AiSettings s = new AiSettings();
        s.apiKey = AiAnalysis.apiKey(context);
        s.baseUrl = AiAnalysis.baseUrl(context);
        s.model = AiAnalysis.model(context);
        s.prompt = AiAnalysis.prompt(context);
        return s;
    }

    /**
     * 恢复备份里的 AI 设置。规则是「<b>只补不清</b>」，与账本数据的 {@code upsertLww} 同一个思路。
     *
     * <p>备份里没有 Key（字段缺失或为空）就整块跳过：恢复一份「配 Key 之前」做的旧备份，
     * 不应该把现在配好的 Key 清掉 —— 那正是加这个字段要解决的问题。电脑端导出的备份
     * 若没配过 Key，走的也是这条路径。</p>
     *
     * <p>有 Key 时才写回，且写回时会被 {@link AiAnalysis#save} 重新用本机 Keystore 加密，
     * 所以备份里的明文只在恢复那一瞬间存在。</p>
     *
     * @return 是否真的恢复了 AI 设置
     */
    private static boolean applyAiSettings(Context context, AiSettings s) {
        if (s == null) return false;
        // 规则本身在 AiSettingsRules 里（纯逻辑、可在 JVM 上测）；这里只负责读本机值、写回。
        AiSettingsRules.Values merged = AiSettingsRules.merge(
                new AiSettingsRules.Values(s.apiKey, s.baseUrl, s.model, s.prompt),
                new AiSettingsRules.Values(AiAnalysis.apiKey(context), AiAnalysis.baseUrl(context),
                        AiAnalysis.model(context), AiAnalysis.prompt(context)));
        if (merged == null) return false;                   // 备份里没有 Key：整块跳过
        AiAnalysis.save(context, merged.apiKey, merged.baseUrl, merged.model, merged.prompt);
        return true;
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
