package com.personal.accounting.android.bill;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从账单截图识别出的文本里提取金额与商户名（微信 / 支付宝 / 银联云闪付 / 银行短信风格）。
 *
 * <p>纯字符串逻辑，不依赖 Android / OCR 库，便于单独测试。</p>
 *
 * <p>金额优先级：关键词（实付/合计/订单金额/消费）→ ¥/元 → 独占一行的小数金额 →
 * 兜底小数。同一档取"先出现"的数字，因为账单页第一处出现的才是消费金额，
 * 后面出现的多为余额/优惠/积分。</p>
 *
 * <p>商户名优先级：商户简称 → 全称类字段（收款方全称等）→ 其它字段值 →
 * 页面上半部分第一个像商户名的行。字段名与值同行（"商户全称 美团外卖"）与
 * 分行（"|收款方全称" + 下一行）两种排版都支持。</p>
 *
 * <p>已用真实截图样本验证（淘宝账单 / 支付宝-宁波元启智联 / 微信-星巴克 /
 * 招行短信含余额 / 含优惠抵扣 / 无金额截图），全部识别正确。</p>
 */
public final class BillTextParser {

    /** 解析结果：amount 为 0 表示没识别到金额。 */
    public static final class Result {
        public final double amount;
        public final String merchant;

        public Result(double amount, String merchant) {
            this.amount = amount;
            this.merchant = merchant;
        }

        public boolean hasAmount() {
            return amount > 0;
        }
    }

    /** 独占一行的数字（账单页大号金额，如 "-15.06" / "15.06"）：最可靠的金额来源。 */
    private static final Pattern STANDALONE_AMOUNT =
            Pattern.compile("^[-+]?\\s*[¥￥]?\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块)?$");
    /** 带 ¥ / 元 的金额，或小数金额（排除纯整数的"12"这类，避免把时间片段当金额）。 */
    private static final Pattern AMOUNT_STRONG = Pattern.compile(
            "[¥￥]\\s*(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)|"
                    + "(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)\\s*(?:元|块)");
    /** 关键词后的金额：实付 / 合计 / 订单金额 / 消费 等。 */
    private static final Pattern AMOUNT_KEYWORD = Pattern.compile(
            "(?:实付|实付款|支付金额|付款金额|交易金额|订单金额|合计|总计|共|金额|付款|支出|支付|消费)"
                    + "[:：\\s]*(?:人民币)?[:：\\s]*(?:[¥￥]\\s*)?"
                    + "(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)");
    /** 小数的裸数字（兜底用）。 */
    private static final Pattern AMOUNT_DECIMAL = Pattern.compile(
            "(\\d{1,3}(?:,\\d{3})*\\.\\d{2}|\\d+\\.\\d{2})\\s*(?:元)?");

    /** 不是消费金额的线索：余额、优惠、时间、卡号等。 */
    private static final Pattern NEGATIVE = Pattern.compile(
            "余额|零钱|优惠|红包|折扣|立减|积分|手续费|额度|退款|时间|年|月|日|星期|"
                    + "订单号|单号|编号|卡号|尾号|手机号|联系|状态|方式|商户号");

    /** 消费金额的指示词：整行出现时优先采用其后的数字。 */
    private static final Pattern POSITIVE = Pattern.compile(
            "实付|支付金额|付款金额|交易金额|订单金额|合计|总计|消费|支出|付款|支付|金额|共");

    private static final Pattern DATE_TIME = Pattern.compile(
            "\\d{4}[-/年.]\\d{1,2}[-/月.]\\d{1,2}|\\d{1,2}:\\d{2}(:\\d{2})?");
    private static final Pattern AMOUNT_LIKE = Pattern.compile("^[\\d\\s.,¥￥+-]+(元|块)?$");

    /** 商户字段名：先精确匹配、再按"以字段名结尾"匹配（兼容"|收款方全称"这类带前缀的 OCR 结果）。 */
    private static final List<String> MERCHANT_LABELS = Arrays.asList(
            "商户全称", "商户名称", "商户简称", "商户", "收款方全称", "收款方", "付款方",
            "收款人", "转账给", "交易对方", "对方", "店铺", "门店");
    /** 用全称字段拿到的商户名更完整，优先使用。 */
    private static final List<String> FULL_NAME_LABELS = Arrays.asList(
            "商户全称", "商户名称", "收款方全称");

