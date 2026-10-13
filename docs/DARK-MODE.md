# Android 深浅模式（鹰角网络风格）

## 设计约束

深色**不是另做一套皮肤**，而是同一套鹰角网络（Hypergryph）设计语言在近黑基底上的表达。所以：

- **强调色 `#C0FF00` 两个基底完全一致** —— 它是品牌锚点，规范里点名的酸橙绿。
- **`#1A1A1A` 与 `#F5F5F5` 角色互换**：浅色下前者当字、后者当底；深色下反过来。
  这样既保持同一套色板，又不会让深色发灰。
- **`#2C2C2C` 来自规范给的主色列表**，深色下补位成面板/卡片。
- **1px 细线保留**（深色下 `#3D3D3D`）：它是这套语言的骨架，不能因为深色就省略。
- **直角矩形、超大标题配微小标签、舞台与仪表的布局**都不因深色而改变。

## 两个基底的语义对照

| 语义角色 | 浅色 | 深色 |
| --- | --- | --- |
| accent / primary | `#C0FF00` | `#C0FF00`（不变）|
| primary_dark | `#A6D800` | `#A6D800`（不变）|
| background（舞台底）| `#F5F5F5` | `#0A0A0A`（近黑）|
| surface（控件面）| `#FFFFFF` | `#1A1A1A` |
| card（面板）| `#FAFAFA` | `#2C2C2C` |
| text_primary | `#1A1A1A` | `#F5F5F5` |
| text_secondary | `#8C8C8C` | `#9E9E9E` |
| hairline | `#D9D9D9` | `#3D3D3D` |
| income | `#2E9E24` | `#7CD86A` |
| expense | `#D64545` | `#FF6B6B` |
| warning | `#A07800` | `#FFD700` |
| heatmap_empty | `#EDEDED` | `#1F1F1F` |

红绿在深色下只提明度、不改色相。

## 文件布局

```
res/values/colors.xml            # 全部语义色（浅色）
res/values/pie_palette.xml       # 饼图调色板（浅色）
res/values/themes.xml            # 主题（浅色）
res/color/nav_item_color.xml     # 底部导航着色（浅色）

res/values-night/colors.xml      # 只覆盖需要变的语义色
res/values-night/pie_palette.xml # 饼图调色板（深色）
res/values-night/themes.xml      # 只改状态栏图标明暗
res/values-night/color/nav_item_color.xml
```

## 三个曾经踩过的坑

### 1. 主题把底色写死了，盖掉父主题的自动适配

`windowBackground` / `colorBackground` 一旦在主题里显式声明，就会**覆盖** `DayNight` 父主题
按系统模式自动选择的背景 —— 结果是系统切深色后，自己绘制的部分（对话框、日期选择器、涟漪）
变深了，而应用自己的底色还是浅的，出现「混搭」。

**现在两处都不写**，交给父主题。深色主题只声明「必须在深色底上翻转」的状态栏图标明暗。

### 2. 颜色硬编码在 Java 里，`values-night` 覆盖不到

`values-night` 只能覆盖 XML 里的 `@color` 引用，覆盖不到代码里的 `0xFF8C8C8C`。
改造前 `ReportFragment` 一个文件就有 24 处、全项目 32 处硬编码：

- 图表坐标轴/网格线/无数据提示
- 饼图调色板
- 热力图渐变基色（原来是把「白→酸橙绿」的通道值写死算出来的，深色下必然算错）
- 预算进度条与三档状态色
- 历史流水的金额红绿

现在统一走 `ui/ChartColors`：`ChartColors.of(context, R.color.xxx)` 按资源取色，
`ChartColors.piePalette(context)` 读 `R.array.pie_palette`。
**新增图表或状态色时不要再写十六进制字面量。**

### 3. 饼图标签的反色必须跟着扇区走
`PieSliceLabelRenderer` 原来固定「金额黑字 + 分类名白字」，因为浅色调色板以中浅色为主。
深色调色板里出现了更深的灰，固定黑字会糊在扇区上。

现在渲染器对**每个扇区**按颜色算感知亮度（Rec.601 加权，阈值 0.55），
自动选深字或浅字，两行标签取同一个反色（避免「一行黑一行白」的花脸），
描边颜色也随之在深/浅两种描边之间切换。深浅两套调色板因此共用同一个渲染器。

## 刻意不跟随主题的两处

