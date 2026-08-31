package com.mcitemstockmarket.client;

import java.util.HashMap;
import java.util.Map;

import com.mcitemstockmarket.data.Stock;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 客户端侧股票/物品名称解析器。
 *
 * 作用：
 *  - {@link #getItemName(String)} 取物品在客户端当前语言下的显示名（用于排序与展示）。
 *  - {@link #getDisplayName(Stock)} 取股票完整本地化显示名（前缀 + 物品名 + 股 + 后缀）。
 *  - {@link #getSortKey(Stock)} 取排序键（物品本地化名），用于选股界面按物品名排序。
 *
 * 说明：股票显示名由 {@link Stock#getDisplayNameComponent()} 构造为 translatable 组件，
 *      在客户端调用 {@code getString()} 即按玩家选择的语言渲染。物品名由 Minecraft 自身本地化。
 *      仅支持中英文（zh_cn / en_us）；其它语言回退到物品 id / 原文前缀。
 */
public final class StockNameResolver {
    private StockNameResolver() {}

    private static String cachedLang;
    private static final Map<String, String> NAME_CACHE = new HashMap<>();

    /** 当前客户端选择的语言代码（如 "zh_cn" / "en_us"）。*/
    private static String currentLang() {
        try {
            LanguageManager lm = Minecraft.getInstance().getLanguageManager();
            if (lm != null) return lm.getSelected();
        } catch (Exception ignore) {}
        return null;
    }

    /** 物品在客户端当前语言下的显示名；解析失败回退到去命名空间的 itemId。*/
    public static String getItemName(String itemId) {
        String lang = currentLang();
        if (lang != null && !lang.equals(cachedLang)) {
            NAME_CACHE.clear();
            cachedLang = lang;
        }
        String cached = NAME_CACHE.get(itemId);
        if (cached != null) return cached;
        String name = computeItemName(itemId);
        NAME_CACHE.put(itemId, name);
        return name;
    }

    private static String computeItemName(String itemId) {
        try {
            Item item = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(itemId));
            if (item != null && item != Items.AIR) {
                return new ItemStack(item).getHoverName().getString();
            }
        } catch (Exception ignore) {}
        return itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
    }

    /** 股票完整本地化显示名（客户端当前语言）。*/
    public static String getDisplayName(Stock stock) {
        if (stock == null) return "";
        return stock.getDisplayNameComponent().getString();
    }

    /** 排序键：物品本地化名（选股界面按物品标签名称排序）。*/
    public static String getSortKey(Stock stock) {
        if (stock == null) return "";
        return getItemName(stock.getItemId());
    }
}
