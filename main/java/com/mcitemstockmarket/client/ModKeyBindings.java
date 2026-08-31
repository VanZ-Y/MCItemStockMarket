package com.mcitemstockmarket.client;

import org.lwjgl.glfw.GLFW;

import com.mcitemstockmarket.MCItemStockMarket;
import com.mcitemstockmarket.network.Payloads;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/**
 * K 键打开股市主界面。
 * NeoForge 1.21.10 已统一事件总线，无需指定 bus。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = MCItemStockMarket.MODID)
public class ModKeyBindings {
    public static final KeyMapping OPEN_MARKET = new KeyMapping(
            "key.mcitemstockmarket.open_market",
            GLFW.GLFW_KEY_K,
            KeyMapping.Category.MISC
    );

    @SubscribeEvent
    public static void register(RegisterKeyMappingsEvent event) {
        event.register(OPEN_MARKET);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null) return; // 打开屏幕时不响应
        if (mc.player == null) return;
        if (OPEN_MARKET.consumeClick()) {
            openMarket(mc);
        }
    }

    public static void openMarket(Minecraft mc) {
        if (mc.player == null) return;
        // 向服务端请求最新全量数据
        ClientPacketDistributor.sendToServer(new Payloads.ServerboundOpenGui());
        // 打开 GUI（等待同步后再填充内容，故先开空壳）
        mc.setScreen(new StockMarketScreen());
    }
}
