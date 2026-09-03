# 语音记账与 Android 小组件

## 语音记账

### 实现思路

1. 用系统 `SpeechRecognizer`（`RecognizerIntent.ACTION_RECOGNIZE_SPEECH`）获取文本。
2. 用正则提取金额：`(\d+(?:\.\d{1,2})?)\s*(元|块|块钱|¥|￥)`。
3. 用关键词表猜测分类（餐饮/交通/购物/住房/通讯/医疗/教育/娱乐/人情）。
4. 若分类命中，自动填入表单；否则回退「其他」或让用户选择。

### 关键代码（解析器）

```java
public static Parsed parse(String text) {
    Matcher m = AMOUNT.matcher(text.trim());
    double amount = m.find() ? Double.parseDouble(m.group(1)) : 0;
    return new Parsed(amount, guessCategory(text));
}
```

### 权限

- `android.permission.RECORD_AUDIO`
- Android 11+ 在 `<queries>` 声明 `RecognitionService`。

## Android 桌面小组件

### 实现思路

- `AppWidgetProvider` 提供 `RemoteViews`，放置一个可点击区域。
- 点击通过 `PendingIntent` 打开 `QuickAddWidgetActivity`（轻量，无需主界面）。
- 小组件页内含金额输入 + 常用分类快捷按钮，保存后直接写本地库并后台同步。

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
