package com.mcitemstockmarket.event;

import com.mcitemstockmarket.MCItemStockMarket;
import com.mcitemstockmarket.command.StocksCommand;
import com.mcitemstockmarket.data.StockMarketSavedData;
import com.mcitemstockmarket.market.MarketEngine;
import com.mcitemstockmarket.network.NetworkHandler;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 服务端事件：服务器启动、tick、玩家加入、指令注册。
 * NeoForge 1.21.10 已统一事件总线，{@code @EventBusSubscriber} 不再需要指定 bus。
 */
@EventBusSubscriber(modid = MCItemStockMarket.MODID)
public class ServerEventHandlers {
    private static final MarketEngine marketEngine = new MarketEngine();

    static {
        marketEngine.setBroadcaster(NetworkHandler.getBroadcaster());
    }

    public static MarketEngine getEngine() { return marketEngine; }

    @SubscribeEvent
    public static void onServerStart(ServerStartingEvent event) {
        MCItemStockMarket.LOGGER.info("StockMarket: server starting, engine bound.");
    }

    /**
     * 服务器停止：保存数据到 <世界目录>/stockmarket/，并重置引擎与数据实例，
     * 防止切换世界（集成服务端）时残留旧数据导致行为异常。
     */
    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        MinecraftServer server = event.getServer();
        if (server != null) {
            StockMarketSavedData data = StockMarketSavedData.get(server);
            if (data != null) data.saveAll(server); // 停止时强制写入两个文件
            StockMarketSavedData.resetInstance();
        }
        marketEngine.resetState();
        MCItemStockMarket.LOGGER.info("StockMarket: server stopping, data saved & engine state reset.");
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (server == null) return;
        // 每个 server tick 驱动引擎；每次 50 秒价格调整后由 MarketEngine 内部保存存档
        try {
            StockMarketSavedData data = StockMarketSavedData.get(server);
            if (data != null) {
                marketEngine.tick(server, data);
            }
        } catch (Exception ex) {
            MCItemStockMarket.LOGGER.error("StockMarket tick error", ex);
        }
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer sp) {
            try {
                NetworkHandler.syncPlayerOnJoin(sp);
            } catch (Exception ex) {
                MCItemStockMarket.LOGGER.warn("Failed to sync stock market on player join: {}", ex.toString());
            }
        }
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        StocksCommand.register(event.getDispatcher());
    }
}

