package com.mcitemstockmarket.network;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mcitemstockmarket.Config;
import com.mcitemstockmarket.MCItemStockMarket;
import com.mcitemstockmarket.data.PendingOrder;
import com.mcitemstockmarket.data.PlayerAccount;
import com.mcitemstockmarket.data.Stock;
import com.mcitemstockmarket.data.StockMarketSavedData;
import com.mcitemstockmarket.market.MarketEngine;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 网络协调者。
 *  - 注册 Payload
 *  - 提供发送辅助（封装 PacketDistributor）
 *  - 实现 MarketEngine.Broadcaster
 */
@EventBusSubscriber(modid = MCItemStockMarket.MODID)
public class NetworkHandler {
    public static final String VERSION = "1";
    private static final MarketEngine.Broadcaster BROADCASTER = new MarketBroadcasterImpl();

    public static MarketEngine.Broadcaster getBroadcaster() { return BROADCASTER; }

    // ========== 客户端侧回调钩子（客户端注册） ==========
    public interface ClientSideHandlers {
        void onFullSync(Payloads.ClientboundFullSync payload);
        void onPriceUpdate(Payloads.ClientboundPriceUpdate payload);
        void onPricePatch(Payloads.ClientboundPricePatch payload);
        void onAccountUpdate(Payloads.ClientboundAccountUpdate payload);
    }
    // 安全修复 #5：volatile 保证多线程可见性（注册在客户端线程，读取在网络线程）
    private static volatile ClientSideHandlers clientHandlers;

    public static void setClientHandlers(ClientSideHandlers h) { clientHandlers = h; }

    // ========== Payload 注册（在 RegisterPayloadHandlersEvent 上调用，事件总线自动分发） ==========
    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var reg = event.registrar(MCItemStockMarket.MODID).versioned(VERSION);

        // S -> C
        reg.playToClient(Payloads.ClientboundFullSync.TYPE, Payloads.ClientboundFullSync.STREAM_CODEC,
                NetworkHandler::handleFullSync);
        reg.playToClient(Payloads.ClientboundPriceUpdate.TYPE, Payloads.ClientboundPriceUpdate.STREAM_CODEC,
                NetworkHandler::handlePriceUpdate);
        reg.playToClient(Payloads.ClientboundPricePatch.TYPE, Payloads.ClientboundPricePatch.STREAM_CODEC,
                NetworkHandler::handlePricePatch);
        reg.playToClient(Payloads.ClientboundAccountUpdate.TYPE, Payloads.ClientboundAccountUpdate.STREAM_CODEC,
                NetworkHandler::handleAccountUpdate);
        reg.playToClient(Payloads.ClientboundEvent.TYPE, Payloads.ClientboundEvent.STREAM_CODEC,
                NetworkHandler::handleEvent);

