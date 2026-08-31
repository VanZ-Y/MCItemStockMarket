package com.mcitemstockmarket.market;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import com.mcitemstockmarket.Config;
import com.mcitemstockmarket.MCItemStockMarket;
import com.mcitemstockmarket.data.ItemTrend;
import com.mcitemstockmarket.data.PendingOrder;
import com.mcitemstockmarket.data.PlayerAccount;
import com.mcitemstockmarket.data.Stock;
import com.mcitemstockmarket.data.StockMarketSavedData;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 核心市场引擎。
 * 由服务端 tick 驱动，执行：
 *  - 每分钟股价调整 + 委托成交
 *  - 每 TREND_UPDATE_INTERVAL_MINUTES 趋势调整
 *  - 退市 / 重新上市
 *  - 跟风股票检测与生成
 * 调用方（ServerTickHandler）需确保线程安全（仅在主线程调用）。
 */
public class MarketEngine {
    private final Random random = new Random();

    /** 广播事件回调：需要在模组主类中注入网络层发送逻辑。*/
    public interface Broadcaster {
        /** 广播给所有玩家一条聊天消息。*/
        void broadcastChat(Component msg);
        /** 向所有玩家广播价格更新（全量历史，仅在玩家加入/打开 GUI 时用）。*/
        void broadcastPriceUpdate(Collection<Stock> changedStocks);
        /** 向所有玩家广播增量价格补丁（周期性 tick 用，轻量）。*/
        default void broadcastPricePatch(List<Stock.PricePatch> patches) {
            // 默认空实现；具体广播器应 override 为真正的增量发送
        }
        /** 向某个玩家发送账户变动。*/
        void sendAccountUpdate(UUID playerId, PlayerAccount account);
        /** 某玩家系统消息（不一定需要聊天）。*/
        void sendPlayerChat(UUID playerId, Component msg);
    }

    private Broadcaster broadcaster;
    // 复用缓冲区：避免每 tick / 每分钟 tick 重复分配 ArrayList
    private final List<Stock.PricePatch> patchBuffer = new ArrayList<>();
    private final List<Stock> relaunchBuffer = new ArrayList<>();
    private long lastRelaunchCheckMs = 0L;

    // ========== 性能探针（零成本：仅耗时超阈值才打印 WARN，正常时不输出）==========
    // 单 tick 总耗时警告阈值：5ms（服务器 tick 预算 50ms，超过 5ms 值得关注）
    private static final long TICK_WARN_NS = 5_000_000L;
    // 每分钟 tick 总耗时警告阈值：20ms（含遍历所有股票 + 委托 + 广播）
    private static final long MINUTE_WARN_NS = 20_000_000L;
    // tick 各环节纳秒耗时（每次 tick 开始时清零）
    private long probeMinuteNs, probeTrendNs, probeDailyNs, probeRelaunchNs, probeListingNs;
    // runMinuteTick 内部细分纳秒耗时
    private long probePriceLoopNs, probeOrdersNs, probeDelistNs, probeMomentumNs;

    public void setBroadcaster(Broadcaster b) { this.broadcaster = b; }

    /**
     * 安全修复：重置引擎内部缓冲区和计时状态。
     * 在服务器停止（切换世界/退出单人流）时调用，防止残留旧数据。
     * SavedData 本身是按世界存储的，不受影响；仅清理引擎实例的临时状态。
     */
    public void resetState() {
        patchBuffer.clear();
        relaunchBuffer.clear();
        lastRelaunchCheckMs = 0L;
        probeMinuteNs = probeTrendNs = probeDailyNs = probeRelaunchNs = probeListingNs = 0L;
        probePriceLoopNs = probeOrdersNs = probeDelistNs = probeMomentumNs = 0L;
    }