| 文件 | 为什么固定 |
| --- | --- |
| `layout/marker_view.xml` | 图表点击气泡：深色半透明底 + 白字。深色底上是「更深的气泡 + 白字」，悬浮感更强；跟主题走会糊进深色图表。|
| `layout/widget_quick_add.xml` | 桌面小组件是独立视图层级，拿不到 Activity 主题；酸橙绿底黑字正是品牌强调色的用法。|

## 系统「强制深色」(force dark)

Android 10+ 有个强转机制：对**只有浅色主题**的应用，系统会按启发式自动反色。它的启发式很容易翻车 ——
**只反文字、不反背景**，于是出现「白字压浅灰底」这种完全看不清的结果。本应用自带了完整的深浅两套配色，
绝不该让系统再转一次，所以两个主题里都写了：

```xml
<item name="android:forceDarkAllowed">false</item>
```

API 29 以下无此属性，会被忽略。

### 输入框的 hint 与光标色必须显式声明

`textColorHint` 与 `colorControlNormal` **不跟随** `textColorPrimary`。不声明的话会取父主题的浅色值，
表现为：界面底色已经是近黑，而「金额」「备注（选填）」这些提示文字仍然是白/浅灰 —— 白字压深底勉强能看，
一旦底色没跟着变就彻底看不见。所以这两项在两个基底的 themes.xml 里都钉成了 `@color/text_secondary`
（浅色 `#8C8C8C` / 深色 `#9E9E9E`），`Switch` 的轨道与滑块也随之取主题色。

## 设置里的「深浅模式」

**设置 → 外观 → 深浅模式**，三选一：跟随系统（默认）/ 强制浅色 / 强制深色。

这个开关不只是给用户选偏好，也是排查工具：部分 ROM（如 ColorOS）的「强制深色」会在浅色界面上
按启发式反色（只反文字不反背景）。选「强制深色」时是应用通过 `AppCompatDelegate` 显式要求深色资源，
不经过系统的启发式判断 —— 于是可以把两件事分开：

- 强制深色下正常 → 配色方案本身没问题，问题只在「跟随系统」这一步；
- 强制浅色下正常 → 浅色那套没被改坏。

实现见 `AccountingApp`：模式存在 `app_prefs/theme_mode`，`onCreate` 里读出来应用；
设置页改动后由 `AppCompatDelegate` 触发重建，界面与按钮文字随之刷新。

### 4. 环形图的「洞」颜色不受主题控制，而且 setter 有隐藏依赖

MPAndroidChart 的 `PieChartRenderer` 内部有两个 Paint：`mHolePaint` 与 `mTransparentCirclePaint`，
构造函数里**都写死成纯白**（字节码是 `setColor(-1)`，透明圆再加 `setAlpha(105)`），不随主题变化。
`drawHole()` 先画 `transparentCircleRadius` 处的圆、再画洞 —— 所以深色下就是深底顶着一个白圆，
而且外面还会多出一圈更亮的环（两个半径之间的那层）。

**坑在于 `PieChart` 的这两个 setter 依赖调用时刻的 `mRenderer`：**

```java
public void setHoleColor(int color) {
    ((PieChartRenderer) mRenderer).getPaintHole().setColor(color);   // 取的是「当前」渲染器
}
```

本页用自定义渲染器接管标签绘制，如果在 `setRenderer(...)` **之前**调用 `setHoleColor()`，
颜色就落不到最终在画的那个 Paint 上。正确写法是装好渲染器之后再设，并且直接对它设色，
不经过 `PieChart` 的封装：

```java
PieSliceLabelRenderer renderer = new PieSliceLabelRenderer(...);
pie.setRenderer(renderer);
int chartBg = ChartColors.of(requireContext(), R.color.background);
renderer.getPaintHole().setColor(chartBg);              // public 方法，PieChartRenderer 上就有
renderer.getPaintTransparentCircle().setColor(chartBg);
```

### 5. 饼图调色板要按「明度从高到低」排序

调色板是按扇区**占比从大到小**依次取用的，所以顺序就是明度顺序：
最亮的颜色给最大的扇区（配深字），最暗的放到最后（只会出现在小扇区，那些扇区的标签本来就走环外引线）。
两个基底的调色板都按这个规则排过 —— 否则会出现「占比第三大的扇区拿到一块深灰、配白字」的突兀效果。

## 图表必须走调色板，不能用库的默认外观

