package com.mcitemstockmarket.data;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

/**
 * 待成交的买卖委托。
 * 提交后不会立即成交，而是在下一次每分钟价格调整时，
 * 以调整后的新价格尝试执行。
 */
public class PendingOrder {
    public enum Type { BUY, SELL }

    private UUID id;
    private UUID playerId;
    private Type type;
    private String stockFullName;
    private int quantity;
    // 提交时间
    private long createdAt;

    public PendingOrder(UUID playerId, Type type, String stockFullName, int quantity) {
        this.id = UUID.randomUUID();
        this.playerId = playerId;
        this.type = type;
        this.stockFullName = stockFullName;
        this.quantity = quantity;
        this.createdAt = System.currentTimeMillis();
    }

    public PendingOrder(CompoundTag tag) {
        this.id = UUID.fromString(tag.getStringOr("id", "00000000-0000-0000-0000-000000000000"));
        this.playerId = UUID.fromString(tag.getStringOr("playerId", "00000000-0000-0000-0000-000000000000"));
        this.type = Type.valueOf(tag.getStringOr("type", "BUY"));
        this.stockFullName = tag.getStringOr("stockFullName", "");
        this.quantity = tag.getIntOr("quantity", 0);
        this.createdAt = tag.getLongOr("createdAt", 0L);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", this.id.toString());
        tag.putString("playerId", this.playerId.toString());
        tag.putString("type", this.type.name());
        tag.putString("stockFullName", this.stockFullName);
        tag.putInt("quantity", this.quantity);
        tag.putLong("createdAt", this.createdAt);
        return tag;
    }

    // ========== Getters ==========
    public UUID getId() { return id; }
    public UUID getPlayerId() { return playerId; }
    public Type getType() { return type; }
    public String getStockFullName() { return stockFullName; }
    public int getQuantity() { return quantity; }
    public long getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return "PendingOrder{" + type + " " + stockFullName + " x" + quantity + "}";
    }
}
