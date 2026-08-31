package com.mcitemstockmarket.data;

import net.minecraft.nbt.CompoundTag;

/**
 * 每个基础物品一个趋势值。
 * 趋势值会在每 TREND_UPDATE_INTERVAL_MINUTES 分钟重新随机一次。
 */
public class ItemTrend {
    private String itemId;
    // 当前趋势（百分比小数）
    private double trend;
    // 上次趋势调整时间戳（ms）
    private long lastUpdated;

    public ItemTrend(String itemId, double initialTrend) {
        this.itemId = itemId;
        this.trend = initialTrend;
        this.lastUpdated = System.currentTimeMillis();
    }

    public ItemTrend(CompoundTag tag) {
        this.itemId = tag.getStringOr("itemId", "");
        this.trend = tag.getDoubleOr("trend", 0.0);
        this.lastUpdated = tag.getLongOr("lastUpdated", 0L);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("itemId", this.itemId);
        tag.putDouble("trend", this.trend);
        tag.putLong("lastUpdated", this.lastUpdated);
        return tag;
    }

    public String getItemId() { return itemId; }
    public double getTrend() { return trend; }
    public long getLastUpdated() { return lastUpdated; }

    public void setTrend(double trend) { this.trend = trend; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }
}
