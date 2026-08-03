package com.mcitemstockmarket.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.mcitemstockmarket.Config;
import com.mcitemstockmarket.MCItemStockMarket;
import com.mcitemstockmarket.data.ItemTrend;
import com.mcitemstockmarket.data.PendingOrder;
import com.mcitemstockmarket.data.PlayerAccount;
import com.mcitemstockmarket.data.Stock;
import com.mcitemstockmarket.data.StockMarketSavedData;
import com.mcitemstockmarket.network.NetworkHandler;
import com.mcitemstockmarket.network.Payloads;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * /stocks 指令实现。
 * 子命令：list, portfolio, buy, sell, cancel, exchange buy/sell, price
 */
public class StocksCommand {

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_STOCKS = (ctx, builder) -> {
        var data = getData(ctx);
        if (data == null) return builder.buildFuture();
        List<String> names = data.getAllStocks().stream()
                .filter(s -> !s.isDelisted())
                .map(Stock::getFullName)
                .sorted()
                .toList();
        return SharedSuggestionProvider.suggest(names, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_ITEMS = (ctx, builder) -> {
        var data = getData(ctx);
        if (data == null) return builder.buildFuture();
        List<String> ids = data.getAllStocks().stream()
                .map(Stock::getItemId)
                .distinct()
                .sorted()
                .toList();
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    private static StockMarketSavedData getData(CommandContext<CommandSourceStack> ctx) {
        var server = ctx.getSource().getServer();
        return StockMarketSavedData.get(server);
    }

    private static PlayerAccount getAccount(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) return null;
        var data = getData(ctx);
        if (data == null) return null;
        return data.getOrCreateAccount(sp.getUUID());
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var root = Commands.literal("stocks")
                .requires(src -> true);

        // /stocks list [itemId]   itemId 含 ":"（如 minecraft:diamond），用 greedyString 作为末尾参数
        root.then(Commands.literal("list")
                .executes(StocksCommand::listAll)
                .then(Commands.argument("itemId", StringArgumentType.greedyString())
                        .suggests(SUGGEST_ITEMS)
                        .executes(StocksCommand::listByItem)));

        // /stocks portfolio
        root.then(Commands.literal("portfolio")
                .executes(StocksCommand::portfolio));

        // /stocks buy <quantity> <stock>
        // 股票全名形如 "Villager-diamond股"，含中文"股"；word()/string() 的非引号模式无法解析中文，
        // 故用 greedyString 并置于末尾（greedyString 会吞掉其后所有文本，必须作为最后一个参数）。
        root.then(Commands.literal("buy")
                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 100000))
                        .then(Commands.argument("stock", StringArgumentType.greedyString())
                                .suggests(SUGGEST_STOCKS)
                                .executes(StocksCommand::buyStock))));

