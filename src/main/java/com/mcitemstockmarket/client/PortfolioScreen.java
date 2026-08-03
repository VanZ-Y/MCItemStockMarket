package com.mcitemstockmarket.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mcitemstockmarket.Config;
import com.mcitemstockmarket.data.PlayerAccount;
import com.mcitemstockmarket.data.Stock;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 持仓界面：以列表方式展示玩家持有的股票。
 * 每行显示：图标 + 股票名称 / 数量 / 购入单价 / 现单价 / 涨跌幅 / 变化总值。
 * 顶部显示账户余额与持仓汇总。
 *
 * 注意：1.21.10 的 GuiGraphics.drawString 仅当 ARGB.alpha(color)!=0 才绘制，
 *      所有文字颜色使用 0xFFRRGGBB；fill 的颜色本就带 alpha。
 */
public class PortfolioScreen extends Screen {
    private static final int PADDING = 8;
    private static final int ROW_HEIGHT = 24;
    private static final int HEADER_Y = 50;
    private static final int LIST_TOP = 64;
    // 各列宽度（表头与行共用，确保对齐）
    private static final int COL_CHG = 92;  // 变化总值
    private static final int COL_PCT = 70;  // 涨跌幅
    private static final int COL_CUR = 80;  // 现单价
    private static final int COL_BUY = 80;  // 购入单价

    private PortfolioList portfolioList;

    protected PortfolioScreen() {
        super(Component.translatable("mcitemstockmarket.gui.portfolio_title"));
    }

    @Override
    protected void init() {
        portfolioList = new PortfolioList();
        this.addRenderableWidget(portfolioList);
        rebuildList();
    }

    public void refreshList() {
        if (portfolioList != null) rebuildList();
    }