MPAndroidChart / JavaFX Charts 都自带一套「默认观感」：虚线灰网格、比例字体刻度、自带的蓝红绿紫配色。
这些和「酸橙绿 + 直角 + 1px 细线」的工业语言不是一套，**每个图表都要显式覆盖**，
否则会出现「界面是这套风格、图表是另一套」的割裂。

手机端（`ReportFragment`）：

| 项 | 做法 |
| --- | --- |
| 网格线 | `styleGridLines()` 统一成 `@color/chart_line`（与 hairline 同值）+ 1px 线宽；竖网格线关掉（对这几张图没有信息量），左侧轴自身不画竖线 |
| 刻度数字 | `Typeface.MONOSPACE` —— 规范要求关键数据用 tabular numerals，比例字体下刻度会参差 |
| 柱状图配色 | 支出 = `@color/primary`（酸橙绿），收入 = `@color/income`；**不再硬编码 `#2E9E24`** |
| 被选中的柱子 | `@color/chart_text`（深灰），与未选中的酸橙绿形成「仪表选中」的对比 |
| 折线 | `@color/primary` 描边 + `@color/chart_fill` 低不透明度填充 |
| 饼图 | `@array/pie_palette`（按明度排序），图例关闭（标签已含金额与分类名）|
| 无数据提示 | `@color/no_data` |
| 图例 / 坐标轴文字 | `@color/chart_text` / `@color/chart_axis` |

电脑端（`app.css` + `DashboardController`）：

| 项 | 做法 |
| --- | --- |
| 网格线 | `.chart-*-grid-lines { -fx-stroke: #D9D9D9 }`（原来是 `rgba(0,0,0,.08)` 的库默认观感）|
| 刻度数字 | `.axis { -fx-tick-label-font-family: "Consolas", "Menlo", monospace }` |
| 饼图八色 | 按明度从高到低重排，与手机端 `pie_palette.xml` 对齐 —— 调色板按扇区占比依次取用，最亮的必须排在最前 |
| 折线数据点 | 中心从白色改为底色，避免一串白点跳出来 |
| Java 里的字面量 | 收敛成 `COLOR_PRIMARY` / `COLOR_EXPENSE` / `COLOR_INCOME` / `COLOR_HAIRLINE` 四个常量 |

> 电脑端**没有**深色模式（CSS 只有一套），所以这些是浅色基底的直接取值；
> 手机端则全部走 `@color` 资源，深浅两个基底自动切换。

## 空状态与首次使用引导

新装的库如果到处是「暂无数据」、首次打开又是一个空记账页，用户不知道该先做什么。两端的处理：

### 首次使用引导卡

**只在「一条流水都还没有」时显示**，记完第一笔自动消失。

判断依据是流水总数，不是「已看过引导」的标记：

```java
boolean empty = txRepo.countAll() == 0;     // 手机端
boolean empty = db.countTransactions() == 0; // 电脑端
```

这样做的好处是**不会出现错位** —— 标记方案可能「标记说已看过、用户其实什么都没记」，
也可能「用户记过又全删了、却再也不显示引导」。看数据本身就没有这些边界情况，
卸载重装、清数据、换库都能正确复位。

两端首启都会写入内置默认分类与账户（手机端 `DatabaseHelper.seedDefaults()`，
电脑端 `DataManager.seedDefaults()`，clientId 固定且两端一致），所以引导卡的重点不是
「让你去建分类」，而是告诉用户**默认项已经配好、可以直接开始，想改去哪里改**。

- 手机端：`fragment_record.xml` 的 `first_use_card`，背景复用 `@drawable/category_chip`
  （直角 + 1px 细线，两个基底自动切换），由 `RecordFragment.refreshFirstUseCard()` 控制显隐。
- 电脑端：`record.fxml` 的 `firstUseCard`（`.panel` 样式），由 `RecordController.refreshFirstUseCard()`
  控制。注意 JavaFX 里**必须同时切 `managed`**，否则隐藏的节点仍占布局，会留一块空白。

### 空状态文案要说清「为什么空」和「怎么让它不空」

不要写「暂无数据」。对比：

