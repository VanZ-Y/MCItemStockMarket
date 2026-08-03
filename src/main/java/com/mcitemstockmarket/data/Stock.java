package com.mcitemstockmarket.data;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.mcitemstockmarket.Config;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 表示一只股票。
 */
public class Stock {
    private String fullName;
    private String prefix;
    private String itemId;
    private String momentumSuffix;
    private double price;
    private boolean delisted;
    private boolean momentum;
    private long delistedAt;
    private long relaunchAt;
    private int cumulativeBuys;
    private int cumulativeSells;
    // 当日（24分钟周期）开盘价，用于涨跌停板（±10%）计算
    private double dayOpenPrice;
    // 历史点：long[0]=timestamp_ms, long[1]=Double.doubleToLongBits(price)
    private Deque<long[]> priceHistory = new ArrayDeque<>();
    // 月级历史：每 20 分钟（1 游戏天）记录一个点，最多 30 个，满后删除最早的（环形覆盖）
    private Deque<long[]> monthlyHistory = new ArrayDeque<>();
    private static final int MAX_MONTHLY_POINTS = 30;

    public Stock(String prefix, String itemId, String momentumSuffix, double initialPrice) {
        this(prefix, itemId, momentumSuffix, initialPrice, true);
    }

    /**
     * @param recordInitialPoint 若为 false（反序列化/网络解码用），不立即记录初始点。
     */
    public Stock(String prefix, String itemId, String momentumSuffix, double initialPrice,
                 boolean recordInitialPoint) {
        this.prefix = prefix;
        this.itemId = itemId;
        this.momentumSuffix = momentumSuffix == null ? "" : momentumSuffix;
        this.price = initialPrice;
        this.momentum = !this.momentumSuffix.isEmpty();
        this.delisted = false;
        this.delistedAt = 0L;
        this.relaunchAt = 0L;
        this.cumulativeBuys = 0;
        this.cumulativeSells = 0;
        this.dayOpenPrice = initialPrice;
        updateFullName();
        if (recordInitialPoint) {
            recordPriceNow();
            recordMonthlyPoint(System.currentTimeMillis(), initialPrice);
        }
    }

    public Stock(CompoundTag tag) {
        this.fullName = tag.getStringOr("fullName", "");
        this.prefix = tag.getStringOr("prefix", "");
        this.itemId = tag.getStringOr("itemId", "");
        this.momentumSuffix = tag.getStringOr("momentumSuffix", "");
        this.price = tag.getDoubleOr("price", 0.0);
        this.delisted = tag.getBooleanOr("delisted", false);
        this.momentum = tag.getBooleanOr("momentum", false);
        this.delistedAt = tag.getLongOr("delistedAt", 0L);
        this.relaunchAt = tag.getLongOr("relaunchAt", 0L);
        this.cumulativeBuys = tag.getIntOr("cumulativeBuys", 0);
        this.cumulativeSells = tag.getIntOr("cumulativeSells", 0);
        this.dayOpenPrice = tag.getDoubleOr("dayOpenPrice", this.price);
        this.priceHistory = new ArrayDeque<>();
        ListTag hist = tag.getListOrEmpty("priceHistory");
        for (int i = 0; i < hist.size(); i++) {
            CompoundTag p = hist.getCompoundOrEmpty(i);
            this.priceHistory.addLast(new long[] { p.getLongOr("t", 0L), Double.doubleToLongBits(p.getDoubleOr("p", 0.0)) });
        }
        this.monthlyHistory = new ArrayDeque<>();
        ListTag mhist = tag.getListOrEmpty("monthlyHistory");
        for (int i = 0; i < mhist.size(); i++) {
            CompoundTag p = mhist.getCompoundOrEmpty(i);
            this.monthlyHistory.addLast(new long[] { p.getLongOr("t", 0L), Double.doubleToLongBits(p.getDoubleOr("p", 0.0)) });
        }
        // 旧存档无月级数据：从小时级历史回填（约每 24 个点 = 1 游戏天），最多 30 个
        if (this.monthlyHistory.isEmpty() && !this.priceHistory.isEmpty()) {
            List<long[]> pts = new ArrayList<>(this.priceHistory);
            for (int i = 0; i < pts.size() && this.monthlyHistory.size() < MAX_MONTHLY_POINTS; i += 24) {
                this.monthlyHistory.addLast(pts.get(i));
            }
        }
    }

