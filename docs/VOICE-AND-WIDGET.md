# 语音记账与 Android 小组件

## 语音记账

### 输入格式

固定为 **账户 + 分类 + 金额**，三段顺序随意，也可以只说出其中一两段：

| 说 / 输入 | 账户 | 分类 | 金额 | 备注 |
| --- | --- | --- | --- | --- |
| `支付宝午餐27元` | 支付宝 | 餐饮（由「午餐」推出） | 27 | 午餐 |
| `微信打车15块` | 微信 | 交通 | 15 | 打车 |
| `支付宝午餐27` | 支付宝 | 餐饮 | 27 | 午餐 |
| `餐饮27元` | 保持当前 | 餐饮 | 27 | 餐饮 |
| `27元` | 保持当前 | 其他 | 27 | （空） |

- **账户**：只在本机已有的账户名里匹配，匹配到才切换；没说到的段保持当前所选账户不变。
- **分类**：先在本机分类名里找（说的是分类本身），再按关键词表推出分类名（说的是「午餐」这类口语词），都没有则回退「其他」。
- **金额**：`27元`、`27块`、`¥27` 都认，位置随意。漏说单位时兜底认**整句末尾**的裸数字（`支付宝午餐27` → 金额 27、备注「午餐」）；只认末尾是为了避免把「3人聚餐」的 3、「买了2件」的 2 当成金额，所以裸数字要放在最后。
- **备注**：摘掉识别到的账户名和金额之后**剩下的部分**，所以「支付宝午餐27元」的备注是「午餐」而不是整句原文；剩下的只有标点或空白时备注为空。
- 名字匹配是「长的优先」，所以同时存在「微信」和「微信零钱」时会选中更具体的那个；少于 2 个字的名字不参与匹配，避免误命中。

### 实现思路

1. 语音转文字交给输入法键盘上的麦克风（普通文本输入框），本应用不申请也不使用 `RECORD_AUDIO`。
2. `VoiceInputParser` 按上面三段拆解文本，账户名与分类名由调用方传入，不写死在解析器里 —— 用户自建的账户（如「招行卡」）和分类（如「宠物」）同样能识别。
3. `VoiceEntryDialog` 是手机端**唯一**的语音入口：记账页（`RecordFragment`）和桌面小组件（`QuickAddWidgetActivity`）都实现它的 `Host` 接口，因此两处的输入格式、解析规则和提示文案完全一致 —— 改格式只需要改这一个地方。
4. 回填：金额写进输入框、备注只留有用的那段、分类调用 `selectCategory()` 高亮对应分类框、账户调用 `refreshAccountButton()` 切到说到的那个账户。小组件则把金额/分类/账户/备注带进它的保存流程。
5. 回填后弹一条提示，说明三段各自落到了哪里、哪一段没识别到（没识别到金额时会提示正确说法）。

### 关键代码（解析器）

```java
public static Parsed parse(String text, Collection<String> accountNames,
                           Collection<String> categoryNames) {
    double amount = 0;
    String rest = text;
    AmountMatch m = findAmount(text);       // 带单位/货币符号的写法，或末尾的裸数字
    if (m != null) {
        amount = m.value;
        rest = text.substring(0, m.start) + text.substring(m.end);
    }
    String account = firstContained(text, accountNames);        // 账户
    return new Parsed(amount,
            guessCategory(text, categoryNames),                 // 分类
            account,
            cleanNote(removeFirst(rest, account)));             // 备注 = 摘完剩下的部分
}
```

## Android 桌面小组件

### 实现思路

- `AppWidgetProvider` 提供 `RemoteViews`，放置一个可点击区域。
- 点击通过 `PendingIntent` 打开 `QuickAddWidgetActivity`（轻量，无需主界面）。
- 小组件页内含金额输入 + 常用分类快捷按钮，保存后直接写本地库并后台同步。
- 语音按钮走与记账页同一个 `VoiceEntryDialog`：「账户 + 分类 + 金额」解析出的金额、分类、账户和备注都会填进来，保存时账户用语音选中的那个（没说到则退回「现金」），备注用解析出来的那段（为空则退回「小组件快捷记账」）。

### 关键代码

```java
PendingIntent pi = PendingIntent.getActivity(context, 0,
        new Intent(context, QuickAddWidgetActivity.class),
        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
views.setOnClickPendingIntent(R.id.widget_root, pi);
```

### 配置

- `res/xml/quick_add_widget_info.xml` 定义尺寸与 `initialLayout`。
- Manifest 注册 `<receiver>`。
