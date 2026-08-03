package com.mcitemstockmarket.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.mcitemstockmarket.Config;
import com.mcitemstockmarket.data.Stock;
import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
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
 * 股市主界面（选股列表）：
 *  顶部：标题 + 搜索框 + 余额信息
 *  主体：可滚动的股票列表，每行显示 图标/名称/物品ID/现价/1分·1天·1月涨跌幅
 *  点击某行进入详情界面。
 *
 * 注意：1.21.10 的 GuiGraphics.drawString 仅当 ARGB.alpha(color)!=0 才绘制，
 *      因此所有文字颜色必须带 0xFF alpha（即 0xFFRRGGBB），否则不可见。
 */
public class StockMarketScreen extends Screen {
    private static final int PADDING = 8;
    private static final int ROW_HEIGHT = 24;
    private static final int SEARCH_Y = 22;
    private static final int HEADER_Y = 50;
    private static final int LIST_TOP = 64;
    // 每档涨跌幅列宽（与行内渲染保持一致）
    private static final int COL_W = 62;

    private StockList stockList;
    private EditBox searchBox;
    private String lastFilter = "";
    // 界面级滚动条拖动状态（由本界面直接处理，不依赖子列表的焦点/拖拽状态链）
    private boolean scrollbarDragging = false;

    protected StockMarketScreen() {
        super(Component.literal("股市 - MCItemStockMarket"));
    }

    @Override
    protected void init() {
        int w = this.width;
        // 右侧预留持仓按钮宽度，避免与搜索框重叠
        int portfolioBtnW = 80;
        int searchW = Math.min(360, w - PADDING * 2 - portfolioBtnW - 12);
        searchBox = new EditBox(this.font, (w - searchW - portfolioBtnW - 12) / 2, SEARCH_Y, searchW, 18,
                Component.translatable("mcitemstockmarket.gui.search_hint"));
        searchBox.setResponder(this::onSearchChanged);
        searchBox.setMaxLength(40);
        searchBox.setValue(lastFilter);
        this.addRenderableWidget(searchBox);

        // “我的持仓”按钮：打开持仓界面
        int pBtnX = searchBox.getX() + searchW + 12;
        this.addRenderableWidget(Button.builder(Component.translatable("mcitemstockmarket.gui.portfolio_btn"),
                b -> Minecraft.getInstance().setScreen(new PortfolioScreen()))
                .bounds(pBtnX, SEARCH_Y, portfolioBtnW, 18).build());

        stockList = new StockList();
        // 关键：必须用 addRenderableWidget，列表才会被渲染（addWidget 只注册事件不渲染）
        this.addRenderableWidget(stockList);
        rebuildList();
    }

    private void onSearchChanged(String s) {
        lastFilter = s;
        rebuildList();
    }

    public void refreshList() {
        if (stockList != null) rebuildList();
    }

    public void refreshAccount() {
        // 标题栏实时读取，无需操作
    }