| 位置 | 之前 | 现在 |
| --- | --- | --- |
| 饼图 | 暂无数据 | 本月还没有支出记录 / 记一笔就会出现在这里 |
| 近 30 天折线 | 暂无数据 | 最近 30 天还没有支出记录 / 记一笔就会出现在这里 |
| 跨月趋势 | 暂无数据 | 近半年还没有支出记录 / 记一笔就会出现在这里 |
| 预算进度 | 暂无数据 | 还没有设预算 / 给分类设上限后，这里显示进度 |
| 年度热力图 | 暂无数据 | 今年还没有支出记录 / 记一笔就会出现在这里 |
| 流水页 | 暂无记录 / 去记一笔吧 | 还没有任何流水 / 去「记账」填一笔，这里就会列出来 |
| 流水页（有筛选）| 没有符合条件的记录 / 换个条件试试 | 没有符合条件的记录 / 把上面的筛选放宽一点试试 |
| 分类栏 | （只剩一个「＋ 新增」按钮）| 还没有支出分类，点「＋ 新增」建一个 |

两个实现细节：

- **图表占位文字不支持换行，得自己画。** MPAndroidChart v3.1.0 的 `Chart.onDraw()` 是用**单次
  `drawText`** 画占位文字的，字符串里的换行符会被当普通字符（它不像 TextView、JavaFX Label 会解析换行），
  所以写 `\n` 不会换行，换成逗号只会得到更长的一行。
  三个图表因此各包了一层（`TrendLineChart` / `TrendBarChart` / `TrendPieChart`），
  把文案交给共用的 `NoDataTip`：按换行符切行、逐行居中绘制。
  两个坑：`Chart.mNoDataText` 是**私有字段且没有 getter**，所以要自己存一份文案；
  设文案时还要把库那份置空（`super.setNoDataText("")`），否则库会再画一遍单行文字叠在上面。
- **多行占位要收字号。** `Chart` 上没有 `setNoDataTextSize`，占位文字用内部的 `mInfoPaint` 画，
  只能 `chart.getPaint(Chart.PAINT_INFO).setTextSize(...)`。默认 12sp 是给单行设计的，两行会挤。
- **电脑端的空状态在 `StackPane` 里浮在图表上方**（`pieEmpty`/`lineEmpty`/`monthTrendEmpty`/
  `budgetEmpty`/`heatEmpty`），JavaFX 能解析换行，FXML 里用 `&#10;`。

## 饼图标签反色：不能用固定亮度阈值

标签颜色要按扇区底色选反色。早先的实现是「感知亮度 > 0.55 就用深色」，在中间调上会判错：
中灰 `#8C8C8C` 的加权亮度约 0.42，被判给**浅色**文字 —— 白字压浅灰扇区，对比度只有 3.36:1，
实际就是看不见；而同一块灰在浅色基底里换成更浅的取值（如 `#EDEDED`）时又该用深字。
一个固定阈值夹在两端之间，必有一头是错的。

改成**直接比较「黑字」和「白字」两种候选的实际对比度**，谁高用谁，就没有临界问题。
对比度用 W3C 公式（sRGB 反伽马 → 相对亮度 → `(亮+0.05)/(暗+0.05)`），比亮度加权更贴近肉眼。

两个基底的全部调色板颜色按新逻辑验算过，最低 4.80:1（浅色的 `#D64545` 红），其余都在 5.3:1 以上，
都过 WCAG AA 的 4.5:1 门槛。

## 环外标签的反色要按「页面底色」算，不是按扇区色

这是同一处的第二个坑，而且更隐蔽：**环外标签画在页面背景上，不画在扇区上**
（有引线把它拉出去）。早先的实现沿用扇区反色，于是浅色模式下深灰扇区算出白字，
白字画在白页面上直接消失 —— 表现是「那一块既没有标签也没有金额」，而引线还在，
很容易误判成「标签没绘制」。

正确的区分：

| 标签位置 | 文字压在哪 | 反色按谁算 | 描边 |
| --- | --- | --- | --- |
| 环内 | 扇区 | 扇区颜色 | `STROKE` 勾一圈与文字相反的轮廓 |
| 环外 | 页面背景 | **页面背景色** | `FILL` 用页面背景色垫底（等于底色，同时遮住穿过的引线）|

`PieSliceLabelRenderer` 因此多收一个构造参数 `labelBackgroundColor`（由 `ReportFragment`
传 `R.color.background`）。验算：浅色页面 `#F5F5F5` → 黑字 19.26:1，深色页面 `#0A0A0A` → 白字 19.80:1。