        // C -> S
        reg.playToServer(Payloads.ServerboundOpenGui.TYPE, Payloads.ServerboundOpenGui.STREAM_CODEC,
                NetworkHandler::handleOpenGui);
        reg.playToServer(Payloads.ServerboundSubmitOrder.TYPE, Payloads.ServerboundSubmitOrder.STREAM_CODEC,
                NetworkHandler::handleSubmitOrder);
        reg.playToServer(Payloads.ServerboundCancelOrders.TYPE, Payloads.ServerboundCancelOrders.STREAM_CODEC,
                NetworkHandler::handleCancelOrders);
        reg.playToServer(Payloads.ServerboundExchange.TYPE, Payloads.ServerboundExchange.STREAM_CODEC,
                NetworkHandler::handleExchange);
    }

    // ========== S2C -> Client 分发 ==========
    private static void handleFullSync(Payloads.ClientboundFullSync p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (clientHandlers != null) clientHandlers.onFullSync(p);
        });
    }
    private static void handlePriceUpdate(Payloads.ClientboundPriceUpdate p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (clientHandlers != null) clientHandlers.onPriceUpdate(p);
        });
    }
    private static void handlePricePatch(Payloads.ClientboundPricePatch p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (clientHandlers != null) clientHandlers.onPricePatch(p);
        });
    }
    private static void handleAccountUpdate(Payloads.ClientboundAccountUpdate p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (clientHandlers != null) clientHandlers.onAccountUpdate(p);
        });
    }
    private static void handleEvent(Payloads.ClientboundEvent p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            Player pl = ctx.player();
            if (pl != null) pl.displayClientMessage(p.message(), false);
        });
    }

    // ========== C2S -> Server 处理 ==========
    private static ServerPlayer asServerPlayer(Player p) {
        return p instanceof ServerPlayer sp ? sp : null;
    }
    private static StockMarketSavedData getData(ServerPlayer sp) {
        if (sp == null) return null;
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) return null;
        return StockMarketSavedData.get(server);
    }

    // ========== 安全修复 #2：C2S 包速率限制 ==========
    // 每个玩家每包类型的最小间隔（毫秒），防止恶意客户端刷包 DoS。
    private static final long RATE_LIMIT_MS = 200; // 每类型最快 5 次/秒
    private static final Map<UUID, long[]> playerRateLimits = new ConcurrentHashMap<>();

    /**
     * 检查玩家是否被速率限制。
     * @param playerId 玩家 UUID
     * @param slot 速率槽位（0=openGui, 1=submitOrder, 2=cancelOrders, 3=exchange）
     * @return true=允许通过, false=被限制
     */
    private static boolean checkRateLimit(UUID playerId, int slot) {
        long now = System.currentTimeMillis();
        long[] timestamps = playerRateLimits.computeIfAbsent(playerId, k -> new long[4]);
        synchronized (timestamps) {
            if (now - timestamps[slot] < RATE_LIMIT_MS) {
                return false; // 被限制
            }
            timestamps[slot] = now;
            return true;
        }
    }

    /** 玩家离线时清理速率限制数据，防止 Map 无限增长。*/
    public static void onPlayerLogout(UUID playerId) {
        playerRateLimits.remove(playerId);
    }

    /** 安全修复 #2：玩家离线时自动清理速率限制数据。*/
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            onPlayerLogout(sp.getUUID());
        }
    }

    /**
     * 非破坏性检查：玩家主背包（36 格）能否再容纳 quantity 个 item。
     * 不调用 Inventory.add（那会真正写入物品），只统计现有同类堆叠与空格的可容纳量。
     */
    private static boolean hasInventorySpace(ServerPlayer sp, Item item, int quantity) {
        int max = new ItemStack(item).getMaxStackSize();
        if (max <= 0 || quantity <= 0) return quantity <= 0;
        int remaining = quantity;
        var inv = sp.getInventory();
        // getItem(0..35) 为主背包格（含快捷栏）
        for (int i = 0; i < 36 && remaining > 0; i++) {
            ItemStack slot = inv.getItem(i);
            if (slot.isEmpty()) {
                remaining -= max;
            } else if (slot.is(item)) {
                remaining -= Math.max(0, max - slot.getCount());
            }
        }
        return remaining <= 0;
    }

    private static void handleOpenGui(Payloads.ServerboundOpenGui p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer sp = asServerPlayer(ctx.player());
            if (sp == null) return;
            if (!checkRateLimit(sp.getUUID(), 0)) return; // 速率限制
            StockMarketSavedData data = getData(sp);
            if (data == null) return;
            PlayerAccount acc = data.getOrCreateAccount(sp.getUUID());
            PacketDistributor.sendToPlayer(sp, new Payloads.ClientboundFullSync(List.copyOf(data.getAllStocks()), acc));
        });
    }

    private static void handleSubmitOrder(Payloads.ServerboundSubmitOrder p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer sp = asServerPlayer(ctx.player());
            if (sp == null) return;
            if (!checkRateLimit(sp.getUUID(), 1)) return; // 速率限制
            StockMarketSavedData data = getData(sp);
            if (data == null) return;
            Stock s = data.findStockByName(p.stockFullName());
            if (s == null) {
                sp.sendSystemMessage(Component.literal("[股市] 未找到股票：" + p.stockFullName()));
                return;
            }
            if (s.isDelisted()) {
                sp.sendSystemMessage(Component.literal("[股市] 股票已退市，无法交易：" + s.getFullName()));
                return;
            }
            if (p.quantity() <= 0) {
                sp.sendSystemMessage(Component.literal("[股市] 数量必须为正整数。"));
                return;
            }
            // 安全修复 #7：数量上限校验，防止 int 溢出和过大委托
            if (p.quantity() > 100000) {
                sp.sendSystemMessage(Component.literal("[股市] 单笔委托数量不能超过 100000。"));
                return;
            }
            PendingOrder.Type type = p.isBuy() ? PendingOrder.Type.BUY : PendingOrder.Type.SELL;
            PendingOrder order = new PendingOrder(sp.getUUID(), type, p.stockFullName(), p.quantity());
            data.addOrder(order);
            data.setDirty();
            String act = p.isBuy() ? "买入" : "卖出";
            sp.sendSystemMessage(Component.literal(String.format(
                    "[股市] 已提交%s委托：%s x%d，将在下一次股价调整后尝试成交。",
                    act, s.getFullName(), p.quantity())));
        });
    }

    private static void handleCancelOrders(Payloads.ServerboundCancelOrders p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer sp = asServerPlayer(ctx.player());
            if (sp == null) return;
            if (!checkRateLimit(sp.getUUID(), 2)) return; // 速率限制
            StockMarketSavedData data = getData(sp);
            if (data == null) return;
            int before = data.getPendingOrders().size();
            data.removeOrdersFor(sp.getUUID());
            int removed = before - data.getPendingOrders().size();
            data.setDirty();
            sp.sendSystemMessage(Component.literal(String.format(
                    "[股市] 已取消 %d 条待成交委托。", removed)));
        });
    }

    private static void handleExchange(Payloads.ServerboundExchange p, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer sp = asServerPlayer(ctx.player());
            if (sp == null) return;
            if (!checkRateLimit(sp.getUUID(), 3)) return; // 速率限制
            StockMarketSavedData data = getData(sp);
            if (data == null) return;
            Stock s = data.findStockByName(p.stockFullName());
            if (s == null) {
                sp.sendSystemMessage(Component.literal("[股市] 未找到股票：" + p.stockFullName()));
                return;
            }
            if (s.isDelisted()) {
                sp.sendSystemMessage(Component.literal("[股市] 股票已退市，无法兑换。"));
                return;
            }
            // 跟风股票不支持物品与货币兑换
            if (s.isMomentum()) {
                sp.sendSystemMessage(Component.literal("[股市] 兑换失败：跟风股票不支持物品与货币兑换。"));
                return;
            }
            if (p.quantity() <= 0) {
                sp.sendSystemMessage(Component.literal("[股市] 数量必须为正整数。"));
                return;
            }
            // 安全修复 #7：兑换数量上限校验
            if (p.quantity() > 100000) {
                sp.sendSystemMessage(Component.literal("[股市] 单笔兑换数量不能超过 100000。"));
                return;
            }
            Item item = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(s.getItemId()));
            if (item == null || item == Items.AIR) {
                sp.sendSystemMessage(Component.literal("[股市] 无法解析物品：" + s.getItemId()));
                return;
            }
            PlayerAccount acc = data.getOrCreateAccount(sp.getUUID());
            double price = s.getPrice();
            String cur = Config.CURRENCY_NAME.get();

            if (p.buyItem()) {
                // 货币兑换物品：按当前股价原价；跌停时无法兑换
                if (s.isLimitDown()) {
                    sp.sendSystemMessage(Component.literal("[股市] 兑换失败：")
                            .append(s.getDisplayNameComponent())
                            .append(Component.literal(" 已跌停，暂不可兑入物品。")));
                    return;
                }
                double cost = price * p.quantity();
                if (!acc.canAfford(cost)) {
                    sp.sendSystemMessage(Component.literal(String.format(
                            "[股市] 兑换失败：需 %.2f %s，余额 %.2f %s 不足。",
                            cost, cur, acc.getBalance(), cur)));
                    return;
                }
                if (!hasInventorySpace(sp, item, p.quantity())) {
                    sp.sendSystemMessage(Component.literal("[股市] 兑换失败：背包空间不足。"));
                    return;
                }
                // 安全修复 #3：原子性操作 — 保存回滚点，异常时恢复余额
                double savedBalance = acc.getBalance();
                try {
                    acc.addBalance(-cost);
                    ItemStack real = new ItemStack(item, p.quantity());
                    sp.getInventory().add(real);
                    if (!real.isEmpty()) sp.drop(real, false);
                } catch (Exception e) {
                    acc.setBalance(savedBalance);
                    MCItemStockMarket.LOGGER.error("[股市] 兑入物品异常，已回滚余额: {}", e.getMessage());
                    sp.sendSystemMessage(Component.literal("[股市] 兑换失败：内部错误，已回滚。"));
                    return;
                }
                data.markPlayersDirty(); // 兑换只改玩家账户，不写 market.json
                BROADCASTER.sendAccountUpdate(sp.getUUID(), acc);
                ItemStack disp = new ItemStack(item);
                sp.sendSystemMessage(Component.literal(String.format(
                        "[股市] 兑换成功：%s x%d，花费 %.2f %s（单价 %.2f）。",
                        disp.getHoverName().getString(), p.quantity(), cost, cur, price)));
            } else {
                // 物品兑换货币：按 当前股价 × EXCHANGE_SELL_PRICE_MULTIPLIER（默认 0.8）；涨跌都可兑换
                double sellPrice = price * Config.EXCHANGE_SELL_PRICE_MULTIPLIER.get();
                int have = 0;
                for (int i = 0; i < sp.getInventory().getContainerSize(); i++) {
                    ItemStack is = sp.getInventory().getItem(i);
                    if (is.is(item)) have += is.getCount();
                }
                if (have < p.quantity()) {
                    sp.sendSystemMessage(Component.literal(String.format(
                            "[股市] 兑换失败：背包中仅有 %d 个 %s，需要 %d 个。",
                            have, new ItemStack(item).getHoverName().getString(), p.quantity())));
                    return;
                }
                // 安全修复 #3：原子性操作 — 先扣物品再加钱，异常时恢复物品
                int toTake = p.quantity();
                java.util.List<int[]> takenRecords = new java.util.ArrayList<>();
                try {
                    for (int i = 0; i < sp.getInventory().getContainerSize() && toTake > 0; i++) {
                        ItemStack is = sp.getInventory().getItem(i);
                        if (is.is(item)) {
                            int taken = Math.min(is.getCount(), toTake);
                            takenRecords.add(new int[]{i, taken});
                            is.shrink(taken);
                            toTake -= taken;
                        }
                    }
                    double earn = sellPrice * p.quantity();
                    acc.addBalance(earn);
                } catch (Exception e) {
                    // 回滚：恢复被扣除的物品
                    for (int[] rec : takenRecords) {
                        sp.getInventory().getItem(rec[0]).grow(rec[1]);
                    }
                    MCItemStockMarket.LOGGER.error("[股市] 兑出物品异常，已回滚物品: {}", e.getMessage());
                    sp.sendSystemMessage(Component.literal("[股市] 兑换失败：内部错误，已回滚。"));
                    return;
                }
                double earn = sellPrice * p.quantity();
                data.markPlayersDirty(); // 兑换只改玩家账户，不写 market.json
                BROADCASTER.sendAccountUpdate(sp.getUUID(), acc);
                sp.sendSystemMessage(Component.literal(String.format(
                        "[股市] 兑换成功：卖出 %s x%d，获得 %.2f %s（单价 %.2f，8 折结算）。",
                        new ItemStack(item).getHoverName().getString(), p.quantity(), earn, cur, sellPrice)));
            }
        });
    }

    // ========== MarketBroadcaster 实现（封装 PacketDistributor） ==========
    private static class MarketBroadcasterImpl implements MarketEngine.Broadcaster {
        @Override
        public void broadcastChat(Component msg) {
            try {
                PacketDistributor.sendToAllPlayers(new Payloads.ClientboundEvent(msg));
            } catch (Exception e) {
                MCItemStockMarket.LOGGER.warn("[股市] broadcastChat 失败: {}", e.getMessage());
            }
        }
        @Override
        public void broadcastPriceUpdate(java.util.Collection<Stock> changed) {
            try {
                PacketDistributor.sendToAllPlayers(new Payloads.ClientboundPriceUpdate(List.copyOf(changed)));
            } catch (Exception e) {
                MCItemStockMarket.LOGGER.warn("[股市] broadcastPriceUpdate 失败: {}", e.getMessage());
            }
        }
        @Override
        public void broadcastPricePatch(java.util.List<Stock.PricePatch> patches) {
            if (patches.isEmpty()) return;
            try {
                PacketDistributor.sendToAllPlayers(new Payloads.ClientboundPricePatch(List.copyOf(patches)));
            } catch (Exception e) {
                MCItemStockMarket.LOGGER.warn("[股市] broadcastPricePatch 失败: {}", e.getMessage());
            }
        }
        @Override
        public void sendAccountUpdate(UUID playerId, PlayerAccount account) {
            try {
                var svr = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (svr == null) return;
                ServerPlayer sp = svr.getPlayerList().getPlayer(playerId);
                if (sp == null) return;
                PacketDistributor.sendToPlayer(sp, new Payloads.ClientboundAccountUpdate(account));
            } catch (Exception e) {
                MCItemStockMarket.LOGGER.warn("[股市] sendAccountUpdate 失败: {}", e.getMessage());
            }
        }
        @Override
        public void sendPlayerChat(UUID playerId, Component msg) {
            try {
                var svr = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
                if (svr == null) return;
                ServerPlayer sp = svr.getPlayerList().getPlayer(playerId);
                if (sp != null) sp.displayClientMessage(msg, false);
            } catch (Exception e) {
                MCItemStockMarket.LOGGER.warn("[股市] sendPlayerChat 失败: {}", e.getMessage());
            }
        }
    }

    // 玩家加入时主动全量同步（由 PlayerEventHandler 调用）
    public static void syncPlayerOnJoin(ServerPlayer sp) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        StockMarketSavedData data = StockMarketSavedData.get(server);
        if (data == null) return;
        PlayerAccount acc = data.getOrCreateAccount(sp.getUUID());
        PacketDistributor.sendToPlayer(sp, new Payloads.ClientboundFullSync(List.copyOf(data.getAllStocks()), acc));
    }
}