    // ========== 每 tick 检查入口 ==========
    public void tick(MinecraftServer server, StockMarketSavedData data) {
        if (server == null || data == null) return;
        long tickStart = System.nanoTime();
        // 清零各环节探针（本次 tick 重新累计）
        probeMinuteNs = probeTrendNs = probeDailyNs = probeRelaunchNs = probeListingNs = 0L;
        long now = System.currentTimeMillis();

        // 每分钟检查
        if (now - data.getLastMinuteTick() >= 50_000L) {
            long t = System.nanoTime();
            runMinuteTick(server, data);
            probeMinuteNs = System.nanoTime() - t;
            data.setLastMinuteTick(now);
            data.setDirty();
            // 每次 50 秒价格调整后立即保存存档
            data.saveToDisk(server);
        }

        // 趋势更新检查
        int trendMinutes = Config.TREND_UPDATE_INTERVAL_MINUTES.get();
        if (trendMinutes > 0 && now - data.getLastTrendTick() >= (long) trendMinutes * 50_000L) {
            long t = System.nanoTime();
            runTrendTick(server, data);
            probeTrendNs = System.nanoTime() - t;
            data.setLastTrendTick(now);
            data.setDirty();
        }

        // 每日（24分钟）检查：重置开盘价 + 系统播报
        long dayMs = 24L * 50_000L;
        if (now - data.getLastDayOpenReset() >= dayMs) {
            long t = System.nanoTime();
            runDailyTick(server, data);
            probeDailyNs = System.nanoTime() - t;
            data.setLastDayOpenReset(now);
            data.setDirty();
        }

        // 检查退市股票重新上市
        long t = System.nanoTime();
        checkRelaunches(server, data);
        probeRelaunchNs = System.nanoTime() - t;

        // 普通股票定期上市（每 5~10 分钟尝试一次）
        long next = data.getNextListingAttempt();
        if (next <= 0) {
            data.setNextListingAttempt(now + randomListingDelay());
            data.setDirty();
        } else if (now >= next) {
            long t2 = System.nanoTime();
            tryListNewStock(server, data);
            probeListingNs = System.nanoTime() - t2;
            data.setNextListingAttempt(now + randomListingDelay());
            data.setDirty();
        }

        // 探针汇报：仅当本次 tick 总耗时超过阈值时打印，避免日志刷屏
        long totalNs = System.nanoTime() - tickStart;
        if (totalNs >= TICK_WARN_NS) {
            MCItemStockMarket.LOGGER.warn(
                    "[股市探针] tick 耗时 {}ms（阈值{}ms）— minute={}ms trend={}ms daily={}ms relaunch={}ms listing={}ms 股票数={}",
                    totalNs / 1_000_000, TICK_WARN_NS / 1_000_000,
                    probeMinuteNs / 1_000_000, probeTrendNs / 1_000_000, probeDailyNs / 1_000_000,
                    probeRelaunchNs / 1_000_000, probeListingNs / 1_000_000,
                    data.getAllStocks().size());
        }
    }

    /** 普通股票定期上市间隔（现实秒）：默认 5~10 分钟。*/
    private long randomListingDelay() {
        List<? extends Integer> range = Config.NEW_LISTING_INTERVAL_SECONDS_RANGE.get();
        int minSec = range.size() > 0 ? range.get(0) : 300;
        int maxSec = range.size() > 1 ? range.get(1) : 600;
        if (maxSec < minSec) maxSec = minSec;
        int delaySec = minSec + random.nextInt(maxSec - minSec + 1);
        return (long) delaySec * 1000L;
    }

    /** 普通股票定期上市：随机选一个有保护价且普通股数 < 上限的物品，以该物品普通股均价上市，名称不重复。*/
    private void tryListNewStock(MinecraftServer server, StockMarketSavedData data) {
        List<String> itemPool = StockMarketSavedData.collectProtectedItems();
        List<String> prefixes = Config.getStockPrefixPool();
        if (itemPool.isEmpty() || prefixes.isEmpty()) return;
        int maxPerItem = Config.NEW_LISTING_MAX_PER_ITEM.get();

        // 随机选一个有保护价且该物品下普通股数 < maxPerItem 的物品
        String chosenItem = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            String itemId = itemPool.get(random.nextInt(itemPool.size()));
            int existing = data.getActiveNormalStocksByItem(itemId).size();
            if (existing < maxPerItem) { chosenItem = itemId; break; }
        }
        if (chosenItem == null) return;

        // 发行价：该物品下所有普通股的均价；无则用 该物品在 initial_prices 表中的初始价
        List<Stock> live = data.getActiveNormalStocksByItem(chosenItem);
        double price;
        if (live.isEmpty()) {
            price = Config.getInitialPrice(chosenItem);
        } else {
            double sum = 0;
            for (Stock x : live) sum += x.getPrice();
            price = sum / live.size();
        }

        // 选前缀：名称不能重复（含退市的同名股票也跳过）
        String chosenPrefix = null;
        for (int attempt = 0; attempt < 30; attempt++) {
            String prefix = (String) prefixes.get(random.nextInt(prefixes.size()));
            if (data.findStockByPrefixItem(prefix, chosenItem, "") == null) { chosenPrefix = prefix; break; }
        }
        if (chosenPrefix == null) return;