顺带说明：环外描边用实心垫底还有个好处 —— 标签被避让算法推开后，引线会从文字下方穿过，
垫底正好把它压掉。

另外，分类名的描边必须与文字**相反**：深字配浅描边、浅字配深描边。
两行文字分处不同基线，描边不会盖住金额那一行。

## 动效：机械反馈，不做柔和过渡

规范里对交互反馈的要求是「通过颜色变化或简单的填充/描边切换来体现，**避免复杂或花哨的动效**」。
所以动效按机械仪表的思路做：**快速、直线、可预期**，没有弹性回弹，也没有位移演出。

| 交互 | 手机端 | 电脑端 |
| --- | --- | --- |
| 按钮按下 | `backgroundTint` 状态选择器：酸橙绿填充 → 黑底 + 酸橙绿字，**瞬时反转** | `:pressed` 深色底 + 酸橙绿字；`:hover` 白底 + 1px 酸橙绿描边 |
| 底部导航 | 指示条 2dp 酸橙绿细线平移（180ms）；按下压暗到 0.55（160ms）；选中放大到 1.18（180ms）| 选中态酸橙绿填充 + 左侧 3px 黑刻度；悬停时给 1px 酸橙绿刻度作预告 |
| 页面切换 | 入场：自右 **40% 屏宽**滑入 + 淡入，340ms；退场：向左 18% 屏宽滑出 + 淡出，280ms | 内容淡入 + 自右 14px 滑入，280ms `EASE_OUT` |
| 图表 | 生长/扇开动画，280ms **线性** | —（JavaFX 图表本就静态）|

### 几个关键取舍

**去掉了 Material 的圆形水波纹。** 按钮和底部导航项的 `rippleColor` 都设成透明。
水波纹是圆形扩散、缓动偏柔和，与这套直角工业语言不搭；规范要求的也正是「填充/描边切换」。

但去掉之后底部导航**少了按压的即时反馈**，所以补了两层：

| 层 | 动的是什么 | 表达什么 |
| --- | --- | --- |
| 按压反馈 | 被按到的导航项 View 压暗到 0.55 再恢复（160ms）| 「按到了」 |
| 选中脉冲 | 选中的导航项 View 放大到 1.18 再回落（180ms）| 「刚刚点的是它」 |
| 指示条 | 2dp 酸橙绿细线平移到新位置（180ms）| 「现在选中是谁」 |

**必须动画导航项的 View，不能动画 MenuItem 的图标。** 图标的着色由 `itemIconTint` 控制，
给 drawable 做透明度动画不落地，还会波及共享同一 drawable 的其它项。
导航项 View 通过「菜单顺序对应子 View 顺序」拿到（Material 的 `NavigationBarItemView` 不设 tag）。

**位移量要够大才看得出来。** 一开始用 24dp / 300ms，实际观感几乎察觉不到 ——
位移太小会被淡入盖过去，看起来还是硬切。改成 40% 屏宽之后，「页面在横向移动」才被看见。
这不是夸张的转场：只有一次直线位移、没有回弹、没有缩放。

**入场用 `res/anim`（补间动画），不要用 `res/animator`（属性动画）。**
Fragment 动画的 API 本来就是 `Animation` 类型、作用在容器快照上；
属性动画版本的 `setCustomAnimations` 在部分版本上**不生效且不报错** ——
表现就是「动画写了、代码也对，但看起来还是硬切」，排查起来很费时间。
补间动画会在播完跳回原值，但入场视图随后就被销毁/重建，那一瞬看不见，所以这个代价无关紧要。

**退场动画能做到，靠的是容器快照。** `replace()` 会立刻销毁旧 Fragment 的视图，
但动画作用在快照上，快照的生命周期与视图无关，所以旧页面照样能滑出去。
用 Fragment 自身的 lifecycle 回调是拿不到已销毁视图的。
`setCustomAnimations(enter, exit)` 这个重载要 API 26+，低版本降级为无动画（不影响功能）。

**入场比退场长、位移也比退场大。** 入场是主角（340ms / 40% 屏宽），退场是让位（280ms / 18% 屏宽），
退场动作更轻才不会和入场抢注意力。两者方向相反（入场自右、退场往左），
形成「前进向右、后退向左」的一套方向语义。

**指示条要比页面切换快。** 指示条 180ms、页面切换 340ms：
指针先到位、内容随后展开，符合仪表的手感；两者一样长会觉得拖沓。