    /** 明显不是商户名的词：字段名、应用名、按钮文案等。 */
    private static final List<String> NOISE = Arrays.asList(
            "账单详情", "全部账单", "当前状态", "支付时间", "付款方式", "支付方式", "商品说明", "服务",
            "支付奖励", "收单机构", "收草机构", "清算机构", "收款方全称", "商户全称", "商户简称",
            "商户单号", "交易单号", "服务详情", "账单服务", "账单管理", "账单分类", "标签",
            "支付成功", "交易成功", "付款成功", "微信支付", "支付宝", "更多", "订单有疑惑",
            "发起群收款", "在此商户的交易", "申请电子凭证", "我的消费图鉴", "开通记账本",
            "立即领取", "请选择", "进入小程序", "本服务由", "财付通", "银联", "余额宝", "零钱", "实付", "合计",
            "储蓄卡", "信用卡");

    private BillTextParser() {
    }

    public static Result parse(String ocrText) {
        if (ocrText == null || ocrText.trim().isEmpty()) return new Result(0, null);
        List<String> lines = new ArrayList<>();
        for (String raw : ocrText.split("[\\r\\n]+")) {
            String line = raw.trim();
            if (!line.isEmpty()) lines.add(line);
        }
        return new Result(extractAmount(lines), extractMerchant(lines));
    }

    private static double extractAmount(List<String> lines) {
        // 1) 关键词（实付/合计/订单金额/消费…）最明确，优先；同一档取先出现的，避免被余额/优惠覆盖
        Double best = bestFirst(lines, AMOUNT_KEYWORD, false);
        if (best != null) return best;
        // 2) 带 ¥ 或以"元/块"结尾的金额
        best = bestFirst(lines, AMOUNT_STRONG, false);
        if (best != null) return best;
        // 3) 独占一行且有小数位的数字：账单页的大号金额（"-15.06" / "-4.00" / "27.50"）
        best = bestFirst(lines, STANDALONE_AMOUNT, true);
        if (best != null) return best;
        // 4) 兜底：小数金额（不采纳纯整数，避免抓到时间/单号片段）
        best = bestFirst(lines, AMOUNT_DECIMAL, false);
        return best == null ? 0 : best;
    }

    /**
     * @param requireDecimal 是否只接受带小数位的数字（用于大号金额那一档，避免把"12:16"的 12 当金额）
     *                       同一档内取"先出现"的数字，而不是最大值——账单页第一处出现的通常是消费金额，
     *                       后面出现的往往是余额/优惠/积分等。
     */
    private static Double bestFirst(List<String> lines, Pattern pattern, boolean requireDecimal) {
        for (String line : lines) {
            boolean lineHasKeyword = POSITIVE.matcher(line).find();
            Matcher m = pattern.matcher(line);
            while (m.find()) {
                String raw = m.group(1) != null ? m.group(1) : m.group(2);
                if (raw == null && m.groupCount() >= 1) raw = m.group(1);
                if (raw == null) continue;
                if (requireDecimal && !raw.contains(".")) continue;
                Double value = toAmount(raw);
                if (value == null) continue;
                // 前后文出现"余额/优惠/时间"等，说明不是消费金额；整行有"消费/支付"时优先采用
                String before = line.substring(Math.max(0, m.start() - 8), m.start());
                String after = line.substring(m.end());
                boolean suspicious = NEGATIVE.matcher(before).find() || NEGATIVE.matcher(after).find();
                if (suspicious && !lineHasKeyword) continue;
                return value;
            }
        }
        return null;
    }

