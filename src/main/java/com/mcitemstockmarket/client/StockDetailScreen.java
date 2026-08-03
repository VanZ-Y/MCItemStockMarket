package com.mcitemstockmarket.client;

import java.util.List;
import java.util.Locale;

import com.mcitemstockmarket.Config;
import com.mcitemstockmarket.data.Stock;
import com.mcitemstockmarket.network.Payloads;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import org.joml.Matrix3x2f;

/**
 * 股票详情 / 交易界面。
 * 布局（自上而下）：
 *   ① 头部：物品图标 + 股票名称 + 状态 / 退市倒计时
 *   ② 图表上方：现价 + 1分钟 / 1天 / 1周 涨跌幅
 *   ③ 折线图（水平居中）
 *   ④ 图表下方：数量输入 + 周期切换
 *   ⑤ 操作按钮行：买入 卖出 兑入物品 兑出货币 取消委托 返回
 *   ⑥ 账户信息：持有股数 + 余额
 *
 * 注意：1.21.10 的 GuiGraphics.drawString 仅当 ARGB.alpha(color)!=0 才绘制，
 *      所有文字颜色使用 0xFFRRGGBB；fill 的颜色本就带 alpha。
 */
public class StockDetailScreen extends Screen {
    private Stock stock;
    private EditBox qtyBox;
    private CycleButton<String> rangeButton;
    private String range = "day"; // "minute"=20分钟K线 | "day"=最近1天折线 | "month"=最近1个月折线

    private int headerY = 6;
    private int priceLineY = 38;
    private int chartX, chartY, chartW, chartH;
    private int qtyRowY, btnRowY, acctY;

    protected StockDetailScreen(Stock stock) {
        super(stock.getDisplayNameComponent());
        this.stock = stock;
    }

    public void refresh() {
        if (stock != null && ClientData.STOCKS.containsKey(stock.getFullName())) {
            this.stock = ClientData.STOCKS.get(stock.getFullName());
        }
    }

    @Override
    protected void init() {
        int w = this.width;
        int h = this.height;

        // 自下而上预留控件空间
        acctY = h - 26;
        btnRowY = h - 54;
        qtyRowY = h - 82;
        int chartBottom = qtyRowY - 8;

        // 折线图区域（水平居中）
        chartW = Math.min(w - 40, 720);
        chartX = (w - chartW) / 2;
        chartY = priceLineY + 16;
        chartH = Math.max(100, chartBottom - chartY);

        // 数量输入框 + 周期切换（居中）
        int qtyW = 70, rangeW = 130;
        int ctrlTotal = qtyW + 8 + rangeW;
        int ctrlX = (w - ctrlTotal) / 2;
        qtyBox = new EditBox(this.font, ctrlX, qtyRowY, qtyW, 18, Component.translatable("mcitemstockmarket.gui.qty"));
        qtyBox.setMaxLength(8);
        qtyBox.setValue("1");
        qtyBox.setFilter(s -> s.isEmpty() || s.matches("\\d+"));
        this.addRenderableWidget(qtyBox);

        rangeButton = CycleButton.<String>builder(str -> Component.translatable(
                    str.equals("minute") ? "mcitemstockmarket.gui.period_minute"
                    : str.equals("day") ? "mcitemstockmarket.gui.period_day"
                    : "mcitemstockmarket.gui.period_month"))
                .withValues(List.of("minute", "day", "month"))
                .withInitialValue(range)
                .create(ctrlX + qtyW + 8, qtyRowY, rangeW, 18,
                        Component.translatable("mcitemstockmarket.gui.period"),
                        (btn, val) -> this.range = val);
        this.addRenderableWidget(rangeButton);

        // 操作按钮行：6 个按钮，等宽居中
        int gap = 6;
        int btnCount = 6;
        int btnH = 20;
        int btnW = Math.min(96, (w - PADDING() * 2 - gap * (btnCount - 1)) / btnCount);
        int totalW = btnW * btnCount + gap * (btnCount - 1);
        int bx = (w - totalW) / 2;

        this.addRenderableWidget(Button.builder(Component.translatable("mcitemstockmarket.gui.buy"), b -> submitOrder(true)).bounds(bx, btnRowY, btnW, btnH).build()); bx += btnW + gap;
        this.addRenderableWidget(Button.builder(Component.translatable("mcitemstockmarket.gui.sell"), b -> submitOrder(false)).bounds(bx, btnRowY, btnW, btnH).build()); bx += btnW + gap;
        this.addRenderableWidget(Button.builder(Component.translatable("mcitemstockmarket.gui.exchange_item"), b -> exchange(true)).bounds(bx, btnRowY, btnW, btnH).build()); bx += btnW + gap;
        this.addRenderableWidget(Button.builder(Component.translatable("mcitemstockmarket.gui.exchange_currency"), b -> exchange(false)).bounds(bx, btnRowY, btnW, btnH).build()); bx += btnW + gap;
        this.addRenderableWidget(Button.builder(Component.translatable("mcitemstockmarket.gui.cancel_orders"), b -> cancelOrders()).bounds(bx, btnRowY, btnW, btnH).build()); bx += btnW + gap;
        this.addRenderableWidget(Button.builder(Component.translatable("mcitemstockmarket.gui.back"), b -> back()).bounds(bx, btnRowY, btnW, btnH).build());
    }