**按钮样式的父类不能换成 AppCompat。** 24 个描边按钮经由
`Widget.MaterialComponents.Button.OutlinedButton` → `Widget.App.Button` 这条链间接拿到圆角设置，
换掉父类会一起断掉。所以保留了 Material 父类，只把 `rippleColor` 置空、换 `backgroundTint`，
并通过 `materialButtonStyle` / `materialButtonOutlinedStyle` 两个主题属性让全部按钮统一生效。

**图表生长动画：不要用库自带的 `animateY`，改成自己驱动相位。**

MPAndroidChart 的 `animateY` 会创建 `ObjectAnimator` 去动画 `ChartAnimator.mPhaseY`，
渲染端也确实读这个相位（环形图渲染器 10 处、柱状图渲染器 6 处）。
但实测在真机上**不产生可见效果** —— 代码在跑、相位在变、重绘也在发生，就是看不到动画。

改成用 `ValueAnimator` 逐帧调 `setPhaseY` 并重绘，观感与库原本那套一致（环形图按数据扫开、
柱状图从底部长起），因为**渲染逻辑仍然是库自己的**，只是驱动源换成了可靠的一类：

```java
ValueAnimator va = ValueAnimator.ofFloat(0.02f, 1f);
va.setDuration(800);
va.setInterpolator(new DecelerateInterpolator());
va.addUpdateListener(a -> {
    chart.getAnimator().setPhaseY((float) a.getAnimatedValue());
    chart.invalidate();
});
va.start();
```

三个要点：相位起点取 `0.02` 而不是 `0`，避免整帧完全不可见导致闪烁；
必须 `post` 到布局完成后再启动（`onCreateView` 阶段视图还没测量）；
缓动用减速而不是库默认的线性 —— 相位由我们推进，减速收尾更自然。

**时长取 800ms，比常见的入场动画长得多，这是刻意的。** 环形图扫开覆盖整块画布，
是连续变化而不是位移，时长太短会被看成「一下就位」。实测下来：280ms 基本观察不到，
600ms 能辨认，800ms 有明显生长感又不至于让人等。其余动效（导航指示条 180ms、
页面推拉 300ms）仍是短时长，不受影响 —— 只有这类「大面积连续变化」需要更长的余量。

**注意 v3.1.0 的 API 是 `EasingFunction` 类型的静态字段（写 `Easing.Linear`），
不是 `EasingOption` 枚举** —— 后者是旧版写法，写了编译不过。

### 排查这类「动画看不到」的教训

这轮走了五轮弯路，根因是**把「叠加遮蔽」误判成「机制失效」**：

1. 一开始在库动画之上又叠了一套视图 `scaleY`，形变幅度更大，把库动画盖住了 ——
   看到的是挤压效果，误以为库动画在跑。
2. 用户说「没动画」后，不断更换机制，却没有先验证上一轮的机制**是否真的启动**。
   `ViewPropertyAnimator`、`Easing` 参数、`commitNow` 时序……逐个排除，都没命中。
3. 真正有效的两步：一是把动画时长临时拉到 700ms / 2500ms，让「有没有动」不再靠感觉；
   二是把状态打到界面上（如「重播 #2 view=true」）—— 日志只能证明代码被执行，
   证明不了动画在跑，这两件事必须分开验证。

**排查动画类问题时，先让效果「不可能看不见」，再区分「没启动」和「启动了但看不见」。**

两端的接入点都在容器层，避免逐个改页面：

- 手机端：`switchTo()` 里设置事务动画，五个页面自动生效。
- 电脑端：监听 `content` 这个 `StackPane` 的子节点列表，九处
  `content.getChildren().setAll(...)` 切换点都不用改。

## 自测清单

在真机上切系统深色，逐项过一遍：

1. 底部导航：选中酸橙绿、未选中为次级灰（不能发闷）。
2. 报表页：概览卡片、饼图扇区与标签（重点看金额是否糊在深色扇区上）、近 30 天折线、
   跨月趋势柱子（选中态）、预算进度、年度热力图（空格子不能是浅灰）。
3. 记账页：分类按钮未选中态文字可见、选中态仍是酸橙绿底黑字。
4. 流水页：支出红/收入绿提亮后是否清晰。
5. 对话框与日期选择器：底色与文字是否与应用一致（这是原来「混搭」最明显的地方）。
6. 状态栏图标：深色下应为浅色。