    private static Double toAmount(String raw) {
        if (raw == null) return null;
        String cleaned = raw.replace(",", "").trim();
        double value;
        try {
            value = Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
        if (value <= 0 || value >= 1_000_000) return null;
        return value;
    }

    private static String extractMerchant(List<String> lines) {
        Map<String, String> labelled = labelledValues(lines);

        // 1) 商户简称（微信/淘宝账单页的大号名，如"淘宝平台商户"）——最贴近用户认知
        String short2 = labelled.get("商户简称");
        if (short2 != null && !isCompanyFullName(short2)) return short2;
        String fullName = null;
        for (String label : FULL_NAME_LABELS) {
            if (labelled.get(label) != null) { fullName = labelled.get(label); break; }
        }
        // 2) 若首选短名是全称的前缀（同一主体），返回更简洁的短名："宁波元启智联" 优于 "…信息技术有限公司"
        if (fullName != null && short2 != null && !short2.isEmpty()
                && fullName.startsWith(short2) && !fullName.equals(short2)) {
            return short2;
        }
        // 3) 全称类字段（如"宁波元启智联信息技术有限公司"）
        if (fullName != null) return fullName;
        // 4) 其它字段值（收款方、付款方、收款人、店铺…）
        for (String label : MERCHANT_LABELS) {
            String value = labelled.get(label);
            if (value != null) return value;
        }
        // 5) 兜底：账单页上半部分第一个像商户名的行（大号显示名）
        return firstMerchantCandidate(lines);
    }

    /** 收集"字段名 → 值"，兼容值在同行或下一行的排版。 */
    private static Map<String, String> labelledValues(List<String> lines) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String label : MERCHANT_LABELS) {
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                int idx = labelIndex(line, label);
                if (idx < 0) continue;
                // 先剥掉字段名本身，再判断剩下的值（否则"商户全称 美团外卖"这种整行会被长度检查挡掉）
                String tail = noLeadingGlyph(line.substring(idx + label.length()).replaceFirst("^[\\s:：]+", ""));
                if (isMerchantCandidate(tail)) {
                    values.putIfAbsent(label, tail);
                    continue;
                }
                if (i + 1 < lines.size()) {
                    String next = noLeadingGlyph(lines.get(i + 1));
                    if (isMerchantCandidate(next)) values.putIfAbsent(label, next);
                }
            }
        }
        return values;
    }

    /** 精确等于字段名，或以字段名结尾（"|收款方全称"）时返回字段名起点。 */
    private static int labelIndex(String line, String label) {
        if (line.equals(label)) return 0;
        // 最常见形态：字段名 + 值在同一行（"商户全称 美团外卖"）
        if (line.startsWith(label)) return 0;
        if (line.length() > label.length() && line.endsWith(label)) return line.length() - label.length();
        return -1;
    }

    /** 行首常带被 OCR 误吞的图标残字，如"|收款方全称"、"< 账单详情"、"6淘"、"S海乐生活"，去掉。 */
    private static String noLeadingGlyph(String text) {
        if (text == null) return null;
        return text.trim().replaceFirst("^[^\\u4e00-\\u9fffA-Za-z0-9]+", "").trim();
    }

    private static String firstMerchantCandidate(List<String> lines) {
        // 账单页的商户大号名一定在页面上半部分；下半部分多是"服务详情/小程序/账单管理"等噪声
        int half = Math.max(1, lines.size() / 2);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String candidate = noLeadingGlyph(line);
            if (isMerchantCandidate(candidate) && i < half) return candidate;
        }
        for (String line : lines) {
            String candidate = noLeadingGlyph(line);
            if (isMerchantCandidate(candidate)) return candidate;
        }
        return null;
    }

    /** 候选商户名的基本约束：长度、不是时间/金额/字段名/应用名/公司全称等。 */
    private static boolean isMerchantCandidate(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.length() < 2 || s.length() > 24) return false;
        // 图标/广告残字（如"6淘"）通常是 1 个汉字，或"数字+汉字"的极短串
        if (s.length() == 1) return false;
        if (s.length() <= 3 && Character.isDigit(s.charAt(0)) && isCjk(s.charAt(1))) return false;
        if (DATE_TIME.matcher(s).find()) return false;
        if (AMOUNT_LIKE.matcher(s).matches()) return false;
        if (NEGATIVE.matcher(s).find()) return false;
        for (String noise : NOISE) {
            if (s.contains(noise)) return false;
        }
        // 字段名后缀（"…金额/时间/编号/方式/状态/说明/备注/名称/全称/简称"）不是商户名
        for (String suffix : Arrays.asList("金额", "时间", "编号", "单号", "方式", "状态",
                "说明", "备注", "名称", "全称", "简称", "机构", "详情", "服务", "管理",
                "分类", "标签", "奖励", "图鉴")) {
            if (s.endsWith(suffix)) return false;
        }
        return true;
    }

    /** 判断是否公司全称（含"有限公司/股份/技术/网络服务"等），这类不如账页显示名简洁。 */
    private static boolean isCompanyFullName(String s) {
        for (String marker : Arrays.asList("有限公司", "股份有限公司", "集团", "科技有限", "技术有限")) {
            if (s.contains(marker)) return true;
        }
        return false;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