    private void updateFullName() {
        String baseItemName = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
        String suffix = momentumSuffix.isEmpty() ? "" : "-" + momentumSuffix;
        this.fullName = prefix + "-" + baseItemName + "股" + suffix;
    }

    /** 保存时货币/价格值统一保留两位小数。*/
    private static double round2(double v) { return Math.round(v * 100.0) / 100.0; }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("fullName", this.fullName);
        tag.putString("prefix", this.prefix);
        tag.putString("itemId", this.itemId);
        tag.putString("momentumSuffix", this.momentumSuffix);
        tag.putDouble("price", round2(this.price));
        tag.putBoolean("delisted", this.delisted);
        tag.putBoolean("momentum", this.momentum);
        tag.putLong("delistedAt", this.delistedAt);
        tag.putLong("relaunchAt", this.relaunchAt);
        tag.putInt("cumulativeBuys", this.cumulativeBuys);
        tag.putInt("cumulativeSells", this.cumulativeSells);
        tag.putDouble("dayOpenPrice", round2(this.dayOpenPrice));
        ListTag hist = new ListTag();
        for (long[] p : this.priceHistory) {
            CompoundTag e = new CompoundTag();
            e.putLong("t", p[0]);
            e.putDouble("p", round2(Double.longBitsToDouble(p[1])));
            hist.add(e);
        }
        tag.put("priceHistory", hist);
        ListTag mhist = new ListTag();
        for (long[] p : this.monthlyHistory) {
            CompoundTag e = new CompoundTag();
            e.putLong("t", p[0]);
            e.putDouble("p", round2(Double.longBitsToDouble(p[1])));
            mhist.add(e);
        }
        tag.put("monthlyHistory", mhist);
        return tag;
    }

    public void recordPriceNow() {
        long now = System.currentTimeMillis();
        this.priceHistory.addLast(new long[] { now, Double.doubleToLongBits(this.price) });
        trimHistory();
    }

    private void trimHistory() {
        int max = Config.HISTORY_RETENTION_MINUTES.get() + 10;
        while (this.priceHistory.size() > max) this.priceHistory.removeFirst();
        long cutoff = System.currentTimeMillis() - (long) Config.HISTORY_RETENTION_MINUTES.get() * 50_000L;
        while (!this.priceHistory.isEmpty() && this.priceHistory.peekFirst()[0] < cutoff) {
            this.priceHistory.removeFirst();
        }
    }

    // ========== 月级历史（每 20 分钟 / 1 游戏天记录一个点，最多 30 个，环形覆盖）==========
    /** 记录一个月级数据点；满 30 个后删除最早的，再记录最新的。*/
    public void recordMonthlyPoint(long ts, double price) {
        this.monthlyHistory.addLast(new long[] { ts, Double.doubleToLongBits(price) });
        while (this.monthlyHistory.size() > MAX_MONTHLY_POINTS) this.monthlyHistory.removeFirst();
    }

    /** 以当前价格记录一个月级数据点（由每日 tick 每 20 分钟调用）。*/
    public void recordMonthlyNow() {
        recordMonthlyPoint(System.currentTimeMillis(), this.price);
    }

    public List<long[]> getMonthlyHistoryCopy() { return new ArrayList<>(this.monthlyHistory); }

    /** 月级涨跌幅：(现价 - 最早月级点) / 最早月级点。*/
    public double getMonthChangePercent() {
        if (this.monthlyHistory.size() < 2) return 0.0;
        long[] first = this.monthlyHistory.peekFirst();
        double p0 = Double.longBitsToDouble(first[1]);
        if (p0 <= 0) return 0.0;
        return (this.price - p0) / p0;
    }

    public double getPriceMinutesAgo(int minutes) {
        if (this.priceHistory.isEmpty()) return this.price;
        long target = System.currentTimeMillis() - (long) minutes * 50_000L;
        long[] best = this.priceHistory.peekFirst();
        for (long[] p : this.priceHistory) {
            if (p[0] <= target) best = p;
            else break;
        }
        return Double.longBitsToDouble(best[1]);
    }

    public double getChangePercent(double oldPrice) {
        if (oldPrice <= 0) return 0.0;
        return (this.price - oldPrice) / oldPrice;
    }

    public void addBuyCount(int amount) { this.cumulativeBuys += amount; }
    public void addSellCount(int amount) { this.cumulativeSells += amount; }

    /** 当前自上次 tick 起累计的买入股数（用于"玩家影响值"查询，不消耗）。*/
    public int getCumulativeBuys() { return this.cumulativeBuys; }
    /** 当前自上次 tick 起累计的卖出股数（用于"玩家影响值"查询，不消耗）。*/
    public int getCumulativeSells() { return this.cumulativeSells; }

    public int consumeBuyImpacts() {
        int threshold = Config.TRADE_VOLUME_THRESHOLD.get();
        if (threshold <= 0) return 0;
        int impacts = this.cumulativeBuys / threshold;
        this.cumulativeBuys = this.cumulativeBuys % threshold;
        return impacts;
    }
    public int consumeSellImpacts() {
        int threshold = Config.TRADE_VOLUME_THRESHOLD.get();
        if (threshold <= 0) return 0;
        int impacts = this.cumulativeSells / threshold;
        this.cumulativeSells = this.cumulativeSells % threshold;
        return impacts;
    }

    public String getFullName() { return fullName; }
    public String getPrefix() { return prefix; }
    public String getItemId() { return itemId; }
    public String getMomentumSuffix() { return momentumSuffix; }
    public double getPrice() { return price; }
    public boolean isDelisted() { return delisted; }
    public boolean isMomentum() { return momentum; }
    public long getDelistedAt() { return delistedAt; }
    public long getRelaunchAt() { return relaunchAt; }
    public void setPrice(double price) {
        // 跟风股票无保护价，允许跌至 0；普通股票保留最小价格地板
        this.price = this.momentum ? Math.max(0.0, price) : Math.max(0.0001, price);
    }
    public void setDelisted(boolean delisted) { this.delisted = delisted; }
    public void setDelistedAt(long delistedAt) { this.delistedAt = delistedAt; }
    public void setRelaunchAt(long relaunchAt) { this.relaunchAt = relaunchAt; }
    public List<long[]> getPriceHistoryCopy() { return new ArrayList<>(this.priceHistory); }

    /** 当日（24分钟周期）开盘价。*/
    public double getDayOpenPrice() { return dayOpenPrice; }
    public void setDayOpenPrice(double dayOpenPrice) { this.dayOpenPrice = dayOpenPrice; }

    /** 当日涨跌幅：(price - dayOpenPrice) / dayOpenPrice。*/
    public double getDayChangePercent() {
        if (dayOpenPrice <= 0) return 0.0;
        return (this.price - dayOpenPrice) / dayOpenPrice;
    }

    /** 是否涨停（当日涨幅达到上限）：仅普通股票，跟风股票不受限。*/
    public boolean isLimitUp() {
        if (this.momentum) return false;
        if (this.dayOpenPrice <= 0) return false;
        double limit = Config.DAILY_LIMIT_PERCENT.get();
        return this.price >= this.dayOpenPrice * (1.0 + limit) - 1e-9;
    }

    /** 是否跌停（当日跌幅达到下限）：仅普通股票，跟风股票不受限。*/
    public boolean isLimitDown() {
        if (this.momentum) return false;
        if (this.dayOpenPrice <= 0) return false;
        double limit = Config.DAILY_LIMIT_PERCENT.get();
        return this.price <= this.dayOpenPrice * (1.0 - limit) + 1e-9;
    }

    /**
     * 本地化显示名（前缀 + 游戏内物品名 + 股 + 后缀）。
     * 返回的是由 translatable 组件构成的 Component，客户端会按自身语言渲染。
     * 服务端广播时直接发送该 Component，客户端各自翻译。
     */
    public Component getDisplayNameComponent() {
        MutableComponent c = prefixComponent();
        c.append(Component.literal("-"));
        Item item = resolveItemSafe();
        if (item != null) {
            c.append(new ItemStack(item).getHoverName());
        } else {
            String base = this.itemId.contains(":") ? this.itemId.substring(this.itemId.indexOf(':') + 1) : this.itemId;
            c.append(Component.literal(base));
        }
        c.append(Component.translatable("mcitemstockmarket.stock_word"));
        if (!this.momentumSuffix.isEmpty()) {
            c.append(Component.literal("-"));
            c.append(Component.translatable("mcitemstockmarket.suffix." + this.momentumSuffix));
        }
        return c;
    }

    /**
     * 前缀组件解析优先级：
     * ① 配置前缀（旧存档/use_entity_prefixes=false）→ 本地化 lang key（mcitemstockmarket.prefix.*）；
     * ② 生物实体前缀（如 villager / modid:entity）→ 游戏内注册实体名（自动随语言本地化）；
     * ③ 其他（如带序号的衍生前缀 "villager2"）→ 原文。
     */
    private MutableComponent prefixComponent() {
        try {
            for (Object o : Config.STOCK_PREFIXES.get()) {
                if (this.prefix.equals(o)) {
                    return Component.translatable("mcitemstockmarket.prefix." + this.prefix);
                }
            }
        } catch (Exception ignore) {
        }
        EntityType<?> et = resolveEntityPrefix(this.prefix);
        if (et != null) {
            return Component.translatable(et.getDescriptionId());
        }
        return Component.literal(this.prefix);
    }

    /** 尝试把前缀解析为游戏内注册的实体类型（先按 minecraft 命名空间，再按完整 ID）。*/
    private static EntityType<?> resolveEntityPrefix(String prefix) {
        try {
            EntityType<?> et = BuiltInRegistries.ENTITY_TYPE.getValue(
                    ResourceLocation.fromNamespaceAndPath("minecraft", prefix));
            if (et != null) return et;
        } catch (Exception ignore) {
        }
        try {
            return BuiltInRegistries.ENTITY_TYPE.getValue(ResourceLocation.parse(prefix));
        } catch (Exception e) {
            return null;
        }
    }

    private Item resolveItemSafe() {
        try {
            Item it = BuiltInRegistries.ITEM.getValue(ResourceLocation.parse(this.itemId));
            if (it == null || it == Items.AIR) return null;
            return it;
        } catch (Exception e) {
            return null;
        }
    }

    @Override public String toString() {
        return "Stock{" + fullName + " price=" + price + " delisted=" + delisted + "}";
    }

    // 客户端侧缓存的涨跌幅（避免客户端无历史时算不准）
    public transient float cachedP1 = 0f;
    public transient float cachedP24 = 0f;
    public transient float cachedP168 = 0f;
    public transient float cachedPMonth = 0f;

    // ================= ByteBuf 网络编解码 =================
    public static final StreamCodec<ByteBuf, Stock> STREAM_CODEC = StreamCodec.of(
            Stock::encodeBuf, Stock::decodeBuf
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

    public static void encodeBuf(ByteBuf buf, Stock s) {
        writeUtf(buf, s.fullName);
        writeUtf(buf, s.prefix);
        writeUtf(buf, s.itemId);
        writeUtf(buf, s.momentumSuffix);
        buf.writeDouble(s.price);
        buf.writeBoolean(s.delisted);
        buf.writeBoolean(s.momentum);
        buf.writeDouble(s.dayOpenPrice);
        // 涨跌幅（客户端展示）
        float p1 = (float) s.getChangePercent(s.getPriceMinutesAgo(1));
        float p24 = (float) s.getChangePercent(s.getPriceMinutesAgo(24));
        float p168 = (float) s.getChangePercent(s.getPriceMinutesAgo(168));
        float pMonth = (float) s.getMonthChangePercent();
        buf.writeFloat(p1);
        buf.writeFloat(p24);
        buf.writeFloat(p168);
        buf.writeFloat(pMonth);
        // 最多200个历史点（足够覆盖168分钟折线图）
        int maxHist = 200;
        List<long[]> hist = new ArrayList<>(s.priceHistory);
        int count = Math.min(maxHist, hist.size());
        int startIdx = hist.size() - count;
        buf.writeInt(count);
        for (int i = 0; i < count; i++) {
            long[] pt = hist.get(startIdx + i);
            buf.writeLong(pt[0]);
            buf.writeDouble(Double.longBitsToDouble(pt[1]));
        }
        // 月级历史（最多 30 个点）
        buf.writeInt(s.monthlyHistory.size());
        for (long[] pt : s.monthlyHistory) {
            buf.writeLong(pt[0]);
            buf.writeDouble(Double.longBitsToDouble(pt[1]));
        }
    }

    public static Stock decodeBuf(ByteBuf buf) {
        String fn = readUtf(buf);
        String px = readUtf(buf);
        String iid = readUtf(buf);
        String sfx = readUtf(buf);
        double pr = buf.readDouble();
        boolean dl = buf.readBoolean();
        boolean mom = buf.readBoolean();
        double dayOpen = buf.readDouble();
        float cp1 = buf.readFloat();
        float cp24 = buf.readFloat();
        float cp168 = buf.readFloat();
        float cpMonth = buf.readFloat();
        // 构造股票但不立即记录价格（避免立即生成重复初始点）
        Stock s = new Stock(px, iid, sfx, pr, false);
        s.fullName = fn;
        s.delisted = dl;
        s.momentum = mom;
        s.dayOpenPrice = dayOpen;
        s.cachedP1 = cp1;
        s.cachedP24 = cp24;
        s.cachedP168 = cp168;
        s.cachedPMonth = cpMonth;
        // 读取历史
        int n = buf.readInt();
        s.priceHistory = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            long t = buf.readLong();
            double p = buf.readDouble();
            s.priceHistory.addLast(new long[] { t, Double.doubleToLongBits(p) });
        }
        // 读取月级历史
        int mn = buf.readInt();
        s.monthlyHistory = new ArrayDeque<>();
        for (int i = 0; i < mn; i++) {
            long t = buf.readLong();
            double p = buf.readDouble();
            s.monthlyHistory.addLast(new long[] { t, Double.doubleToLongBits(p) });
        }
        return s;
    }

    // list codec
    public static final StreamCodec<ByteBuf, List<Stock>> LIST_STREAM_CODEC = StreamCodec.of(
            (buf, list) -> {
                buf.writeInt(list.size());
                for (Stock s : list) encodeBuf(buf, s);
            },
            buf -> {
                int n = buf.readInt();
                List<Stock> r = new ArrayList<>(n);
                for (int i = 0; i < n; i++) r.add(decodeBuf(buf));
                return r;
            }
    );

    // 客户端显示辅助：使用缓存涨跌幅（无历史时回退）
    public float getDisplayP1() {
        if (this.priceHistory.size() >= 2) return (float) getChangePercent(getPriceMinutesAgo(1));
        return cachedP1;
    }
    public float getDisplayP24() {
        if (this.priceHistory.size() >= 2) return (float) getChangePercent(getPriceMinutesAgo(24));
        return cachedP24;
    }
    public float getDisplayP168() {
        if (this.priceHistory.size() >= 2) return (float) getChangePercent(getPriceMinutesAgo(168));
        return cachedP168;
    }
    public float getDisplayPMonth() {
        if (this.monthlyHistory.size() >= 2) return (float) getMonthChangePercent();
        return cachedPMonth;
    }

    /** 客户端侧接收价格更新时：保留已有历史，追加新价格点 */
    public void mergeFromUpdate(Stock newer) {
        this.price = newer.price;
        this.delisted = newer.delisted;
        this.momentum = newer.momentum;
        this.cachedP1 = newer.cachedP1;
        this.cachedP24 = newer.cachedP24;
        this.cachedP168 = newer.cachedP168;
        this.cachedPMonth = newer.cachedPMonth;
        // 合并历史：把 newer 里时间戳大于本地最后时间的点追加
        long last = 0L;
        if (!this.priceHistory.isEmpty()) last = this.priceHistory.peekLast()[0];
        for (long[] pt : newer.priceHistory) {
            if (pt[0] > last) {
                this.priceHistory.addLast(pt);
                last = pt[0];
            }
        }
        trimHistory();
        // 合并月级历史
        long lastM = 0L;
        if (!this.monthlyHistory.isEmpty()) lastM = this.monthlyHistory.peekLast()[0];
        for (long[] pt : newer.monthlyHistory) {
            if (pt[0] > lastM) {
                this.monthlyHistory.addLast(pt);
                lastM = pt[0];
            }
        }
        while (this.monthlyHistory.size() > MAX_MONTHLY_POINTS) this.monthlyHistory.removeFirst();
    }

    /**
     * 轻量增量价格补丁。仅携带当前价 + 涨跌幅 + 最新时间戳 + 最新月级点，
     * 不含小时级历史点。每只股票约 60 字节，相比全量历史（200 点 × 16B ≈ 3.2KB）
     * 减少约 98% 网络与分配开销。用于每分钟的周期性价格广播；
     * 客户端收到后合并到本地累积的历史曲线。
     */
    public record PricePatch(String fullName, double price, double dayOpenPrice,
                             float p1, float p24, float p168, float pMonth,
                             long monthTs, double monthPrice, long timestamp) {
        public static final StreamCodec<ByteBuf, PricePatch> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    writeUtf(buf, p.fullName());
                    buf.writeDouble(p.price());
                    buf.writeDouble(p.dayOpenPrice());
                    buf.writeFloat(p.p1());
                    buf.writeFloat(p.p24());
                    buf.writeFloat(p.p168());
                    buf.writeFloat(p.pMonth());
                    buf.writeLong(p.monthTs());
                    buf.writeDouble(p.monthPrice());
                    buf.writeLong(p.timestamp());
                },
                buf -> new PricePatch(
                        readUtf(buf),
                        buf.readDouble(),
                        buf.readDouble(),
                        buf.readFloat(),
                        buf.readFloat(),
                        buf.readFloat(),
                        buf.readFloat(),
                        buf.readLong(),
                        buf.readDouble(),
                        buf.readLong()
                ));
    }

    /** 服务端构造增量补丁：基于当前价与历史计算涨跌幅，附最新时间戳与最新月级点。*/
    public PricePatch toPatch() {
        float p1 = (float) getChangePercent(getPriceMinutesAgo(1));
        float p24 = (float) getChangePercent(getPriceMinutesAgo(24));
        float p168 = (float) getChangePercent(getPriceMinutesAgo(168));
        float pMonth = (float) getMonthChangePercent();
        long mts = 0L;
        double mp = 0.0;
        if (!this.monthlyHistory.isEmpty()) {
            long[] last = this.monthlyHistory.peekLast();
            mts = last[0];
            mp = Double.longBitsToDouble(last[1]);
        }
        long ts = this.priceHistory.isEmpty() ? System.currentTimeMillis() : this.priceHistory.peekLast()[0];
        return new PricePatch(this.fullName, this.price, this.dayOpenPrice, p1, p24, p168, pMonth, mts, mp, ts);
    }

    /** 客户端合并增量补丁：更新价格/涨跌幅/开盘价，并按时间戳追加历史点与月级点。*/
    public void mergePatch(PricePatch patch) {
        this.price = patch.price();
        this.dayOpenPrice = patch.dayOpenPrice();
        this.cachedP1 = patch.p1();
        this.cachedP24 = patch.p24();
        this.cachedP168 = patch.p168();
        this.cachedPMonth = patch.pMonth();
        long ts = patch.timestamp();
        if (ts > 0) {
            long last = this.priceHistory.isEmpty() ? 0L : this.priceHistory.peekLast()[0];
            if (ts > last) {
                this.priceHistory.addLast(new long[] { ts, Double.doubleToLongBits(patch.price()) });
                trimHistory();
            }
        }
        // 月级点：仅当服务端出现更新的月级点时追加（客户端本地也按 20 分钟间隔补点）
        if (patch.monthTs() > 0) {
            long lastM = this.monthlyHistory.isEmpty() ? 0L : this.monthlyHistory.peekLast()[0];
            if (patch.monthTs() > lastM) {
                this.monthlyHistory.addLast(new long[] { patch.monthTs(), Double.doubleToLongBits(patch.monthPrice()) });
                while (this.monthlyHistory.size() > MAX_MONTHLY_POINTS) this.monthlyHistory.removeFirst();
            }
        }
    }
}