        Stock ns = new Stock(chosenPrefix, chosenItem, "", price);
        data.addStock(ns);
        data.ensureItemTrend(chosenItem);
        data.setDirty();
        if (broadcaster != null) {
            broadcaster.broadcastChat(Component.literal("[股市] 新股上市：")
                    .append(ns.getDisplayNameComponent())
                    .append(Component.literal(String.format("，发行价 %.2f %s。",
                            price, Config.CURRENCY_NAME.get()))));
            List<Stock> bc = new ArrayList<>();
            bc.add(ns);
            broadcaster.broadcastPriceUpdate(bc);
        }
    }

    // ========== 每分钟 tick ==========
    private void runMinuteTick(MinecraftServer server, StockMarketSavedData data) {
        long mtStart = System.nanoTime();
        // 1. 先计算所有股票的价格调整；复用 patchBuffer 避免每次分配
        patchBuffer.clear();
        double minuteMax = Config.MINUTE_FLUCTUATION_PERCENT.get();
        double momMul = Config.MOMENTUM_PRICE_MULTIPLIER.get();
        double tradeImpact = Config.TRADE_IMPACT_PERCENT.get();

        long t0 = System.nanoTime();
        for (Stock s : data.getAllStocks()) {
            if (s.isDelisted()) continue;
            double oldPrice = s.getPrice();
            // 基础：1 + 趋势 + 单次随机
            ItemTrend trend = data.getItemTrend(s.getItemId());
            if (trend == null) {
                data.ensureItemTrend(s.getItemId());
                trend = data.getItemTrend(s.getItemId());
            }
            if (trend == null) continue; // 趋势仍为 null 则跳过此股票
            double r = (random.nextDouble() * 2 - 1) * minuteMax; // [-max, +max]
            // 分时噪声平滑延续：本次 = 上一次噪声×0.7 + 新随机值×0.3，
            // 走势更顺滑、无断崖式涨跌；且噪声幅度（±0.3%）远小于日线趋势（±3%），不会盖过主线方向。
            r = s.getLastMinuteNoise() * 0.7 + r * 0.3;
            s.setLastMinuteNoise(r);
            // 玩家影响：本窗口内累计买入/卖出股数折算的影响（买多推高、卖多压低），
            // 买入/卖出计数在成交时累加，此处取走并叠加到本次价格调整。
            int buyImpacts = s.consumeBuyImpacts();
            int sellImpacts = s.consumeSellImpacts();
            double impactPct = (buyImpacts - sellImpacts) * tradeImpact;
            // 最终调整百分比 = 每20分钟趋势 + 每50秒随机值 + 玩家影响
            double delta = 1 + trend.getTrend() + r + impactPct;
            // 钳制 delta 下限，防止极端配置（波动率 + 趋势 > 100%）导致价格变负
            if (delta < 0.01) delta = 0.01;

            double newPrice = oldPrice * delta;
            // 跟风股票：波动结果再乘
            if (s.isMomentum()) {
                double change = newPrice - oldPrice;
                newPrice = oldPrice + change * momMul;
            }
            // 涨跌停板：普通股票单日（20分钟）涨跌幅不超过 daily_limit_percent（默认10%）。
            // 跟风股票不受此限制，保留其放大波动、可快速崩盘的特性。
            if (!s.isMomentum()) {
                double dayOpen = s.getDayOpenPrice();
                if (dayOpen > 0) {
                    double limit = Config.DAILY_LIMIT_PERCENT.get();
                    double upper = dayOpen * (1.0 + limit);
                    double lower = dayOpen * (1.0 - limit);
                    if (newPrice > upper) newPrice = upper;
                    if (newPrice < lower) newPrice = lower;
                }
                if (newPrice < 0.0001) newPrice = 0.0001;
            } else {
                if (newPrice < 0.0) newPrice = 0.0;
            }
            s.setPrice(newPrice);
            s.recordPriceNow();
            // 构造轻量增量补丁（不含历史），降低网络与分配开销
            patchBuffer.add(s.toPatch());
        }
        probePriceLoopNs = System.nanoTime() - t0;

        // 2. 执行挂起的委托（以新价格成交）
        t0 = System.nanoTime();
        executePendingOrders(server, data);
        probeOrdersNs = System.nanoTime() - t0;

        // 3. 检查退市
        t0 = System.nanoTime();
        checkDelistings(server, data);
        probeDelistNs = System.nanoTime() - t0;

        // 4. 检查跟风触发
        t0 = System.nanoTime();
        checkMomentumTrigger(server, data);
        probeMomentumNs = System.nanoTime() - t0;

        // 5. 广播增量价格补丁（每只股票 ~40 字节，对比全量历史 ~3.2KB/股）
        long bcNs = 0L;
        if (broadcaster != null && !patchBuffer.isEmpty()) {
            t0 = System.nanoTime();
            broadcaster.broadcastPricePatch(patchBuffer);
            bcNs = System.nanoTime() - t0;
        }

        // 探针汇报：仅当每分钟 tick 总耗时超过阈值时打印细分
        long mtTotal = System.nanoTime() - mtStart;
        if (mtTotal >= MINUTE_WARN_NS) {
            MCItemStockMarket.LOGGER.warn(
                    "[股市探针] runMinuteTick 耗时 {}ms（阈值{}ms）— 价格循环={}ms 委托={}ms 退市={}ms 跟风={}ms 广播={}ms 股票数={} 补丁数={}",
                    mtTotal / 1_000_000, MINUTE_WARN_NS / 1_000_000,
                    probePriceLoopNs / 1_000_000, probeOrdersNs / 1_000_000,
                    probeDelistNs / 1_000_000, probeMomentumNs / 1_000_000, bcNs / 1_000_000,
                    data.getAllStocks().size(), patchBuffer.size());
        }
    }

    // ========== 趋势调整 tick ==========
    private void runTrendTick(MinecraftServer server, StockMarketSavedData data) {
        double max = Config.TREND_FLUCTUATION_PERCENT.get();
        Set<String> itemIds = new HashSet<>();
        for (Stock s : data.getAllStocks()) {
            if (!s.isDelisted()) itemIds.add(s.getItemId());
        }
        for (String itemId : itemIds) {
            ItemTrend t = data.getItemTrend(itemId);
            if (t == null) {
                data.ensureItemTrend(itemId);
                t = data.getItemTrend(itemId);
            }
            if (t == null) continue;
            double r = (random.nextDouble() * 2 - 1) * max;
            // 趋势平滑延续：今日趋势 = 昨日趋势×0.6 + 新随机值×0.4，避免趋势大幅跳变
            t.setTrend(t.getTrend() * 0.6 + r * 0.4);
            t.setLastUpdated(System.currentTimeMillis());
        }
        MCItemStockMarket.LOGGER.info("Market trends updated for {} items", itemIds.size());
    }

    // ========== 每日（20分钟）tick：系统播报 + 重置开盘价 ==========
    private void runDailyTick(MinecraftServer server, StockMarketSavedData data) {
        // 收集存活股票及其当日（过去20分钟）涨跌幅
        List<Stock> active = new ArrayList<>();
        for (Stock s : data.getAllStocks()) {
            if (!s.isDelisted()) active.add(s);
        }
        final List<Stock> sorted = new ArrayList<>(active);
        sorted.sort(Comparator.comparingDouble(
                (Stock s) -> s.getChangePercent(s.getPriceMinutesAgo(24))).reversed());

        if (broadcaster != null) {
            // 每日播报：每游戏世界一天（20分钟）才播报一次
            broadcaster.broadcastChat(Component.literal(
                    "===== [股市] 每日播报（游戏一天 / 20 分钟）====="));
            if (sorted.isEmpty()) {
                broadcaster.broadcastChat(Component.literal("[股市] 当前无在市股票。"));
            } else {
                int topN = Math.min(3, sorted.size());
                // 涨幅榜
                MutableComponent gainers = Component.literal("[股市] 涨幅榜：");
                for (int i = 0; i < topN; i++) {
                    Stock s = sorted.get(i);
                    double chg = s.getChangePercent(s.getPriceMinutesAgo(24)) * 100;
                    if (i > 0) gainers.append(Component.literal("  "));
                    gainers.append(Component.literal(String.format("%d. ", i + 1)));
                    gainers.append(s.getDisplayNameComponent());
                    gainers.append(Component.literal(String.format(" %+.2f%%", chg)));
                }
                broadcaster.broadcastChat(gainers);
                // 跌幅榜
                MutableComponent losers = Component.literal("[股市] 跌幅榜：");
                for (int i = 0; i < topN; i++) {
                    Stock s = sorted.get(sorted.size() - 1 - i);
                    double chg = s.getChangePercent(s.getPriceMinutesAgo(24)) * 100;
                    if (i > 0) losers.append(Component.literal("  "));
                    losers.append(Component.literal(String.format("%d. ", i + 1)));
                    losers.append(s.getDisplayNameComponent());
                    losers.append(Component.literal(String.format(" %+.2f%%", chg)));
                }
                broadcaster.broadcastChat(losers);
            }
        }

        // 重置所有股票当日开盘价 = 当前价（新的一天开始）
        // 同时为存活股票记录一个月级数据点（每 20 分钟 / 1 游戏天一个，最多 30 个，环形覆盖）
        for (Stock s : data.getAllStocks()) {
            if (!s.isDelisted()) s.recordMonthlyNow();
            s.setDayOpenPrice(s.getPrice());
        }
        MCItemStockMarket.LOGGER.info("StockMarket daily tick: day-open prices reset for {} stocks", data.getAllStocks().size());
    }

    // ========== 挂单成交 ==========
    private void executePendingOrders(MinecraftServer server, StockMarketSavedData data) {
        // 安全修复 #3：不提前清空委托列表，逐条处理成功后再移除，
        // 这样服务器崩溃时未处理的委托不会丢失。
        List<PendingOrder> orders = new ArrayList<>(data.getPendingOrders());

        for (PendingOrder o : orders) {
            Stock s = data.findStockByName(o.getStockFullName());
            PlayerAccount account = data.getOrCreateAccount(o.getPlayerId());
            if (s == null) {
                notifyPlayer(server, o.getPlayerId(),
                        Component.literal("[股市] 委托失败：股票不存在：" + o.getStockFullName()));
                data.getPendingOrders().remove(o);
                continue;
            }
            if (s.isDelisted()) {
                notifyPlayer(server, o.getPlayerId(),
                        Component.literal("[股市] 委托失败：股票已退市：").append(s.getDisplayNameComponent()));
                data.getPendingOrders().remove(o);
                continue;
            }
            double price = s.getPrice();
            int qty = o.getQuantity();
            String cur = Config.CURRENCY_NAME.get();

            // 涨跌停板拦截（仅普通股票；跟风股票不受限）
            double limit = Config.DAILY_LIMIT_PERCENT.get();
            boolean limitUp = s.isLimitUp();
            boolean limitDown = s.isLimitDown();

            if (o.getType() == PendingOrder.Type.BUY) {
                if (limitUp) {
                    notifyPlayer(server, o.getPlayerId(),
                            Component.literal("[股市] 买入委托失败：")
                                    .append(s.getDisplayNameComponent())
                                    .append(Component.literal(String.format(
                                            " 已涨停（当日涨幅达 %.0f%%），暂不可买入。", limit * 100))));
                    data.getPendingOrders().remove(o);
                    continue;
                }
                double total = price * qty;
                if (!account.canAfford(total)) {
                    notifyPlayer(server, o.getPlayerId(),
                            Component.literal("[股市] 买入委托失败：")
                                    .append(s.getDisplayNameComponent())
                                    .append(Component.literal(String.format(
                                            " x%d 需 %.2f %s，余额不足。", qty, total, cur))));
                    data.getPendingOrders().remove(o);
                    continue;
                }
                // 安全修复 #5：原子性操作 — 保存回滚点，失败时恢复
                double savedBalance = account.getBalance();
                try {
                    account.addBalance(-total);
                    account.recordBuy(s.getFullName(), qty, price);
                    s.addBuyCount(qty); // 累计买入股数，下一次价格调整时计入玩家影响
                } catch (Exception e) {
                    // 回滚：恢复余额和持仓
                    account.setBalance(savedBalance);
                    MCItemStockMarket.LOGGER.error("[股市] 买入委托执行异常，已回滚: {}", e.getMessage());
                    data.getPendingOrders().remove(o);
                    continue;
                }
                notifyPlayer(server, o.getPlayerId(),
                        Component.literal("[股市] 买入成交：")
                                .append(s.getDisplayNameComponent())
                                .append(Component.literal(String.format(
                                        " x%d @ %.2f %s，合计 %.2f %s。",
                                        qty, price, cur, total, cur))));
            } else {
                if (limitDown) {
                    notifyPlayer(server, o.getPlayerId(),
                            Component.literal("[股市] 卖出委托失败：")
                                    .append(s.getDisplayNameComponent())
                                    .append(Component.literal(String.format(
                                            " 已跌停（当日跌幅达 %.0f%%），暂不可卖出。", limit * 100))));
                    data.getPendingOrders().remove(o);
                    continue;
                }
                if (!account.hasHolding(s.getFullName(), qty)) {
                    notifyPlayer(server, o.getPlayerId(),
                            Component.literal("[股市] 卖出委托失败：")
                                    .append(s.getDisplayNameComponent())
                                    .append(Component.literal(String.format(
                                            " 持仓不足 %d 股。", qty))));
                    data.getPendingOrders().remove(o);
                    continue;
                }
                // 安全修复 #5：原子性操作 — 保存回滚点
                double savedBalance = account.getBalance();
                int savedHolding = account.getHolding(s.getFullName());
                try {
                    account.addHolding(s.getFullName(), -qty);
                    account.addBalance(price * qty);
                    s.addSellCount(qty); // 累计卖出股数，下一次价格调整时计入玩家影响
                } catch (Exception e) {
                    // 回滚：恢复余额和持仓
                    account.setBalance(savedBalance);
                    account.addHolding(s.getFullName(), savedHolding - account.getHolding(s.getFullName()));
                    MCItemStockMarket.LOGGER.error("[股市] 卖出委托执行异常，已回滚: {}", e.getMessage());
                    data.getPendingOrders().remove(o);
                    continue;
                }
                double total = price * qty;
                notifyPlayer(server, o.getPlayerId(),
                        Component.literal("[股市] 卖出成交：")
                                .append(s.getDisplayNameComponent())
                                .append(Component.literal(String.format(
                                        " x%d @ %.2f %s，获得 %.2f %s。",
                                        qty, price, cur, total, cur))));
            }
            // 成功执行后才从委托列表移除（安全修复 #3）
            data.getPendingOrders().remove(o);
            data.setDirty();
            data.markPlayersDirty(); // 账户余额/持仓已变动，需要写 players.json
            if (broadcaster != null) {
                broadcaster.sendAccountUpdate(o.getPlayerId(), account);
            }
        }
    }

    private void notifyPlayer(MinecraftServer server, UUID pid, Component msg) {
        ServerPlayer sp = server.getPlayerList().getPlayer(pid);
        if (sp != null) sp.sendSystemMessage(msg);
        // 离线玩家则忽略（其上线后可查持仓/余额即可看到最终结果，
        // 也可改进为发送到事件记录；此处保持轻量）。
    }

    // ========== 退市检查 ==========
    private void checkDelistings(MinecraftServer server, StockMarketSavedData data) {
        List<Stock> toDelist = new ArrayList<>();
        for (Stock s : data.getAllStocks()) {
            if (s.isDelisted()) continue;
            if (s.isMomentum()) {
                // 跟风股票：无保护价，跌至 0 即自动退市
                if (s.getPrice() <= 0.0) toDelist.add(s);
            } else {
                double protection = Config.getProtectionPrice(s.getItemId());
                if (s.getPrice() <= protection + 1e-9) {
                    toDelist.add(s);
                }
            }
        }
        for (Stock s : toDelist) {
            delistStock(server, data, s);
        }
        // 安全修复 #4：定期清理已退市且超过重新上市窗口的旧股票，防止内存泄漏
        cleanupOldDelistedStocks(data);
    }

    /**
     * 安全修复 #4：清理退市时间超过 1 小时的旧股票（远超 8-10 分钟重新上市窗口）。
     * 仅清理无任何玩家持仓的退市股（delistStock 已补偿并清零持仓，故正常情况下都满足）。
     * 每次最多清理 50 条，避免单次 tick 开销过大。
     */
    private void cleanupOldDelistedStocks(StockMarketSavedData data) {
        long now = System.currentTimeMillis();
        long cutoff = now - 3_600_000L; // 1 小时前
        int removed = 0;
        List<Stock> toRemove = new ArrayList<>();
        for (Stock s : data.getAllStocks()) {
            if (removed >= 50) break;
            if (!s.isDelisted()) continue;
            if (s.getDelistedAt() > cutoff) continue; // 退市不足 1 小时，跳过
            // 检查是否还有玩家持仓（理论上 delistStock 已清零，但防御性检查）
            boolean hasHolders = false;
            for (PlayerAccount a : data.getAllAccounts()) {
                if (a.getHolding(s.getFullName()) > 0) { hasHolders = true; break; }
            }
            if (!hasHolders) {
                toRemove.add(s);
                removed++;
            }
        }
        for (Stock s : toRemove) {
            data.removeStock(s);
        }
        if (!toRemove.isEmpty()) {
            MCItemStockMarket.LOGGER.info("[股市] 清理了 {} 只旧退市股票", toRemove.size());
            data.setDirty();
        }
    }

    private void delistStock(MinecraftServer server, StockMarketSavedData data, Stock s) {
        s.setDelisted(true);
        s.setDelistedAt(System.currentTimeMillis());
        boolean momentum = s.isMomentum();
        if (momentum) {
            // 跟风股退市后不再重新上市：不设置重新上市时间（保持 0，稍后直接删除）
            s.setRelaunchAt(0);
        } else {
            // 普通股票：计算重新上市时间：8-10 分钟随机
            List<? extends Integer> range = Config.RELAUNCH_DELAY_SECONDS_RANGE.get();
            int minSec = range.size() > 0 ? range.get(0) : 480;
            int maxSec = range.size() > 1 ? range.get(1) : 600;
            if (maxSec < minSec) maxSec = minSec;
            int delaySec = minSec + random.nextInt(maxSec - minSec + 1);
            s.setRelaunchAt(System.currentTimeMillis() + (long) delaySec * 1000L);
        }

        // 补偿所有持股玩家（跟风股票无保护价，补偿为 0）
        double protection = s.isMomentum() ? 0.0 : Config.getProtectionPrice(s.getItemId());
        Map<UUID, Double> payouts = new HashMap<>();
        Collection<PlayerAccount> all = data.getAllAccounts();
        for (PlayerAccount a : all) {
            int holding = a.getHolding(s.getFullName());
            if (holding > 0) {
                double pay = holding * protection;
                a.addBalance(pay);
                a.addHolding(s.getFullName(), -holding);
                payouts.put(a.getPlayerId(), pay);
                data.markPlayersDirty(); // 退市补偿改变了玩家账户
                if (broadcaster != null) broadcaster.sendAccountUpdate(a.getPlayerId(), a);
            }
        }

        // 聊天公告
        if (broadcaster != null) {
            if (s.isMomentum()) {
                broadcaster.broadcastChat(Component.literal("[股市] 跟风股 ")
                        .append(s.getDisplayNameComponent())
                        .append(Component.literal(" 崩盘退市，价格归零；跟风股无保护价，持股玩家无补偿。")));
            } else {
                broadcaster.broadcastChat(Component.literal("[股市] ")
                        .append(s.getDisplayNameComponent())
                        .append(Component.literal(String.format(
                                " 已退市，价格 %.2f 低于保护价 %.2f；持股玩家已按 %.2f %s/股结算。",
                                s.getPrice(), protection, protection, Config.CURRENCY_NAME.get()))));
            }
        }
        // 私人通知
        for (Map.Entry<UUID, Double> e : payouts.entrySet()) {
            ServerPlayer sp = server.getPlayerList().getPlayer(e.getKey());
            if (sp != null) {
                if (s.isMomentum()) {
                    sp.sendSystemMessage(Component.literal("[股市] 您持有的跟风股 ")
                            .append(s.getDisplayNameComponent())
                            .append(Component.literal(" 已崩盘退市，无补偿。")));
                } else {
                    sp.sendSystemMessage(Component.literal("[股市] 您持有的 ")
                            .append(s.getDisplayNameComponent())
                            .append(Component.literal(String.format(
                                    " 已退市，按保护价结算获得 %.2f %s。",
                                    e.getValue(), Config.CURRENCY_NAME.get()))));
                }
            }
        }

        // 跟风股退市后不再重新上市：补偿已结清、持仓已清零，直接删除
        if (momentum) {
            data.removeStock(s);
            data.setDirty();
            MCItemStockMarket.LOGGER.info("[股市] 跟风股 {} 已退市并删除，不再重新上市", s.getFullName());
        }
    }

    // ========== 重新上市检查 ==========
    private void checkRelaunches(MinecraftServer server, StockMarketSavedData data) {
        long now = System.currentTimeMillis();
        // 节流：每秒最多完整扫描一次（该方法每个服务器 tick 50ms 都会被调用，
        // 但重新上市判定精度到秒足够，避免上百只股票每 tick 全量遍历）。
        if (now - lastRelaunchCheckMs < 1000L) return;
        lastRelaunchCheckMs = now;
        // 复用 relaunchBuffer，避免每次调用分配新 ArrayList
        relaunchBuffer.clear();
        for (Stock s : data.getAllStocks()) {
            if (s.isDelisted() && s.getRelaunchAt() > 0 && now >= s.getRelaunchAt()) {
                relaunchBuffer.add(s);
            }
        }
        for (Stock old : relaunchBuffer) {
            // 该物品下存活普通股票若已达上限，不再重新上市，直接删除
            int maxPerItem = Config.NEW_LISTING_MAX_PER_ITEM.get();
            if (data.getActiveNormalStocksByItem(old.getItemId()).size() >= maxPerItem) {
                data.removeStock(old);
                data.setDirty();
                MCItemStockMarket.LOGGER.info("[股市] 物品 {} 普通股票已达上限 {}，退市股 {} 直接删除，不再重新上市",
                        old.getItemId(), maxPerItem, old.getFullName());
                continue;
            }
            // 先移除旧股票
            data.removeStock(old);

            // 该物品下其他存活股票均价80% 或 初始价，取高者
            double initial = Config.getInitialPrice(old.getItemId());
            double avg80 = 0.0;
            List<Stock> live = data.getActiveNormalStocksByItem(old.getItemId());
            if (!live.isEmpty()) {
                double sum = 0.0;
                for (Stock x : live) sum += x.getPrice();
                avg80 = (sum / live.size()) * 0.8;
            }
            double launchPrice = Math.max(initial, avg80 > 0 ? avg80 : initial);

            // 创建新股票（同前缀、同物品、相同是否跟风属性）
            Stock ns = new Stock(old.getPrefix(), old.getItemId(),
                    old.isMomentum() ? old.getMomentumSuffix() : "", launchPrice);
            // 如果名字冲突（极小概率），换个前缀；设置上限避免无限循环
            if (data.findStockByName(ns.getFullName()) != null) {
                int idx = 2;
                boolean found = false;
                while (idx <= 9999) {
                    String tryPrefix = old.getPrefix() + idx;
                    Stock candidate = new Stock(tryPrefix, old.getItemId(),
                            old.isMomentum() ? old.getMomentumSuffix() : "", launchPrice);
                    if (data.findStockByName(candidate.getFullName()) == null) {
                        ns = candidate;
                        found = true;
                        break;
                    }
                    idx++;
                }
                if (!found) {
                    // 所有序号都被占用，放弃重新上市，保留退市状态
                    data.addStock(old); // 恢复旧股票到列表（removeStock 已移除）
                    old.setDelisted(true);
                    return;
                }
            }
            data.addStock(ns);
            data.ensureItemTrend(ns.getItemId());
            if (broadcaster != null) {
                broadcaster.broadcastChat(Component.literal("[股市] ")
                        .append(ns.getDisplayNameComponent())
                        .append(Component.literal(String.format(
                                " 重新上市，发行价 %.2f %s。",
                                launchPrice, Config.CURRENCY_NAME.get()))));
                List<Stock> broadcast = new ArrayList<>();
                broadcast.add(ns);
                broadcaster.broadcastPriceUpdate(broadcast);
            }
        }
    }

    private String itemNameOf(String itemId) {
        return itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
    }

    // ========== 跟风触发检查 ==========
    private void checkMomentumTrigger(MinecraftServer server, StockMarketSavedData data) {
        long now = System.currentTimeMillis();
        // 全局限频：窗口期内最多触发 N 次
        int maxEvents = Config.MOMENTUM_MAX_EVENTS_PER_WINDOW.get();
        if (maxEvents > 0 && data.countMomentumEventsInWindow(now) >= maxEvents) {
            return;
        }
        // 收集各物品：旗下任一普通股近24小时涨幅、近168小时涨幅
        Set<String> itemIds = new HashSet<>();
        for (Stock s : data.getAllStocks()) {
            if (!s.isDelisted()) itemIds.add(s.getItemId());
        }

        // 计算每个 item 的 max(普通股票涨幅)
        Map<String, Double> dayGain = new HashMap<>();
        Map<String, Double> weekGain = new HashMap<>();
        for (String id : itemIds) {
            double maxDay = -Double.MAX_VALUE;
            double maxWeek = -Double.MAX_VALUE;
            for (Stock s : data.getActiveNormalStocksByItem(id)) {
                double p24 = s.getPriceMinutesAgo(24);
                double p168 = s.getPriceMinutesAgo(168);
                double d = p24 > 0 ? (s.getPrice() - p24) / p24 : 0;
                double w = p168 > 0 ? (s.getPrice() - p168) / p168 : 0;
                if (d > maxDay) maxDay = d;
                if (w > maxWeek) maxWeek = w;
            }
            if (maxDay != -Double.MAX_VALUE) dayGain.put(id, maxDay);
            if (maxWeek != -Double.MAX_VALUE) weekGain.put(id, maxWeek);
        }

        Set<String> topDayItems = rankTopItems(dayGain);
        Set<String> topWeekItems = rankTopItems(weekGain);
        double minIncrease = Config.MOMENTUM_MIN_INCREASE_PERCENT.get();

        Set<String> candidates = new HashSet<>(topDayItems);
        candidates.retainAll(topWeekItems);

        List<? extends String> suffixes = Config.MOMENTUM_SUFFIXES.get();
        List<String> prefixPool = Config.getStockPrefixPool();
        if (suffixes.isEmpty() || prefixPool.isEmpty()) return;

        for (String itemId : candidates) {
            // 每轮重新检查全局限频（避免单次 tick 内多候选突破上限）
            if (maxEvents > 0 && data.countMomentumEventsInWindow(now) >= maxEvents) return;
            if (dayGain.getOrDefault(itemId, 0.0) <= minIncrease) continue;
            if (weekGain.getOrDefault(itemId, 0.0) <= minIncrease) continue;
            long cooldown = data.getMomentumCooldown().getOrDefault(itemId, 0L);
            if (now < cooldown) continue;
            int maxMom = Config.MOMENTUM_MAX_SAME_ITEM.get();
            if (data.countActiveMomentumStocks(itemId) >= maxMom) continue;

            // 尝试每个后缀+多个前缀，找到一个未重名的组合即上市
            boolean launched = false;
            for (String sfx : suffixes) {
                if (launched) break;
                for (int attempt = 0; attempt < 30 && !launched; attempt++) {
                    String prefix = attempt < prefixPool.size()
                            ? prefixPool.get((attempt + random.nextInt(prefixPool.size())) % prefixPool.size())
                            : prefixPool.get(random.nextInt(prefixPool.size())) + (attempt - prefixPool.size() + 2);
                    Stock test = data.findStockByPrefixItem(prefix, itemId, sfx);
                    if (test != null) continue;
                    List<Stock> live = data.getActiveNormalStocksByItem(itemId);
                    double avg = Config.getInitialPrice(itemId);
                    if (!live.isEmpty()) {
                        double sum = 0;
                        for (Stock x : live) sum += x.getPrice();
                        avg = sum / live.size();
                    }
                    Stock ns = new Stock(prefix, itemId, sfx, avg);
                    data.addStock(ns);
                    data.ensureItemTrend(itemId);
                    data.getMomentumCooldown().put(itemId,
                            now + (long) Config.MOMENTUM_COOLDOWN_MINUTES.get() * 50_000L);
                    // 记录一次跟风事件（用于全局限频）
                    data.addMomentumEvent(now);
                    if (broadcaster != null) {
                        broadcaster.broadcastChat(Component.literal("[股市] 市场火热！跟风股票 ")
                                .append(ns.getDisplayNameComponent())
                                .append(Component.literal(String.format(
                                        " 上市，发行价 %.2f %s（波动幅度 x%.1f）。",
                                        avg, Config.CURRENCY_NAME.get(),
                                        Config.MOMENTUM_PRICE_MULTIPLIER.get()))));
                        List<Stock> bc = new ArrayList<>();
                        bc.add(ns);
                        broadcaster.broadcastPriceUpdate(bc);
                    }
                    launched = true;
                    break;
                }
            }
        }
    }

    private Set<String> rankTopItems(Map<String, Double> gainByItem) {
        int topN = Config.MOMENTUM_TOP_RANK.get();
        List<Map.Entry<String, Double>> sorted = new ArrayList<>(gainByItem.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue).reversed());
        Set<String> result = new HashSet<>();
        if (topN > 0) {
            for (int i = 0; i < Math.min(topN, sorted.size()); i++) {
                result.add(sorted.get(i).getKey());
            }
        } else {
            double pct = Config.MOMENTUM_TOP_PERCENT.get();
            int take = (int) Math.max(1, Math.ceil(sorted.size() * pct));
            for (int i = 0; i < take && i < sorted.size(); i++) {
                result.add(sorted.get(i).getKey());
            }
        }
        return result;
    }

}
