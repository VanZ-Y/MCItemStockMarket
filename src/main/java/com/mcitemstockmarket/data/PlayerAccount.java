package com.mcitemstockmarket.data;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.netty.buffer.ByteBuf;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.StreamCodec;

public class PlayerAccount {
    private UUID playerId;
    private double balance;
    private Map<String, Integer> holdings;
    // 每只股票的加权平均购入单价（用于持仓界面展示盈亏）
    private Map<String, Double> avgBuyPrice;

    public PlayerAccount(UUID playerId, double startingBalance) {
        this.playerId = playerId;
        this.balance = startingBalance;
        this.holdings = new HashMap<>();
        this.avgBuyPrice = new HashMap<>();
    }

    public PlayerAccount(CompoundTag tag) {
        this.playerId = UUID.fromString(tag.getStringOr("playerId", "00000000-0000-0000-0000-000000000000"));
        this.balance = tag.getDoubleOr("balance", 0.0);
        this.holdings = new HashMap<>();
        CompoundTag h = tag.getCompoundOrEmpty("holdings");
        for (String key : h.keySet()) {
            this.holdings.put(key, h.getIntOr(key, 0));
        }
        this.avgBuyPrice = new HashMap<>();
        CompoundTag avgs = tag.getCompoundOrEmpty("avgBuyPrice");
        for (String key : avgs.keySet()) {
            this.avgBuyPrice.put(key, avgs.getDoubleOr(key, 0.0));
        }
    }

    /** 保存时货币/价格值统一保留两位小数。*/
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("playerId", this.playerId.toString());
        tag.putDouble("balance", round2(this.balance));
        CompoundTag h = new CompoundTag();
        for (Map.Entry<String, Integer> e : this.holdings.entrySet()) {
            h.putInt(e.getKey(), e.getValue());
        }
        tag.put("holdings", h);
        CompoundTag avgs = new CompoundTag();
        for (Map.Entry<String, Double> e : this.avgBuyPrice.entrySet()) {
            avgs.putDouble(e.getKey(), round2(e.getValue()));
        }
        tag.put("avgBuyPrice", avgs);
        return tag;
    }

    public boolean canAfford(double amount) { return this.balance >= amount - 1e-6; }

    public void addBalance(double delta) {
        // 货币值统一保留两位小数（防止浮点累积误差）
        this.balance = Math.max(0.0, round2(this.balance + delta));
    }

    public int getHolding(String stockFullName) {
        return this.holdings.getOrDefault(stockFullName, 0);
    }

    /** 加权平均购入单价；未持仓返回 0。 */
    public double getAvgBuyPrice(String stockFullName) {
        return this.avgBuyPrice.getOrDefault(stockFullName, 0.0);
    }

    public void addHolding(String stockFullName, int amount) {
        int cur = getHolding(stockFullName);
        int next = cur + amount;
        if (next <= 0) {
            this.holdings.remove(stockFullName);
            this.avgBuyPrice.remove(stockFullName);
        } else {
            this.holdings.put(stockFullName, next);
        }
    }

    /** 买入：按成交价更新加权平均购入价，并增加持仓。 */
    public void recordBuy(String stockFullName, int qty, double price) {
        int oldQty = getHolding(stockFullName);
        double oldAvg = getAvgBuyPrice(stockFullName);
        double newAvg = oldQty <= 0 ? price : (oldAvg * oldQty + price * qty) / (oldQty + qty);
        this.avgBuyPrice.put(stockFullName, newAvg);
        addHolding(stockFullName, qty);
    }

    public boolean hasHolding(String stockFullName, int amount) {
        return getHolding(stockFullName) >= amount;
    }

    public UUID getPlayerId() { return playerId; }
    public double getBalance() { return balance; }
    /** 安全修复 #5：用于交易异常时回滚余额（同样保留两位小数）。*/
    public void setBalance(double balance) { this.balance = round2(balance); }
    public Map<String, Integer> getHoldings() { return new HashMap<>(holdings); }
    public Map<String, Double> getAvgBuyPrices() { return new HashMap<>(avgBuyPrice); }

    // ================= ByteBuf 网络编解码 =================
    public static final StreamCodec<ByteBuf, PlayerAccount> STREAM_CODEC = StreamCodec.of(
            PlayerAccount::encodeBuf, PlayerAccount::decodeBuf
    );

    private static void writeUtf(ByteBuf buf, String s) {
        byte[] bs = s.getBytes(StandardCharsets.UTF_8);
        buf.writeInt(bs.length);
        buf.writeBytes(bs);
    }
    private static String readUtf(ByteBuf buf) {
        int len = buf.readInt();
        byte[] bs = new byte[len];
        buf.readBytes(bs);
        return new String(bs, StandardCharsets.UTF_8);
    }

    public static void encodeBuf(ByteBuf buf, PlayerAccount a) {
        buf.writeLong(a.playerId.getMostSignificantBits());
        buf.writeLong(a.playerId.getLeastSignificantBits());
        buf.writeDouble(a.balance);
        buf.writeInt(a.holdings.size());
        for (Map.Entry<String, Integer> e : a.holdings.entrySet()) {
            writeUtf(buf, e.getKey());
            buf.writeInt(e.getValue());
        }
        buf.writeInt(a.avgBuyPrice.size());
        for (Map.Entry<String, Double> e : a.avgBuyPrice.entrySet()) {
            writeUtf(buf, e.getKey());
            buf.writeDouble(e.getValue());
        }
    }

    public static PlayerAccount decodeBuf(ByteBuf buf) {
        UUID pid = new UUID(buf.readLong(), buf.readLong());
        double bal = buf.readDouble();
        PlayerAccount a = new PlayerAccount(pid, bal);
        int n = buf.readInt();
        for (int i = 0; i < n; i++) {
            String k = readUtf(buf);
            int v = buf.readInt();
            a.holdings.put(k, v);
        }
        int m = buf.readInt();
        for (int i = 0; i < m; i++) {
            String k = readUtf(buf);
            double v = buf.readDouble();
            a.avgBuyPrice.put(k, v);
        }
        return a;
    }
}