        // /stocks sell <quantity> <stock>
        root.then(Commands.literal("sell")
                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 100000))
                        .then(Commands.argument("stock", StringArgumentType.greedyString())
                                .suggests(SUGGEST_STOCKS)
                                .executes(StocksCommand::sellStock))));

        // /stocks cancel
        root.then(Commands.literal("cancel")
                .executes(StocksCommand::cancelOrders));

        // /stocks exchange buy <quantity> <stock>
        var exchange = Commands.literal("exchange");
        exchange.then(Commands.literal("buy")
                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 100000))
                        .then(Commands.argument("stock", StringArgumentType.greedyString())
                                .suggests(SUGGEST_STOCKS)
                                .executes(StocksCommand::exchangeBuy))));
        exchange.then(Commands.literal("sell")
                .then(Commands.argument("quantity", IntegerArgumentType.integer(1, 100000))
                        .then(Commands.argument("stock", StringArgumentType.greedyString())
                                .suggests(SUGGEST_STOCKS)
                                .executes(StocksCommand::exchangeSell))));
        root.then(exchange);

        // /stocks price <stock>
        root.then(Commands.literal("price")
                .then(Commands.argument("stock", StringArgumentType.greedyString())
                        .suggests(SUGGEST_STOCKS)
                        .executes(StocksCommand::priceCheck)));

        // /stocks trend <itemId>  查看某物品当前趋势值与玩家影响值
        root.then(Commands.literal("trend")
                .then(Commands.argument("itemId", StringArgumentType.greedyString())
                        .suggests(SUGGEST_ITEMS)
                        .executes(StocksCommand::trendCheck)));

        dispatcher.register(root);
    }

    // ========== 子命令实现 ==========

    private static int listAll(CommandContext<CommandSourceStack> ctx) {
        return listInternal(ctx, null);
    }

    private static int listByItem(CommandContext<CommandSourceStack> ctx) {
        String itemId = StringArgumentType.getString(ctx, "itemId").trim();
        return listInternal(ctx, itemId);
    }

    private static int listInternal(CommandContext<CommandSourceStack> ctx, String filterItemId) {
        var data = getData(ctx);
        if (data == null) { ctx.getSource().sendFailure(Component.literal("数据未就绪。")); return 0; }
        var src = ctx.getSource();
        List<Stock> stocks = new ArrayList<>(data.getAllStocks());
        stocks.sort(Comparator.comparing(Stock::getFullName));
        String currency = Config.CURRENCY_NAME.get();
        int count = 0;
        src.sendSuccess(() -> Component.literal("===== 股票列表 =====").withStyle(ChatFormatting.AQUA), false);
        for (Stock s : stocks) {
            if (filterItemId != null && !s.getItemId().equals(filterItemId)) continue;
            String state = s.isDelisted() ? "[退市]" : s.isMomentum() ? "[跟风]" : "      ";
            float p1 = (float) s.getChangePercent(s.getPriceMinutesAgo(1));
            float p24 = (float) s.getChangePercent(s.getPriceMinutesAgo(24));
            float p168 = (float) s.getChangePercent(s.getPriceMinutesAgo(168));
            String line = String.format(Locale.US,
                    "%s %s   价格: %.2f %s   1分: %+.2f%%  1天: %+.2f%%  1周: %+.2f%%",
                    state, s.getFullName(), s.getPrice(), currency, p1 * 100, p24 * 100, p168 * 100);
            ChatFormatting color = (p24 >= 0) ? ChatFormatting.RED : ChatFormatting.GREEN;
            MutableComponent c = Component.literal(line).withStyle(color);
            c = c.withStyle(sb -> sb.withClickEvent(
                    new ClickEvent.SuggestCommand("/stocks price " + s.getFullName()))
                    .withHoverEvent(new HoverEvent.ShowText(
                            Component.literal("点击查询详情，或复制 /stocks price " + s.getFullName()))));
            final Component msg = c;
            final int fcount = ++count;
            src.sendSuccess(() -> msg, false);
        }
        final int fcount = count;
        src.sendSuccess(() -> Component.literal("共 " + fcount + " 只股票。").withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int portfolio(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("只有玩家可使用此命令。")); return 0;
        }
        var data = getData(ctx);
        if (data == null) { ctx.getSource().sendFailure(Component.literal("数据未就绪。")); return 0; }
        PlayerAccount acc = data.getOrCreateAccount(sp.getUUID());
        String currency = Config.CURRENCY_NAME.get();

        ctx.getSource().sendSuccess(() -> Component.literal("===== 我的持仓 =====").withStyle(ChatFormatting.AQUA), false);
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("账户余额: %.2f %s",
                acc.getBalance(), currency)).withStyle(ChatFormatting.YELLOW), false);

        Map<String, Integer> holdings = acc.getHoldings();
        if (holdings.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("(暂无持仓)").withStyle(ChatFormatting.GRAY), false);
        } else {
            for (Map.Entry<String, Integer> e : holdings.entrySet()) {
                Stock s = data.findStockByName(e.getKey());
                double val = s == null ? 0 : s.getPrice() * e.getValue();
                String name = s == null ? e.getKey() : s.getFullName();
                String priceStr = s == null ? "?" : String.format(Locale.US, "%.2f", s.getPrice());
                String line = String.format("%s x%d  (@%s %s)  总估值: %.2f %s",
                        name, e.getValue(), priceStr, currency, val, currency);
                ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.WHITE), false);
            }
        }

        // 挂起的委托
        List<PendingOrder> pending = data.getPendingOrders().stream()
                .filter(o -> o.getPlayerId().equals(sp.getUUID()))
                .toList();
        if (!pending.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("待成交委托:").withStyle(ChatFormatting.GOLD), false);
            for (PendingOrder o : pending) {
                String line = String.format("  %s %s x%d",
                        o.getType() == PendingOrder.Type.BUY ? "买入" : "卖出",
                        o.getStockFullName(), o.getQuantity());
                ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.GRAY), false);
            }
        }
        return 1;
    }

    private static int buyStock(CommandContext<CommandSourceStack> ctx) {
        return submitOrder(ctx, true);
    }
    private static int sellStock(CommandContext<CommandSourceStack> ctx) {
        return submitOrder(ctx, false);
    }
    private static int submitOrder(CommandContext<CommandSourceStack> ctx, boolean isBuy) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("只有玩家可使用此命令。")); return 0;
        }
        String stockName = StringArgumentType.getString(ctx, "stock").trim();
        int qty = IntegerArgumentType.getInteger(ctx, "quantity");
        var data = getData(ctx);
        if (data == null) { ctx.getSource().sendFailure(Component.literal("数据未就绪。")); return 0; }
        Stock s = data.findStockByName(stockName);
        if (s == null) { ctx.getSource().sendFailure(Component.literal("未找到股票：" + stockName)); return 0; }
        if (s.isDelisted()) {
            ctx.getSource().sendFailure(Component.literal("股票已退市：" + s.getFullName())); return 0;
        }
        // 校验买卖前置条件
        PlayerAccount acc = data.getOrCreateAccount(sp.getUUID());
        if (isBuy) {
            double need = s.getPrice() * qty;
            if (!acc.canAfford(need)) {
                ctx.getSource().sendFailure(Component.literal(String.format(
                        "当前余额 %.2f %s，按现价委托需 %.2f %s（成交价以下一周期为准，委托仍可能因余额不足失败）。",
                        acc.getBalance(), Config.CURRENCY_NAME.get(),
                        need, Config.CURRENCY_NAME.get())));
                // 仍然允许提交，只是提示。按需求"委托挂起，到下一周期尝试"。
            }
        } else {
            int have = acc.getHolding(s.getFullName());
            if (have < qty) {
                ctx.getSource().sendFailure(Component.literal(String.format(
                        "当前持仓 %d 股，委托卖出 %d 股（到成交时仍可能因不足失败）。", have, qty)));
                // 仍然允许提交，按需求。
            }
        }
        PendingOrder order = new PendingOrder(sp.getUUID(),
                isBuy ? PendingOrder.Type.BUY : PendingOrder.Type.SELL,
                stockName, qty);
        data.addOrder(order);
        data.setDirty();
        String act = isBuy ? "买入" : "卖出";
        ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                "[股市] 已提交%s委托：%s x%d，将在下一次股价调整后尝试成交。", act, s.getFullName(), qty)), false);
        return 1;
    }

    private static int cancelOrders(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("只有玩家可使用此命令。")); return 0;
        }
        var data = getData(ctx);
        if (data == null) { ctx.getSource().sendFailure(Component.literal("数据未就绪。")); return 0; }
        int before = data.getPendingOrders().size();
        data.removeOrdersFor(sp.getUUID());
        int removed = before - data.getPendingOrders().size();
        data.setDirty();
        ctx.getSource().sendSuccess(() -> Component.literal(String.format("[股市] 已取消 %d 条待成交委托。", removed)), false);
        return 1;
    }

    private static int exchangeBuy(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("只有玩家可使用此命令。")); return 0;
        }
        String stock = StringArgumentType.getString(ctx, "stock").trim();
        int qty = IntegerArgumentType.getInteger(ctx, "quantity");
        // 指令直接在服务端执行兑换逻辑（与网络包 handler 等价）
        doExchangeServer(ctx, true, stock, qty);
        return 1;
    }

    private static int exchangeSell(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("只有玩家可使用此命令。")); return 0;
        }
        String stock = StringArgumentType.getString(ctx, "stock").trim();
        int qty = IntegerArgumentType.getInteger(ctx, "quantity");
        doExchangeServer(ctx, false, stock, qty);
        return 1;
    }

    // 直接复用 NetworkHandler 的 C2S handler 逻辑（等价）
    private static void doExchangeServer(CommandContext<CommandSourceStack> ctx, boolean buyItem, String stock, int qty) {
        ServerPlayer sp = (ServerPlayer) ctx.getSource().getEntity();
        StockMarketSavedData data = getData(ctx);
        if (data == null) return;
        Stock s = data.findStockByName(stock);
        if (s == null) { ctx.getSource().sendFailure(Component.literal("未找到股票：" + stock)); return; }
        if (s.isDelisted()) { ctx.getSource().sendFailure(Component.literal("股票已退市，无法兑换。")); return; }
        // 跟风股票不支持物品与货币兑换
        if (s.isMomentum()) {
            ctx.getSource().sendFailure(Component.literal("[股市] 兑换失败：跟风股票不支持物品与货币兑换。"));
            return;
        }
        if (qty <= 0) { ctx.getSource().sendFailure(Component.literal("数量必须为正整数。")); return; }
        net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(
                net.minecraft.resources.ResourceLocation.parse(s.getItemId()));
        if (item == null || item == net.minecraft.world.item.Items.AIR) {
            ctx.getSource().sendFailure(Component.literal("无法解析物品：" + s.getItemId())); return;
        }
        PlayerAccount acc = data.getOrCreateAccount(sp.getUUID());
        double price = s.getPrice();
        String cur = Config.CURRENCY_NAME.get();

        if (buyItem) {
            // 货币兑换物品：按当前股价原价；跌停时无法兑换
            if (s.isLimitDown()) {
                ctx.getSource().sendFailure(Component.literal("[股市] 兑换失败：")
                        .append(s.getDisplayNameComponent())
                        .append(Component.literal(" 已跌停，暂不可兑入物品。")));
                return;
            }
            double cost = price * qty;
            if (!acc.canAfford(cost)) {
                ctx.getSource().sendFailure(Component.literal(String.format(
                        "兑换失败：需 %.2f %s，余额 %.2f %s 不足。", cost, cur, acc.getBalance(), cur)));
                return;
            }
            // 安全修复：非破坏性背包空间检查（add 会真正写入物品，不能用于测试）
            int max = new net.minecraft.world.item.ItemStack(item).getMaxStackSize();
            int remaining = qty;
            for (int i = 0; i < 36 && remaining > 0; i++) {
                net.minecraft.world.item.ItemStack slot = sp.getInventory().getItem(i);
                if (slot.isEmpty()) {
                    remaining -= max;
                } else if (slot.is(item)) {
                    remaining -= Math.max(0, max - slot.getCount());
                }
            }
            if (remaining > 0) {
                ctx.getSource().sendFailure(Component.literal("兑换失败：背包空间不足。")); return;
            }
            // 扣款并实际加入物品（仅一次）
            acc.addBalance(-cost);
            net.minecraft.world.item.ItemStack real = new net.minecraft.world.item.ItemStack(item, qty);
            sp.getInventory().add(real);
            if (!real.isEmpty()) sp.drop(real, false); // 兜底：极端情况掉落，避免丢失
            data.markPlayersDirty(); // 兑换只改玩家账户，不写 market.json
            NetworkHandler.getBroadcaster().sendAccountUpdate(sp.getUUID(), acc);
            String name = new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "[股市] 兑换成功：%s x%d，花费 %.2f %s（单价 %.2f）。",
                    name, qty, cost, cur, price)), false);
        } else {
            // 物品兑换货币：按 当前股价 × EXCHANGE_SELL_PRICE_MULTIPLIER（默认 0.8）；涨跌都可兑换
            double sellPrice = price * Config.EXCHANGE_SELL_PRICE_MULTIPLIER.get();
            int have = 0;
            for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack is = sp.getInventory().getItem(i);
                if (is.is(item)) have += is.getCount();
            }
            if (have < qty) {
                String name = new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
                ctx.getSource().sendFailure(Component.literal(String.format(
                        "兑换失败：背包中仅有 %d 个 %s，需要 %d 个。", have, name, qty))); return;
            }
            int toTake = qty;
            for (int i = 0; i < sp.getInventory().getContainerSize() && toTake > 0; i++) {
                net.minecraft.world.item.ItemStack is = sp.getInventory().getItem(i);
                if (is.is(item)) {
                    int taken = Math.min(is.getCount(), toTake);
                    is.shrink(taken);
                    toTake -= taken;
                }
            }
            double earn = sellPrice * qty;
            acc.addBalance(earn);
            data.markPlayersDirty(); // 兑换只改玩家账户，不写 market.json
            NetworkHandler.getBroadcaster().sendAccountUpdate(sp.getUUID(), acc);
            String name = new net.minecraft.world.item.ItemStack(item).getHoverName().getString();
            ctx.getSource().sendSuccess(() -> Component.literal(String.format(
                    "[股市] 兑换成功：卖出 %s x%d，获得 %.2f %s（单价 %.2f，8 折结算）。",
                    name, qty, earn, cur, sellPrice)), false);
        }
    }

    private static int priceCheck(CommandContext<CommandSourceStack> ctx) {
        String stockName = StringArgumentType.getString(ctx, "stock").trim();
        var data = getData(ctx);
        if (data == null) { ctx.getSource().sendFailure(Component.literal("数据未就绪。")); return 0; }
        Stock s = data.findStockByName(stockName);
        if (s == null) { ctx.getSource().sendFailure(Component.literal("未找到股票：" + stockName)); return 0; }
        String currency = Config.CURRENCY_NAME.get();
        float p1 = (float) s.getChangePercent(s.getPriceMinutesAgo(1));
        float p24 = (float) s.getChangePercent(s.getPriceMinutesAgo(24));
        float p168 = (float) s.getChangePercent(s.getPriceMinutesAgo(168));
        String currencyInfo = s.isMomentum() ? " [跟风波动x" + Config.MOMENTUM_PRICE_MULTIPLIER.get() + "]" : "";
        String delistedInfo = s.isDelisted() ? " [已退市]" : "";
        String line = String.format(Locale.US,
                "%s%s%n  当前价: %.2f %s%n  1分钟涨跌幅: %+.2f%%%n  1天(24分)涨跌幅: %+.2f%%%n  1周(168分)涨跌幅: %+.2f%%",
                s.getFullName(), currencyInfo + delistedInfo,
                s.getPrice(), currency,
                p1 * 100, p24 * 100, p168 * 100);
        ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    /** /stocks trend <itemId>：查看某物品当前趋势值与玩家影响值。*/
    private static int trendCheck(CommandContext<CommandSourceStack> ctx) {
        String itemId = StringArgumentType.getString(ctx, "itemId").trim();
        var data = getData(ctx);
        if (data == null) { ctx.getSource().sendFailure(Component.literal("数据未就绪。")); return 0; }
        ItemTrend trend = data.getItemTrend(itemId);
        List<Stock> stocks = data.getStocksByItem(itemId);
        double trendVal = trend.getTrend();
        int threshold = Config.TRADE_VOLUME_THRESHOLD.get();
        double tradeImpact = Config.TRADE_IMPACT_PERCENT.get();

        int totalBuys = 0, totalSells = 0;
        for (Stock s : stocks) {
            if (s.isDelisted()) continue;
            totalBuys += s.getCumulativeBuys();
            totalSells += s.getCumulativeSells();
        }
        // 待生效影响：累计股数 / 阈值（向下取整）× 单次影响比例
        double buyImpact = threshold > 0 ? (totalBuys / threshold) * tradeImpact : 0;
        double sellImpact = threshold > 0 ? (totalSells / threshold) * tradeImpact : 0;
        double netImpact = buyImpact - sellImpact;

        // lambda 安全的最终副本
        final int fTotalBuys = totalBuys;
        final int fTotalSells = totalSells;
        final double fBuyImpact = buyImpact;
        final double fSellImpact = sellImpact;
        final double fNetImpact = netImpact;
        final double fTrendVal = trendVal;
        final int fStockCount = stocks.size();

        ctx.getSource().sendSuccess(() -> Component.literal(
                "===== 物品趋势 =====").withStyle(ChatFormatting.AQUA), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("物品: %s", itemId)).withStyle(ChatFormatting.WHITE), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("当前趋势值: %+.2f%%（每 20 分钟调整一次，叠加到每次 50 秒的价格刷新）", fTrendVal * 100))
                .withStyle(fTrendVal >= 0 ? ChatFormatting.RED : ChatFormatting.GREEN), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("玩家买入累计: %d 股 → 预计影响: %+.2f%%", fTotalBuys, fBuyImpact * 100))
                .withStyle(ChatFormatting.RED), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("玩家卖出累计: %d 股 → 预计影响: %+.2f%%", fTotalSells, fSellImpact * 100))
                .withStyle(ChatFormatting.GREEN), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("净影响: %+.2f%%（下次刷新时叠加到价格）", fNetImpact * 100))
                .withStyle(fNetImpact >= 0 ? ChatFormatting.RED : ChatFormatting.GREEN), false);
        ctx.getSource().sendSuccess(() -> Component.literal(
                String.format("该物品相关股票: %d 只", fStockCount))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }
}