    private void rebuildList() {
        if (portfolioList == null) return;
        portfolioList.clearEntries();
        if (ClientData.ACCOUNT == null) return;

        Map<String, Integer> holdings = ClientData.ACCOUNT.getHoldings();
        Map<String, Double> avgPrices = ClientData.ACCOUNT.getAvgBuyPrices();

        List<PortfolioRow> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> e : holdings.entrySet()) {
            int qty = e.getValue();
            if (qty <= 0) continue;
            String fullName = e.getKey();
            double buyPrice = avgPrices.getOrDefault(fullName, 0.0);
            Stock stock = ClientData.STOCKS.get(fullName);
            double curPrice = stock != null ? stock.getPrice() : 0.0;
            boolean available = stock != null && !stock.isDelisted();
            rows.add(new PortfolioRow(fullName, stock, qty, buyPrice, curPrice, available));
        }
        // 按变化总值降序排（盈亏明显的在前）
        rows.sort(Comparator.comparingDouble((PortfolioRow r) -> r.totalChange).reversed());
        for (PortfolioRow r : rows) {
            portfolioList.addEntryPublic(new PortfolioEntry(r));
        }
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (super.keyPressed(keyEvent)) return true;
        InputConstants.Key k = InputConstants.getKey(keyEvent);
        if (keyEvent.key() == InputConstants.KEY_ESCAPE || ModKeyBindings.OPEN_MARKET.isActiveAndMatches(k)) {
            this.onClose();
            return true;
        }
        return false;
    }

    @Override
    public void render(GuiGraphics gg, int mx, int my, float partial) {
        super.render(gg, mx, my, partial);
        // 标题
        gg.drawCenteredString(this.font, Component.translatable("mcitemstockmarket.gui.portfolio_title"),
                this.width / 2, 6, 0xFFFFFFFF);

        String cur = Config.CURRENCY_NAME.get();
        if (ClientData.ACCOUNT != null) {
            PlayerAccount a = ClientData.ACCOUNT;
            // 汇总：余额 / 持仓市值 / 总盈亏
            double marketValue = 0.0;
            double costValue = 0.0;
            for (PortfolioEntry entry : new ArrayList<>(portfolioList.children())) {
                if (entry.row.available) marketValue += entry.row.curPrice * entry.row.qty;
                costValue += entry.row.buyPrice * entry.row.qty;
            }
            String balStr = String.format(Locale.US, "%s: %.2f %s",
                    Component.translatable("mcitemstockmarket.gui.balance").getString(), a.getBalance(), cur);
            gg.drawString(this.font, balStr, PADDING, 24, 0xFFFFFFAA);

            String mvStr = String.format(Locale.US, "%s: %.2f %s",
                    Component.translatable("mcitemstockmarket.gui.market_value").getString(), marketValue, cur);
            int mvX = (this.width - this.font.width(mvStr)) / 2;
            gg.drawString(this.font, mvStr, mvX, 24, 0xFFAAFFAA);

            double pnl = marketValue - costValue;
            String pnlStr = String.format(Locale.US, "%s: %s%.2f %s",
                    Component.translatable("mcitemstockmarket.gui.floating_pnl").getString(),
                    pnl >= 0 ? "+" : "", pnl, cur);
            int pnlColor = pnl >= 0 ? 0xFFFF6666 : 0xFF66FF66;
            int pnlX = this.width - PADDING - this.font.width(pnlStr);
            gg.drawString(this.font, pnlStr, pnlX, 24, pnlColor);

            // 列表表头
            drawHeader(gg);
        } else {
            gg.drawString(this.font, Component.translatable("mcitemstockmarket.gui.syncing"),
                    PADDING, 24, 0xFFFF8888);
        }
    }

    /** 列表表头：与每行列对齐（名称 / 数量 / 购入单价 / 现单价 / 涨跌幅 / 变化总值）。*/
    private void drawHeader(GuiGraphics gg) {
        Font font = this.font;
        int x = PADDING;
        int w = this.width - PADDING * 2 - 6; // 与 getRowWidth() 一致
        int right = x + w - 5;
        int y = HEADER_Y;
        int color = 0xFFCCCCCC;
        // 名称（左）
        gg.drawString(font, Component.translatable("mcitemstockmarket.gui.col_name"), x + 24, y, color);
        // 数量 / 购入单价 / 现单价 / 涨跌幅 / 变化总值（右对齐到各列，与行内一致）
        drawRight(gg, font, Component.translatable("mcitemstockmarket.gui.col_qty"),
                right - COL_CHG - COL_PCT - COL_CUR - COL_BUY, y, color);
        drawRight(gg, font, Component.translatable("mcitemstockmarket.gui.col_buy"),
                right - COL_CHG - COL_PCT - COL_CUR, y, color);
        drawRight(gg, font, Component.translatable("mcitemstockmarket.gui.col_cur"),
                right - COL_CHG - COL_PCT, y, color);
        drawRight(gg, font, Component.translatable("mcitemstockmarket.gui.col_chg"),
                right - COL_CHG, y, color);
        drawRight(gg, font, Component.translatable("mcitemstockmarket.gui.col_total"),
                right, y, color);
        // 表头分隔线（fill 使用 ARGB）
        gg.fill(x, y + 11, x + w, y + 12, 0xFF444444);
    }

    private void drawRight(GuiGraphics gg, Font font, Component text, int rightX, int y, int color) {
        gg.drawString(font, text, rightX - font.width(text), y, color);
    }

    private void drawRight(GuiGraphics gg, Font font, String s, int rightX, int y, int color) {
        gg.drawString(font, s, rightX - font.width(s), y, color);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(new StockMarketScreen());
    }

    // ================= 行数据 =================
    static class PortfolioRow {
        final String fullName;
        final Stock stock;       // 可能为 null（股票已退市移除）
        final int qty;
        final double buyPrice;   // 加权平均购入单价
        final double curPrice;   // 现单价（stock 为 null 时为 0）
        final boolean available; // 股票当前可交易
        final double changePct;  // 涨跌幅 (cur-buy)/buy
        final double totalChange;// 变化总值 (cur-buy)*qty

        PortfolioRow(String fullName, Stock stock, int qty, double buyPrice, double curPrice, boolean available) {
            this.fullName = fullName;
            this.stock = stock;
            this.qty = qty;
            this.buyPrice = buyPrice;
            this.curPrice = curPrice;
            this.available = available;
            this.changePct = buyPrice > 0 ? (curPrice - buyPrice) / buyPrice : 0.0;
            this.totalChange = (curPrice - buyPrice) * qty;
        }
    }

    // ================= List =================
    class PortfolioList extends ObjectSelectionList<PortfolioEntry> {
        PortfolioList() {
            super(PortfolioScreen.this.minecraft,
                    PortfolioScreen.this.width - PADDING * 2,
                    PortfolioScreen.this.height - LIST_TOP - PADDING,
                    LIST_TOP,
                    ROW_HEIGHT);
            this.setX(PADDING);
        }

        public void addEntryPublic(PortfolioEntry entry) {
            addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return this.width - 6;
        }
    }

    class PortfolioEntry extends ObjectSelectionList.Entry<PortfolioEntry> {
        final PortfolioRow row;

        PortfolioEntry(PortfolioRow row) { this.row = row; }

        @Override
        public Component getNarration() { return Component.literal(row.fullName); }

        @Override
        public void renderContent(GuiGraphics gg, int mx, int my, boolean hovered, float pt) {
            Font font = PortfolioScreen.this.font;
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();

            if (hovered) {
                gg.fill(x, y, x + w, y + ROW_HEIGHT, 0x40FFFFFF);
            }

            // 图标
            Item item = resolveItem();
            ItemStack stack = new ItemStack(item);
            gg.renderItem(stack, x + 3, y + 4);
            gg.renderItemDecorations(font, stack, x + 3, y + 4);

            String cur = Config.CURRENCY_NAME.get();
            int right = x + w - 5;

            // 变化总值
            String chgStr = String.format(Locale.US, "%s%.2f", row.totalChange >= 0 ? "+" : "", row.totalChange);
            drawRight(gg, font, chgStr, right, y + 8, row.totalChange >= 0 ? 0xFFFF6666 : 0xFF66FF66);

            // 涨跌幅
            String pctStr = (row.changePct >= 0 ? "+" : "") + String.format(Locale.US, "%.2f%%", row.changePct * 100);
            drawRight(gg, font, pctStr, right - COL_CHG, y + 8, row.changePct >= 0 ? 0xFFFF6666 : 0xFF66FF66);

            // 现单价
            String curStr = row.available
                    ? String.format(Locale.US, "%.2f", row.curPrice)
                    : "—";
            drawRight(gg, font, curStr, right - COL_CHG - COL_PCT, y + 8, row.available ? 0xFFFFFF66 : 0xFF888888);

            // 购入单价
            String buyStr = String.format(Locale.US, "%.2f", row.buyPrice);
            drawRight(gg, font, buyStr, right - COL_CHG - COL_PCT - COL_CUR, y + 8, 0xFFDDDDDD);

            // 数量
            String qtyStr = String.valueOf(row.qty);
            drawRight(gg, font, qtyStr, right - COL_CHG - COL_PCT - COL_CUR - COL_BUY, y + 8, 0xFFAAFFAA);

            // 名称（本地化显示名，截断避免与数量列重叠）
            int nameRight = right - COL_CHG - COL_PCT - COL_CUR - COL_BUY - 6;
            int nameMaxW = nameRight - (x + 24) - 4;
            String state = row.stock != null && row.stock.isDelisted()
                    ? Component.translatable("mcitemstockmarket.gui.state_delisted").getString()
                    : (row.stock != null && row.stock.isMomentum()
                            ? Component.translatable("mcitemstockmarket.gui.state_momentum").getString() : "");
            String dispName = row.stock != null
                    ? StockNameResolver.getDisplayName(row.stock)
                    : row.fullName;
            String nameTxt = state + dispName;
            nameTxt = font.plainSubstrByWidth(nameTxt, Math.max(40, nameMaxW));
            int nameColor = !row.available ? 0xFFAAAAAA : (row.stock != null && row.stock.isDelisted() ? 0xFFAAAAAA : 0xFFFFFFFF);
            gg.drawString(font, nameTxt, x + 24, y + 3, nameColor);
            // 物品ID 副标题
            String itemId = row.stock != null ? row.stock.getItemId() : "";
            if (!itemId.isEmpty()) {
                gg.drawString(font, itemId, x + 24, y + 14, 0xFF777777);
            }
        }

        private void drawRight(GuiGraphics gg, Font font, String s, int rightX, int y, int color) {
            gg.drawString(font, s, rightX - font.width(s), y, color);
        }

        private Item resolveItem() {
            String itemId = row.stock != null ? row.stock.getItemId() : "";
            if (itemId.isEmpty()) return Items.PAPER;
            try {
                Item it = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(itemId));
                if (it == null || it == Items.AIR) return Items.PAPER;
                return it;
            } catch (Exception e) { return Items.PAPER; }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
            // 点击持仓行可进入对应股票的买卖界面（仅当股票存在时）
            if (row.stock != null) {
                Minecraft.getInstance().setScreen(new StockDetailScreen(row.stock));
            }
            return true;
        }
    }
}