    private int PADDING() { return 8; }

    private int parseQty() {
        try {
            int n = Integer.parseInt(qtyBox.getValue());
            return Math.max(0, n);
        } catch (Exception e) { return 0; }
    }

    private void submitOrder(boolean isBuy) {
        int q = parseQty();
        if (q <= 0) { notify("数量必须为正整数"); return; }
        ClientPacketDistributor.sendToServer(new Payloads.ServerboundSubmitOrder(isBuy, stock.getFullName(), q));
        notify((isBuy ? "已提交买入委托：" : "已提交卖出委托：") + stock.getFullName() + " x" + q);
    }

    private void exchange(boolean buyItem) {
        int q = parseQty();
        if (q <= 0) { notify("数量必须为正整数"); return; }
        ClientPacketDistributor.sendToServer(new Payloads.ServerboundExchange(buyItem, stock.getFullName(), q));
        notify(buyItem ? "请求兑换物品 x" + q : "请求兑出货币 x" + q);
    }

    private void cancelOrders() {
        ClientPacketDistributor.sendToServer(new Payloads.ServerboundCancelOrders());
        notify("已请求取消所有待成交委托");
    }

    private void back() {
        Minecraft.getInstance().setScreen(new StockMarketScreen());
    }

    private void notify(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal(s), false);
    }

    // ================= Rendering =================
    @Override
    public void render(GuiGraphics gg, int mx, int my, float pt) {
        // 1.21.10：renderWithTooltipAndSubtitles 已在 render() 前自动调用 renderBackground()
        super.render(gg, mx, my, pt);

        // 刷新股票缓存版本
        if (stock != null && ClientData.STOCKS.containsKey(stock.getFullName())) {
            this.stock = ClientData.STOCKS.get(stock.getFullName());
        }

        drawHeader(gg);
        drawPriceLine(gg);
        drawChart(gg);
        drawAccountInfo(gg);
    }

    private void drawHeader(GuiGraphics gg) {
        Font font = this.font;
        Item it = resolveItem();
        ItemStack stack = new ItemStack(it);
        gg.renderItem(stack, chartX, headerY);
        gg.renderItemDecorations(font, stack, chartX, headerY);

        String state = stock.isDelisted()
                ? Component.translatable("mcitemstockmarket.gui.delisted_tag").getString()
                : stock.isMomentum()
                        ? Component.translatable("mcitemstockmarket.gui.momentum_tag").getString()
                                + Config.MOMENTUM_PRICE_MULTIPLIER.get() + "]"
                        : "";
        String title = StockNameResolver.getDisplayName(stock) + state;
        gg.drawString(font, title, chartX + 22, headerY + 2, stock.isDelisted() ? 0xFFAAAAAA : 0xFFFFFFFF);
        gg.drawString(font, stock.getItemId(), chartX + 22, headerY + 13, 0xFF888888);

        if (stock.isDelisted()) {
            long remain = stock.getRelaunchAt() - System.currentTimeMillis();
            if (remain > 0) {
                String tmpl = Component.translatable("mcitemstockmarket.gui.relaunch_countdown").getString();
                String msg = String.format(tmpl, remain / 1000);
                gg.drawString(font, msg, chartX + 22, headerY + 24, 0xFFFFFF88);
            }
        }
    }

    /** 图表上方：现价 + 当前持有数量 + 涨跌幅（1分钟 / 1天 / 1月）*/
    private void drawPriceLine(GuiGraphics gg) {
        Font font = this.font;
        String cur = Config.CURRENCY_NAME.get();
        float p1 = stock.getDisplayP1();
        float p24 = stock.getDisplayP24();
        float pMonth = stock.getDisplayPMonth();

        // 左侧：现价
        String priceStr = String.format(Locale.US, "%s: %.2f %s",
                Component.translatable("mcitemstockmarket.gui.now_price").getString(), stock.getPrice(), cur);
        gg.drawString(font, priceStr, chartX, priceLineY, 0xFFFFFF66);

        // 现价右侧：当前持有数量
        if (ClientData.ACCOUNT != null) {
            int holding = ClientData.ACCOUNT.getHolding(stock.getFullName());
            String holdStr = String.format(Locale.US, "  |  %s: %d", Component.translatable("mcitemstockmarket.gui.holding_qty").getString(), holding);
            gg.drawString(font, holdStr, chartX + font.width(priceStr), priceLineY, 0xFFAAFFAA);
        }

        // 右侧：三档涨跌幅（右对齐到图表右边）
        int right = chartX + chartW;
        String cMonth = String.format(Locale.US, "1M %s%.2f%%", pMonth >= 0 ? "+" : "", pMonth * 100);
        String c24 = String.format(Locale.US, "1d %s%.2f%%", p24 >= 0 ? "+" : "", p24 * 100);
        String c1 = String.format(Locale.US, "1m %s%.2f%%", p1 >= 0 ? "+" : "", p1 * 100);
        drawRight(gg, font, cMonth, right, priceLineY, pMonth >= 0 ? 0xFFFF6666 : 0xFF66FF66);
        drawRight(gg, font, c24, right - font.width(cMonth) - 16, priceLineY, p24 >= 0 ? 0xFFFF6666 : 0xFF66FF66);
        drawRight(gg, font, c1, right - font.width(cMonth) - font.width(c24) - 32, priceLineY, p1 >= 0 ? 0xFFFF6666 : 0xFF66FF66);
    }

    private void drawRight(GuiGraphics gg, Font font, String s, int rightX, int y, int color) {
        gg.drawString(font, s, rightX - font.width(s), y, color);
    }

    private void drawAccountInfo(GuiGraphics gg) {
        Font font = this.font;
        String cur = Config.CURRENCY_NAME.get();
        if (ClientData.ACCOUNT != null) {
            int holding = ClientData.ACCOUNT.getHolding(stock.getFullName());
            String tmpl = Component.translatable("mcitemstockmarket.gui.hold_balance").getString();
            String line = String.format(Locale.US, tmpl, holding, ClientData.ACCOUNT.getBalance(), cur);
            int x = (this.width - font.width(line)) / 2;
            gg.drawString(font, line, x, acctY, 0xFFEEFFEE);
        } else {
            Component s = Component.translatable("mcitemstockmarket.gui.account_syncing");
            int x = (this.width - font.width(s)) / 2;
            gg.drawString(font, s, x, acctY, 0xFFFF8888);
        }
    }

    private Item resolveItem() {
        try {
            Item it = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(stock.getItemId()));
            if (it == null || it == Items.AIR) return Items.PAPER;
            return it;
        } catch (Exception e) { return Items.PAPER; }
    }

    // ---------- 图表（1.21.10 RenderState 批量渲染）----------
    // minute = 最近 20 分钟（1 游戏天）K 线图；day = 最近 1 天折线图；month = 最近 1 个月折线图
    private void drawChart(GuiGraphics gg) {
        Font font = this.font;
        int x = chartX, y = chartY, w = chartW, h = chartH;

        // 背景框（2 个 quad；与图表同管线、同无纹理，会在 GuiRenderer 中合批为同一次绘制）
        gg.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0xFF202020);
        gg.fill(x, y, x + w, y + h, 0xFF0A0A0A);

        switch (range) {
            case "minute" -> drawKLineChart(gg);
            case "month" -> drawLineChart(gg,
                    stock.getMonthlyHistoryCopy(), 30, 0L,
                    Component.translatable("mcitemstockmarket.gui.period_label_month"));
            default -> drawLineChart(gg,
                    stock.getPriceHistoryCopy(), 24, System.currentTimeMillis() - 24L * 50_000L,
                    Component.translatable("mcitemstockmarket.gui.period_label_day"));
        }
    }

    /** 折线图：从 hist 中取最近 points 个点绘制折线，cutoff 过滤（0 表示不过滤）。*/
    private void drawLineChart(GuiGraphics gg, List<long[]> hist, int points, long cutoff, Component label) {
        Font font = this.font;
        int x = chartX, y = chartY, w = chartW, h = chartH;
        if (hist == null || hist.isEmpty()) {
            gg.drawCenteredString(font, Component.translatable("mcitemstockmarket.gui.no_history"),
                    x + w / 2, y + h / 2 - 4, 0xFF888888);
            return;
        }

        int len = Math.min(points, hist.size());
        double[] ys = new double[len];
        long[] xs = new long[len];
        int startIdx = Math.max(0, hist.size() - len);
        for (int i = 0; i < len; i++) {
            long[] p = hist.get(startIdx + i);
            xs[i] = p[0];
            ys[i] = Double.longBitsToDouble(p[1]);
        }

        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        int count = 0;
        for (int i = 0; i < len; i++) {
            if (xs[i] < cutoff) continue;
            if (ys[i] < minY) minY = ys[i];
            if (ys[i] > maxY) maxY = ys[i];
            count++;
        }
        if (count <= 0) {
            gg.drawCenteredString(font, Component.translatable("mcitemstockmarket.gui.no_period_data"),
                    x + w / 2, y + h / 2 - 4, 0xFF888888);
            return;
        }
        if (minY == maxY) { minY *= 0.9; maxY *= 1.1; }
        double span = maxY - minY;
        if (span <= 0) span = 1.0; // 防止全 0 价格（跟风股崩盘归零）导致除零

        // 纵轴标尺
        gg.drawString(font, String.format(Locale.US, "%.2f", maxY), x + 3, y + 2, 0xFFDDDDDD);
        gg.drawString(font, String.format(Locale.US, "%.2f", minY), x + 3, y + h - 11, 0xFFDDDDDD);

        long minX = xs[Math.max(0, len - count)];
        long maxX = xs[len - 1];
        long xSpan = Math.max(1, maxX - minX);

        // 计算折线屏幕坐标点与每段颜色（红涨绿跌）
        int[] pxs = new int[count];
        int[] pys = new int[count];
        int[] segColors = new int[count - 1];
        int idx = 0;
        double prevY = Double.NaN;
        for (int i = 0; i < len; i++) {
            if (xs[i] < cutoff) continue;
            double xf = (double) (xs[i] - minX) / xSpan;
            double yf = (ys[i] - minY) / span;
            pxs[idx] = x + (int) (xf * (w - 1));
            pys[idx] = y + h - 1 - (int) (yf * (h - 1));
            if (idx > 0) {
                // 与原有逻辑一致：涨（>=前值）红，跌绿
                segColors[idx - 1] = !Double.isNaN(prevY) && ys[i] < prevY ? 0xFF66FF66 : 0xFFFF6666;
            }
            prevY = ys[i];
            idx++;
        }

        // 批量提交：整条折线 + 终点标记作为单个 GUI 元素（一次顶点提交、一次绘制）
        gg.submitGuiElementRenderState(new ChartRenderState(gg.pose(), x, y, w, h, pxs, pys, segColors));
        gg.drawString(font, label, x + w - font.width(label) - 4, y + h + 4, 0xFFAAAAAA);
    }

    /**
     * 最近 20 分钟（1 游戏天 = 24 游戏时）K 线图。
     * 每根 K 线 = 1 游戏时（50 秒现实），开盘价 = 该小时起点价，收盘价 = 该小时终点价，
     * 最高/最低 = 两者取大/取小（模组每 50 秒采样一次，故影线长度为 0，柱体即该小时涨跌）。
     * 红涨绿跌，与折线图配色一致。
     */
    private void drawKLineChart(GuiGraphics gg) {
        Font font = this.font;
        int x = chartX, y = chartY, w = chartW, h = chartH;
        List<long[]> hist = stock.getPriceHistoryCopy();
        if (hist == null || hist.size() < 2) {
            gg.drawCenteredString(font, Component.translatable("mcitemstockmarket.gui.no_history"),
                    x + w / 2, y + h / 2 - 4, 0xFF888888);
            return;
        }

        // 取最近 25 个样本（每 50 秒一个），构成 24 根 K 线 ≈ 20 分钟窗口
        int startIdx = Math.max(0, hist.size() - 25);
        int n = hist.size() - startIdx;  // 样本数
        int candles = n - 1;             // K 线数
        double[] opens = new double[candles];
        double[] closes = new double[candles];
        double[] highs = new double[candles];
        double[] lows = new double[candles];
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (int i = 0; i < candles; i++) {
            double o = Double.longBitsToDouble(hist.get(startIdx + i)[1]);
            double c = Double.longBitsToDouble(hist.get(startIdx + i + 1)[1]);
            opens[i] = o;
            closes[i] = c;
            highs[i] = Math.max(o, c);
            lows[i] = Math.min(o, c);
            if (lows[i] < minY) minY = lows[i];
            if (highs[i] > maxY) maxY = highs[i];
        }
        if (minY == maxY) { minY *= 0.9; maxY *= 1.1; }
        double span = maxY - minY;
        if (span <= 0) span = 1.0;

        // 纵轴标尺
        gg.drawString(font, String.format(Locale.US, "%.2f", maxY), x + 3, y + 2, 0xFFDDDDDD);
        gg.drawString(font, String.format(Locale.US, "%.2f", minY), x + 3, y + h - 11, 0xFFDDDDDD);

        // 每根 K 线的屏幕坐标（柱体中心 x、开盘/收盘/最高/最低 y）
        int[] cx = new int[candles];
        int[] cyOpen = new int[candles];
        int[] cyClose = new int[candles];
        int[] cyHigh = new int[candles];
        int[] cyLow = new int[candles];
        int[] colors = new int[candles];
        double candleW = (double) w / candles;
        for (int i = 0; i < candles; i++) {
            cx[i] = x + (int) ((i + 0.5) * candleW);
            cyHigh[i] = y + h - 1 - (int) (((highs[i] - minY) / span) * (h - 1));
            cyLow[i] = y + h - 1 - (int) (((lows[i] - minY) / span) * (h - 1));
            cyOpen[i] = y + h - 1 - (int) (((opens[i] - minY) / span) * (h - 1));
            cyClose[i] = y + h - 1 - (int) (((closes[i] - minY) / span) * (h - 1));
            colors[i] = closes[i] >= opens[i] ? 0xFFFF6666 : 0xFF66FF66; // 红涨绿跌
        }

        // 批量提交：所有 K 线作为一个 GUI 元素（一次顶点提交、一次绘制）
        gg.submitGuiElementRenderState(new KLineRenderState(gg.pose(), x, y, w, h,
                cx, cyOpen, cyClose, cyHigh, cyLow, colors));

        Component label = Component.translatable("mcitemstockmarket.gui.period_label_minute");
        gg.drawString(font, label, x + w - font.width(label) - 4, y + h + 4, 0xFFAAAAAA);
    }

    /**
     * 批量渲染的折线图元素。
     * 实现 GuiElementRenderState：整条折线只提交一次顶点，由 GuiRenderer 在 GUI 绘制阶段
     * 一次性上传绘制，替代原先逐像素 gg.fill 的数百次提交（约 700 个渲染状态 → 1 个）。
     * 使用 RenderPipelines.GUI（POSITION_COLOR + QUADS），与普通 GUI 矩形同管线合批。
     */
    private static final class ChartRenderState implements GuiElementRenderState {
        private final Matrix3x2f pose;   // 提交时 GUI 变换（本界面为恒等）
        private final int x, y, w, h;    // 图表区域
        private final ScreenRectangle bounds;
        private final int[] px, py;      // 折线屏幕坐标点
        private final int[] segColors;   // 每段颜色（长度 = px.length - 1）

        ChartRenderState(Matrix3x2f pose, int x, int y, int w, int h, int[] px, int[] py, int[] segColors) {
            this.pose = pose;
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.px = px; this.py = py; this.segColors = segColors;
            this.bounds = new ScreenRectangle(x - 2, y - 2, w + 4, h + 4);
        }

        @Override
        public void buildVertices(VertexConsumer consumer) {
            int n = px.length;
            // 折线：每段一个 2px 宽的四边形（沿线段方向延伸 1px 覆盖折角间隙）
            for (int i = 0; i < n - 1; i++) {
                segment(consumer, px[i], py[i], px[i + 1], py[i + 1], segColors[i], 1.0f);
            }
            // 终点标记（3x3 实心方块，与原逻辑一致）
            if (n > 0) {
                quad(consumer, px[n - 1] - 1, py[n - 1] - 1, px[n - 1] + 2, py[n - 1] + 2, 0xFFFFFF88);
            }
        }

        private void quad(VertexConsumer c, int x0, int y0, int x1, int y1, int color) {
            c.addVertexWith2DPose(pose, x0, y0).setColor(color);
            c.addVertexWith2DPose(pose, x0, y1).setColor(color);
            c.addVertexWith2DPose(pose, x1, y1).setColor(color);
            c.addVertexWith2DPose(pose, x1, y0).setColor(color);
        }

        /** 以 (x1,y1)-(x2,y2) 为中心绘制一条宽 halfThickness*2 的线段四边形。*/
        private void segment(VertexConsumer c, int x1, int y1, int x2, int y2, int color, float halfThickness) {
            float dx = x2 - x1, dy = y2 - y1;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 0.001f) { // 零长度段：退化为一个点
                quad(c, x1 - 1, y1 - 1, x1 + 2, y1 + 2, color);
                return;
            }
            float ux = dx / len, uy = dy / len;
            float nx = -uy, ny = ux;   // 线段垂直方向（单位向量）
            float ex = ux, ey = uy;    // 沿线段方向的 1px 延伸
            float xa = x1 - ex + nx * halfThickness, ya = y1 - ey + ny * halfThickness;
            float xb = x1 - ex - nx * halfThickness, yb = y1 - ey - ny * halfThickness;
            float xc = x2 + ex - nx * halfThickness, yc = y2 + ey - ny * halfThickness;
            float xd = x2 + ex + nx * halfThickness, yd = y2 + ey + ny * halfThickness;
            // 注意顶点绕序：RenderPipelines.GUI 默认开启背面剔除，
            // 必须与 quad()/vanilla 矩形（左上→左下→右下→右上）保持一致，否则线段会被剔除而不显示。
            c.addVertexWith2DPose(pose, xb, yb).setColor(color);   // 起点 -perp
            c.addVertexWith2DPose(pose, xa, ya).setColor(color);   // 起点 +perp
            c.addVertexWith2DPose(pose, xd, yd).setColor(color);   // 终点 +perp
            c.addVertexWith2DPose(pose, xc, yc).setColor(color);   // 终点 -perp
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public ScreenRectangle scissorArea() {
            return null;
        }

        @Override
        public ScreenRectangle bounds() {
            return bounds;
        }
    }

    /**
     * 批量渲染的 K 线（蜡烛图）元素。
     * 每根 K 线 = 影线（最高-最低，2px 宽竖线）+ 柱体（开盘-收盘）。
     * 顶点绕序与 quad()/vanilla 矩形一致（左上→左下→右下→右上），避免被背面剔除。
     */
    private static final class KLineRenderState implements GuiElementRenderState {
        private final Matrix3x2f pose;   // 提交时 GUI 变换（本界面为恒等）
        private final int x, y, w, h;    // 图表区域
        private final ScreenRectangle bounds;
        private final int[] cx;          // 每根 K 线中心 x
        private final int[] cyOpen, cyClose, cyHigh, cyLow;  // 开盘/收盘/最高/最低 y
        private final int[] colors;      // 每根 K 线颜色（红涨绿跌）

        KLineRenderState(Matrix3x2f pose, int x, int y, int w, int h,
                         int[] cx, int[] cyOpen, int[] cyClose, int[] cyHigh, int[] cyLow, int[] colors) {
            this.pose = pose;
            this.x = x; this.y = y; this.w = w; this.h = h;
            this.cx = cx;
            this.cyOpen = cyOpen; this.cyClose = cyClose;
            this.cyHigh = cyHigh; this.cyLow = cyLow;
            this.colors = colors;
            this.bounds = new ScreenRectangle(x - 2, y - 2, w + 4, h + 4);
        }

        @Override
        public void buildVertices(VertexConsumer consumer) {
            int bodyHalf = Math.max(1, (int) ((double) w / Math.max(1, cx.length) * 0.2));
            for (int i = 0; i < cx.length; i++) {
                int color = colors[i];
                int x0 = cx[i];
                // 影线：最高-最低（2px 宽竖线）
                rect(consumer, x0 - 1, Math.min(cyHigh[i], cyLow[i]), x0 + 1, Math.max(cyHigh[i], cyLow[i]), color);
                // 柱体：开盘-收盘（最低 2px 高，十字星也可见）
                int bodyTop = Math.min(cyOpen[i], cyClose[i]);
                int bodyBot = Math.max(cyOpen[i], cyClose[i]);
                if (bodyBot - bodyTop < 2) bodyBot = bodyTop + 2;
                rect(consumer, x0 - bodyHalf, bodyTop, x0 + bodyHalf, bodyBot, color);
            }
        }

        private void rect(VertexConsumer c, int x0, int y0, int x1, int y1, int color) {
            c.addVertexWith2DPose(pose, x0, y0).setColor(color);
            c.addVertexWith2DPose(pose, x0, y1).setColor(color);
            c.addVertexWith2DPose(pose, x1, y1).setColor(color);
            c.addVertexWith2DPose(pose, x1, y0).setColor(color);
        }

        @Override
        public RenderPipeline pipeline() {
            return RenderPipelines.GUI;
        }

        @Override
        public TextureSetup textureSetup() {
            return TextureSetup.noTexture();
        }

        @Override
        public ScreenRectangle scissorArea() {
            return null;
        }

        @Override
        public ScreenRectangle bounds() {
            return bounds;
        }
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (super.keyPressed(keyEvent)) return true;
        if (keyEvent.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) { this.onClose(); return true; }
        return false;
    }

    @Override public void onClose() {
        Minecraft.getInstance().setScreen(new StockMarketScreen());
    }
}
