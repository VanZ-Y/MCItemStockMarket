package com.mcitemstockmarket.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mcitemstockmarket.data.PlayerAccount;
import com.mcitemstockmarket.data.Stock;
import com.mcitemstockmarket.network.NetworkHandler;
import com.mcitemstockmarket.network.Payloads;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * 客户端侧缓存：从服务端同步过来的股票列表+玩家账户。
 * 由 NetworkHandler 的 client handlers 更新。
 */
@EventBusSubscriber(value = Dist.CLIENT, modid = "mcitemstockmarket")
public class ClientData {
    // fullName -> Stock（最新快照）
    public static final Map<String, Stock> STOCKS = new HashMap<>();
    // 有序列表（方便 GUI 直接渲染）
    public static final List<Stock> STOCKS_ORDERED = new ArrayList<>();
    // 玩家自己的账户
    public static volatile PlayerAccount ACCOUNT = null;

    /** 在客户端初始化时调用，注册 NetworkHandler 客户端钩子。*/
    public static void initClientHandlers() {
        NetworkHandler.setClientHandlers(new NetworkHandler.ClientSideHandlers() {
            @Override public void onFullSync(Payloads.ClientboundFullSync p) {
                STOCKS.clear();
                STOCKS_ORDERED.clear();
                for (Stock s : p.stocks()) {
                    STOCKS.put(s.getFullName(), s);
                    STOCKS_ORDERED.add(s);
                }
                STOCKS_ORDERED.sort((a, b) -> a.getFullName().compareTo(b.getFullName()));
                ACCOUNT = p.account();
                // 通知屏幕刷新
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof StockMarketScreen sms) {
                    sms.refreshList();
                } else if (mc.screen instanceof StockDetailScreen sds) {
                    sds.refresh();
                } else if (mc.screen instanceof PortfolioScreen ps) {
                    ps.refreshList();
                }
            }
            @Override public void onPriceUpdate(Payloads.ClientboundPriceUpdate p) {
                for (Stock s : p.stocks()) {
                    Stock existing = STOCKS.get(s.getFullName());
                    if (existing != null) {
                        // 合并历史+状态，保留对象引用（列表中的引用也是同一对象）
                        existing.mergeFromUpdate(s);
                    } else {
                        STOCKS.put(s.getFullName(), s);
                        STOCKS_ORDERED.add(s);
                    }
                }
                STOCKS_ORDERED.sort((a, b) -> a.getFullName().compareTo(b.getFullName()));
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof StockMarketScreen sms) sms.refreshList();
                else if (mc.screen instanceof StockDetailScreen sds) sds.refresh();
                else if (mc.screen instanceof PortfolioScreen ps) ps.refreshList();
            }
            @Override public void onPricePatch(Payloads.ClientboundPricePatch p) {
                // 增量补丁：仅更新已存在的股票，不新增、不重排列表，降低客户端开销
                for (Stock.PricePatch patch : p.patches()) {
                    Stock existing = STOCKS.get(patch.fullName());
                    if (existing != null) {
                        existing.mergePatch(patch);
                    }
                }
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof StockMarketScreen sms) sms.refreshList();
                else if (mc.screen instanceof StockDetailScreen sds) sds.refresh();
                else if (mc.screen instanceof PortfolioScreen ps) ps.refreshList();
            }
            @Override public void onAccountUpdate(Payloads.ClientboundAccountUpdate p) {
                ACCOUNT = p.account();
                Minecraft mc = Minecraft.getInstance();
                if (mc.screen instanceof StockMarketScreen sms) sms.refreshAccount();
                else if (mc.screen instanceof StockDetailScreen sds) sds.refresh();
                else if (mc.screen instanceof PortfolioScreen ps) ps.refreshList();
            }
        });
    }
}