    // ================= 滚动条拖动（界面级直接处理） =================
    // 1.21.10 中 vanilla 的 AbstractScrollArea.updateScrolling 无调用方、自身滚动条不可拖；
    // 此处在本界面覆写鼠标事件，点中滚动条后由本界面接管拖动，绕开子组件焦点/拖拽状态链。
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean isDoubleClick) {
        if (event.button() == 0 && stockList != null && stockList.isScrollbarHit(event.x(), event.y())) {
            scrollbarDragging = true;
            stockList.setScrollbarDragging(true);
            stockList.setScrollFromMouseY(event.y());
            this.setFocused(stockList);
            this.setDragging(true);
            return true;
        }
        return super.mouseClicked(event, isDoubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        if (scrollbarDragging && stockList != null) {
            stockList.setScrollFromMouseY(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (scrollbarDragging) {
            scrollbarDragging = false;
            if (stockList != null) stockList.setScrollbarDragging(false);
            this.setDragging(false);
            return true;
        }
        return super.mouseReleased(event);
    }

    private void rebuildList() {
        if (stockList == null) return;
        stockList.clearEntries();
        String filter = lastFilter == null ? "" : lastFilter.trim().toLowerCase(Locale.ROOT);
        List<Stock> list = new ArrayList<>(ClientData.STOCKS_ORDERED);
        // 按物品本地化名排序（退市的排到末尾），次级按股票全名稳定排序
        list.sort(Comparator.comparing(Stock::isDelisted)
                .thenComparing(Comparator.comparing(StockNameResolver::getSortKey, String.CASE_INSENSITIVE_ORDER))
                .thenComparing(Stock::getFullName));
        for (Stock s : list) {
            if (!filter.isEmpty()) {
                String t = (StockNameResolver.getDisplayName(s) + " " + s.getFullName() + " " + s.getItemId())
                        .toLowerCase(Locale.ROOT);
                if (!t.contains(filter)) continue;
            }
            stockList.addEntryPublic(new StockEntry(s));
        }
    }

    @Override
    public boolean keyPressed(KeyEvent keyEvent) {
        if (super.keyPressed(keyEvent)) return true;
        if (searchBox.isFocused()) return false; // 允许搜索框输入
        InputConstants.Key k = InputConstants.getKey(keyEvent);
        if (keyEvent.key() == InputConstants.KEY_ESCAPE || ModKeyBindings.OPEN_MARKET.isActiveAndMatches(k)) {
            this.onClose();
            return true;
        }
        return false;
    }

    @Override
    public void render(GuiGraphics gg, int mx, int my, float partial) {
        // 1.21.10：renderWithTooltipAndSubtitles 已在 render() 前自动调用 renderBackground()，此处不可重复调用
        super.render(gg, mx, my, partial);
        // 标题
        gg.drawCenteredString(this.font, Component.translatable("mcitemstockmarket.gui.title"),
                this.width / 2, 6, 0xFFFFFFFF);
        // 余额信息
        if (ClientData.ACCOUNT != null) {
            String bal = String.format(Locale.US, "%s: %.2f %s  |  %s: %d",
                    Component.translatable("mcitemstockmarket.gui.balance").getString(),
                    ClientData.ACCOUNT.getBalance(), Config.CURRENCY_NAME.get(),
                    Component.translatable("mcitemstockmarket.gui.stocks_count").getString(),
                    ClientData.STOCKS.size());
            gg.drawString(this.font, bal, PADDING, 42, 0xFFFFFFAA);
            if (!ClientData.ACCOUNT.getHoldings().isEmpty()) {
                String holdStr = Component.translatable("mcitemstockmarket.gui.holding_types").getString()
                        + " " + ClientData.ACCOUNT.getHoldings().size();
                gg.drawString(this.font, holdStr, this.width - PADDING - this.font.width(holdStr), 42, 0xFFAAFFAA);
            }
        } else {
            gg.drawString(this.font, Component.translatable("mcitemstockmarket.gui.syncing"),
                    PADDING, 42, 0xFFFF8888);
        }
        // 列表表头
        drawHeader(gg);
    }

    /** 选股列表表头：与每行列对齐（名称 / 现价 / 1分 / 1天 / 1月）。*/
    private void drawHeader(GuiGraphics gg) {
        Font font = this.font;
        int x = PADDING;
        int w = this.width - PADDING * 2 - 6; // 与 getRowWidth() 一致
        int right = x + w - 5;
        int y = HEADER_Y;
        int color = 0xFFCCCCCC;
        // 名称（左）
        gg.drawString(font, Component.translatable("mcitemstockmarket.gui.col_name"), x + 24, y, color);
        // 现价 / 1分 / 1天 / 1月（右对齐到各列）
        drawRight(gg, font, Component.translatable("mcitemstockmarket.gui.col_price"), right - 3 * COL_W, y, color);
        drawRight(gg, font, Component.translatable("mcitemstockmarket.gui.col_chg1"), right - 2 * COL_W, y, color);
        drawRight(gg, font, Component.translatable("mcitemstockmarket.gui.col_chg24"), right - COL_W, y, color);
        drawRight(gg, font, Component.translatable("mcitemstockmarket.gui.col_chgMonth"), right, y, color);
        // 表头分隔线
        gg.fill(x, y + 11, x + w, y + 12, 0xFF444444);
    }

    private void drawRight(GuiGraphics gg, Font font, Component text, int rightX, int y, int color) {
        gg.drawString(font, text, rightX - font.width(text), y, color);
    }

    @Override
    public boolean isPauseScreen() { return false; }

    // ================= List =================
    class StockList extends ObjectSelectionList<StockEntry> {
        private boolean draggingScrollbar = false;

        StockList() {
            super(StockMarketScreen.this.minecraft,
                    StockMarketScreen.this.width - PADDING * 2,
                    StockMarketScreen.this.height - LIST_TOP - PADDING,
                    LIST_TOP,
                    ROW_HEIGHT);
            // 列表默认 x=0，这里平移到 PADDING 形成左右对称边距
            this.setX(PADDING);
        }

        public void addEntryPublic(StockEntry entry) {
            addEntry(entry);
        }

        @Override
        public int getRowWidth() {
            return this.width - 6;
        }

        // 渲染：先画 vanilla（列表+滚动条），再叠加更醒目的可拖动滚动条
        @Override
        public void renderWidget(GuiGraphics gg, int mx, int my, float pt) {
            super.renderWidget(gg, mx, my, pt);
            renderVisibleScrollbar(gg);
        }

        private void renderVisibleScrollbar(GuiGraphics gg) {
            if (!scrollbarVisible()) return;
            int barX = scrollBarX();
            int barW = net.minecraft.client.gui.components.AbstractScrollArea.SCROLLBAR_WIDTH;
            int trackTop = getY();
            int trackBottom = getY() + getHeight();
            int trackH = trackBottom - trackTop;
            if (trackH <= 0) return;
            int thumbH = Math.max(10, scrollerHeight());
            int max = maxScrollAmount();
            double ratio = max > 0 ? scrollAmount() / (double) max : 0;
            ratio = Math.max(0, Math.min(1, ratio));
            // 滑块位置：轨道顶部 + (轨道高 - 滑块高) × 滚动比例
            int thumbY = trackTop + (int) ((trackH - thumbH) * ratio);
            // 轨道（半透明）
            gg.fill(barX, trackTop, barX + barW, trackBottom, 0x40222222);
            // 滑块（拖动时更亮）
            int thumbColor = draggingScrollbar ? 0xFFDDDDDD : 0xFF999999;
            gg.fill(barX, thumbY, barX + barW, thumbY + thumbH, thumbColor);
        }

        /** 供界面层判断：鼠标是否落在滚动条上。*/
        public boolean isScrollbarHit(double mouseX, double mouseY) {
            return scrollbarVisible() && isOverScrollbar(mouseX, mouseY);
        }

        /** 界面层拖动滚动条时同步滑块高亮状态。*/
        public void setScrollbarDragging(boolean v) {
            this.draggingScrollbar = v;
        }

        /** 根据鼠标 y 设置滚动位置（滑块中心对齐鼠标，轨道为列表顶部到底部）。*/
        public void setScrollFromMouseY(double my) {
            int trackTop = getY();
            int trackH = getHeight();
            int thumbH = Math.max(10, scrollerHeight());
            int max = maxScrollAmount();
            if (max <= 0) return;
            double usable = trackH - thumbH;
            if (usable <= 0) { setScrollAmount(0); return; }
            double ratio = (my - trackTop - thumbH / 2.0) / usable;
            ratio = Math.max(0, Math.min(1, ratio));
            setScrollAmount(ratio * max);
        }
    }

    class StockEntry extends ObjectSelectionList.Entry<StockEntry> {
        final Stock stock;
        StockEntry(Stock s) { this.stock = s; }

        @Override
        public Component getNarration() { return Component.literal(stock.getFullName()); }

        @Override
        public void renderContent(GuiGraphics gg, int mx, int my, boolean hovered, float pt) {
            Font font = StockMarketScreen.this.font;
            int x = this.getX();
            int y = this.getY();
            int w = this.getWidth();

            // 行背景（悬停高亮）：fill 使用 ARGB，0x40=半透明
            if (hovered) {
                gg.fill(x, y, x + w, y + ROW_HEIGHT, 0x40FFFFFF);
            }

            // 图标
            Item item = resolveItem();
            ItemStack stack = new ItemStack(item);
            gg.renderItem(stack, x + 3, y + 4);
            gg.renderItemDecorations(font, stack, x + 3, y + 4);

            // 右侧四列：现价 / 1分 / 1天 / 1月
            String cur = Config.CURRENCY_NAME.get();
            float p1 = stock.getDisplayP1();
            float p24 = stock.getDisplayP24();
            float pMonth = stock.getDisplayPMonth();
            String priceStr = String.format(Locale.US, "%.2f %s", stock.getPrice(), cur);
            String c1 = (p1 >= 0 ? "+" : "") + String.format(Locale.US, "%.2f%%", p1 * 100);
            String c24 = (p24 >= 0 ? "+" : "") + String.format(Locale.US, "%.2f%%", p24 * 100);
            String cMonth = (pMonth >= 0 ? "+" : "") + String.format(Locale.US, "%.2f%%", pMonth * 100);

            int right = x + w - 5;
            // 涨跌幅颜色：红涨绿跌（中国习惯），均带 0xFF alpha
            drawRight(gg, font, cMonth, right, y + 8, pMonth >= 0 ? 0xFFFF6666 : 0xFF66FF66);
            drawRight(gg, font, c24, right - COL_W, y + 8, p24 >= 0 ? 0xFFFF6666 : 0xFF66FF66);
            drawRight(gg, font, c1, right - 2 * COL_W, y + 8, p1 >= 0 ? 0xFFFF6666 : 0xFF66FF66);
            drawRight(gg, font, priceStr, right - 3 * COL_W, y + 8, 0xFFFFFF66);

            // 名称（本地化显示名，截断以避免与价格列重叠）
            int nameMaxW = (right - 3 * COL_W) - (x + 24) - 4;
            String state = stock.isDelisted()
                    ? Component.translatable("mcitemstockmarket.gui.state_delisted").getString()
                    : stock.isMomentum()
                            ? Component.translatable("mcitemstockmarket.gui.state_momentum").getString()
                            : "";
            String nameTxt = state + StockNameResolver.getDisplayName(stock);
            nameTxt = font.plainSubstrByWidth(nameTxt, Math.max(40, nameMaxW));
            gg.drawString(font, nameTxt, x + 24, y + 3, stock.isDelisted() ? 0xFFAAAAAA : 0xFFFFFFFF);
            gg.drawString(font, stock.getItemId(), x + 24, y + 14, 0xFF777777);
        }

        private void drawRight(GuiGraphics gg, Font font, String s, int rightX, int y, int color) {
            gg.drawString(font, s, rightX - font.width(s), y, color);
        }

        private Item resolveItem() {
            try {
                Item it = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(stock.getItemId()));
                if (it == null || it == Items.AIR) return Items.PAPER;
                return it;
            } catch (Exception e) { return Items.PAPER; }
        }

        @Override
        public boolean mouseClicked(MouseButtonEvent event, boolean isDoubleClick) {
            Minecraft.getInstance().setScreen(new StockDetailScreen(stock));
            return true;
        }
    }
}
