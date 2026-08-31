package com.mcitemstockmarket.data;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mcitemstockmarket.Config;
import com.mcitemstockmarket.MCItemStockMarket;

import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 服务端全局持久化数据。
 * 持有所有股票、趋势、玩家账户、委托队列。
 * 存档以 JSON 格式存放在世界目录下的独立文件夹 {@code <世界目录>/stockmarket/} 中：
 *  - market.json：股票市场数据（股票、趋势、委托、跟风事件等）
 *  - players.json：玩家账户数据（单独存放）
 * 由 ServerEventHandlers 在服务器 tick 中定期（每 20 秒）以及服务器停止时落盘。
 */
public class StockMarketSavedData {
    // ========== 存档位置：世界目录下的独立文件夹 ==========
    private static final String DATA_FOLDER = "stockmarket";
    private static final String MARKET_FILE = "market.json";
    private static final String PLAYERS_FILE = "players.json";
    /** 当前服务器的数据实例；服务器停止时由 {@link #resetInstance()} 清空。*/
    private static StockMarketSavedData instance;
    /** 是否有未写入磁盘的股市数据改动（股票/价格/趋势/委托等，写 market.json）。*/
    private boolean dirty;
    /** 是否有未写入磁盘的玩家账户改动（写 players.json）。*/
    private boolean playersDirty;

    // 股票全名 -> Stock
    private Map<String, Stock> stocks = new HashMap<>();
    // 物品ID -> ItemTrend
    private Map<String, ItemTrend> itemTrends = new HashMap<>();
    // 玩家 UUID -> 账户
    private Map<UUID, PlayerAccount> playerAccounts = new HashMap<>();
    // 待成交委托
    private List<PendingOrder> pendingOrders = new ArrayList<>();
    // 跟风冷却：物品ID -> 下一次可触发时间(ms)
    private Map<String, Long> momentumCooldown = new HashMap<>();
    // 最近跟风事件时间戳（用于全局限频：窗口期内最多 N 次）
    private Deque<Long> recentMomentumEvents = new ArrayDeque<>();
    // 下一次"普通股票定期上市"尝试时间(ms)；0 表示尚未调度
    private long nextListingAttempt;

    // 是否已执行过首次初始化（创建随机股票）
    private boolean initialized;
    // 上次每分钟执行时间
    private long lastMinuteTick;
    // 上次趋势更新时间
    private long lastTrendTick;
    // 上次"每日（20分钟）"重置开盘价 + 每日播报的时间
    private long lastDayOpenReset;

    public StockMarketSavedData() {
        this.initialized = false;
        this.lastMinuteTick = System.currentTimeMillis();
        this.lastTrendTick = System.currentTimeMillis();
        this.lastDayOpenReset = System.currentTimeMillis();
        this.nextListingAttempt = 0L;
    }

    public static StockMarketSavedData load(CompoundTag tag) {
        StockMarketSavedData data = new StockMarketSavedData();
        data.initialized = tag.getBooleanOr("initialized", false);
        data.lastMinuteTick = tag.getLongOr("lastMinuteTick", System.currentTimeMillis());
        data.lastTrendTick = tag.getLongOr("lastTrendTick", System.currentTimeMillis());
        data.lastDayOpenReset = tag.getLongOr("lastDayOpenReset", System.currentTimeMillis());
        data.nextListingAttempt = tag.getLongOr("nextListingAttempt", 0L);

        // recentMomentumEvents
        data.recentMomentumEvents = new ArrayDeque<>();
        ListTag evTag = tag.getListOrEmpty("recentMomentumEvents");
        for (int i = 0; i < evTag.size(); i++) {
            data.recentMomentumEvents.addLast(evTag.getCompoundOrEmpty(i).getLongOr("t", 0L));
        }

        // Stocks
        data.stocks = new HashMap<>();
        ListTag stocksTag = tag.getListOrEmpty("stocks");
        for (int i = 0; i < stocksTag.size(); i++) {
            Stock s = new Stock(stocksTag.getCompoundOrEmpty(i));
            data.stocks.put(s.getFullName(), s);
        }

        // ItemTrends
        data.itemTrends = new HashMap<>();
        ListTag trendsTag = tag.getListOrEmpty("itemTrends");
        for (int i = 0; i < trendsTag.size(); i++) {
            ItemTrend t = new ItemTrend(trendsTag.getCompoundOrEmpty(i));
            data.itemTrends.put(t.getItemId(), t);
        }

        // PlayerAccounts
        data.playerAccounts = new HashMap<>();
        ListTag acctsTag = tag.getListOrEmpty("playerAccounts");
        for (int i = 0; i < acctsTag.size(); i++) {
            PlayerAccount a = new PlayerAccount(acctsTag.getCompoundOrEmpty(i));
            data.playerAccounts.put(a.getPlayerId(), a);
        }

        // PendingOrders
        data.pendingOrders = new ArrayList<>();
        ListTag ordersTag = tag.getListOrEmpty("pendingOrders");
        for (int i = 0; i < ordersTag.size(); i++) {
            data.pendingOrders.add(new PendingOrder(ordersTag.getCompoundOrEmpty(i)));
        }

        // MomentumCooldown
        CompoundTag mcTag = tag.getCompoundOrEmpty("momentumCooldown");
        data.momentumCooldown = new HashMap<>();
        for (String key : mcTag.keySet()) {
            data.momentumCooldown.put(key, mcTag.getLongOr(key, 0L));
        }
        return data;
    }

    /** 序列化股票市场数据（不含玩家账户，玩家账户单独存 players.dat）。*/
    public CompoundTag saveMarketTag() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("initialized", this.initialized);
        tag.putLong("lastMinuteTick", this.lastMinuteTick);
        tag.putLong("lastTrendTick", this.lastTrendTick);
        tag.putLong("lastDayOpenReset", this.lastDayOpenReset);
        tag.putLong("nextListingAttempt", this.nextListingAttempt);

        ListTag evTag = new ListTag();
        for (long ts : this.recentMomentumEvents) {
            CompoundTag e = new CompoundTag();
            e.putLong("t", ts);
            evTag.add(e);
        }
        tag.put("recentMomentumEvents", evTag);

        ListTag stocksTag = new ListTag();
        for (Stock s : this.stocks.values()) stocksTag.add(s.save());
        tag.put("stocks", stocksTag);

        ListTag trendsTag = new ListTag();
        for (ItemTrend t : this.itemTrends.values()) trendsTag.add(t.save());
        tag.put("itemTrends", trendsTag);

        ListTag ordersTag = new ListTag();
        for (PendingOrder o : this.pendingOrders) ordersTag.add(o.save());
        tag.put("pendingOrders", ordersTag);

        CompoundTag mcTag = new CompoundTag();
        for (Map.Entry<String, Long> e : this.momentumCooldown.entrySet()) {
            mcTag.putLong(e.getKey(), e.getValue());
        }
        tag.put("momentumCooldown", mcTag);
        return tag;
    }

    /** 序列化玩家账户数据（单独存 players.dat）。*/
    public CompoundTag savePlayersTag() {
        CompoundTag tag = new CompoundTag();
        ListTag acctsTag = new ListTag();
        for (PlayerAccount a : this.playerAccounts.values()) acctsTag.add(a.save());
        tag.put("playerAccounts", acctsTag);
        return tag;
    }

    /** 从 MinecraftServer 获取（或创建）全局数据。数据存放于 <世界目录>/stockmarket/ 下。*/
    public static StockMarketSavedData get(MinecraftServer server) {
        if (server == null) return null;
        if (instance == null) {
            instance = loadOrCreate(server);
            // 游戏开始时为所有上市股票生成初始趋势（每 20 分钟趋势启动时也随机生成）
            instance.ensureInitialTrends();
        }
        return instance;
    }

    /** 为所有上市股票的物品生成初始趋势值（缺失时才生成，启动后每 20 分钟再由引擎重随机）。*/
    private void ensureInitialTrends() {
        Random rnd = new Random();
        double max = Config.TREND_FLUCTUATION_PERCENT.get();
        boolean created = false;
        for (Stock s : this.stocks.values()) {
            if (s.isDelisted()) continue;
            if (!this.itemTrends.containsKey(s.getItemId())) {
                double r = (rnd.nextDouble() * 2 - 1) * max;
                this.itemTrends.put(s.getItemId(), new ItemTrend(s.getItemId(), r));
                created = true;
            }
        }
        if (created) this.setDirty();
    }

    /** 服务器停止时重置静态实例，防止切换世界时残留旧数据。*/
    public static void resetInstance() {
        instance = null;
    }

    private static Path dataFolder(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DATA_FOLDER);
    }

    /** 从独立文件夹加载 market.json / players.json；不存在则新建。*/
    private static StockMarketSavedData loadOrCreate(MinecraftServer server) {
        try {
            Path dir = dataFolder(server);
            Path marketFile = dir.resolve(MARKET_FILE);
            if (Files.exists(marketFile)) {
                StockMarketSavedData d = load(readJsonTag(marketFile));
                Path playersFile = dir.resolve(PLAYERS_FILE);
                if (Files.exists(playersFile)) {
                    d.playerAccounts = loadPlayers(readJsonTag(playersFile));
                }
                d.initializeIfNeeded();
                return d;
            }
        } catch (Exception e) {
            MCItemStockMarket.LOGGER.error("[股市] 存档读取失败，将创建新存档: {}", e.toString());
        }
        StockMarketSavedData d = new StockMarketSavedData();
        d.initializeIfNeeded();
        return d;
    }

    /** 写入磁盘（增量）：仅写入有改动的文件，market.json 与 players.json 各自独立脏标记。*/
    public void saveToDisk(MinecraftServer server) {
        try {
            Path dir = dataFolder(server);
            Files.createDirectories(dir);
            if (this.dirty) {
                Files.writeString(dir.resolve(MARKET_FILE), GSON.toJson(nbtToJson(saveMarketTag())));
                this.dirty = false;
            }
            if (this.playersDirty) {
                Files.writeString(dir.resolve(PLAYERS_FILE), GSON.toJson(nbtToJson(savePlayersTag())));
                this.playersDirty = false;
            }
        } catch (Exception e) {
            MCItemStockMarket.LOGGER.error("[股市] 存档写入失败: {}", e.toString());
        }
    }

    /** 强制写入两个文件（服务器停止时调用，确保所有数据落盘）。*/
    public void saveAll(MinecraftServer server) {
        this.dirty = true;
        this.playersDirty = true;
        saveToDisk(server);
    }

    // ========== JSON 编解码（CompoundTag ↔ JsonElement，便于人工阅读/编辑） ==========
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 读取 JSON 文件并解析为 CompoundTag。*/
    private static CompoundTag readJsonTag(Path file) throws java.io.IOException {
        return (CompoundTag) jsonToNbt(JsonParser.parseString(Files.readString(file)));
    }

    /** CompoundTag → JsonElement（递归）。*/
    private static JsonElement nbtToJson(Tag tag) {
        if (tag instanceof CompoundTag c) {
            JsonObject obj = new JsonObject();
            for (String key : c.keySet()) {
                obj.add(key, nbtToJson(c.get(key)));
            }
            return obj;
        }
        if (tag instanceof ListTag l) {
            JsonArray arr = new JsonArray();
            for (int i = 0; i < l.size(); i++) {
                arr.add(nbtToJson(l.get(i)));
            }
            return arr;
        }
        if (tag instanceof StringTag s) return new JsonPrimitive(s.value());
        if (tag instanceof ByteTag b) return new JsonPrimitive(b.byteValue() != 0); // putBoolean 存储为 ByteTag
        if (tag instanceof NumericTag n) {
            // 整数保持为整数（时间戳等），小数按 double 输出
            if (n.doubleValue() == Math.floor(n.doubleValue()) && !Double.isInfinite(n.doubleValue())) {
                return new JsonPrimitive(n.longValue());
            }
            return new JsonPrimitive(n.doubleValue());
        }
        return new JsonPrimitive("");
    }

    /** JsonElement → CompoundTag（递归）。列表元素类型按首元素推断。*/
    private static Tag jsonToNbt(JsonElement el) {
        if (el.isJsonObject()) {
            CompoundTag c = new CompoundTag();
            for (Map.Entry<String, JsonElement> e : el.getAsJsonObject().entrySet()) {
                c.put(e.getKey(), jsonToNbt(e.getValue()));
            }
            return c;
        }
        if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            ListTag list = new ListTag();
            if (arr.isEmpty()) return list;
            Tag sample = jsonToNbt(arr.get(0));
            if (sample instanceof CompoundTag) {
                for (JsonElement x : arr) list.add(jsonToNbt(x));
            } else if (sample instanceof StringTag) {
                for (JsonElement x : arr) list.add(StringTag.valueOf(x.getAsString()));
            } else if (sample instanceof ByteTag) {
                for (JsonElement x : arr) list.add(ByteTag.valueOf(x.getAsBoolean()));
            } else if (sample instanceof LongTag) {
                for (JsonElement x : arr) list.add(LongTag.valueOf(x.getAsLong()));
            } else {
                for (JsonElement x : arr) list.add(DoubleTag.valueOf(x.getAsDouble()));
            }
            return list;
        }
        JsonPrimitive p = el.getAsJsonPrimitive();
        if (p.isBoolean()) return ByteTag.valueOf(p.getAsBoolean());
        if (p.isNumber()) {
            BigDecimal bd = p.getAsBigDecimal();
            if (bd.stripTrailingZeros().scale() <= 0) {
                return LongTag.valueOf(bd.longValue());
            }
            return DoubleTag.valueOf(bd.doubleValue());
        }
        return StringTag.valueOf(p.getAsString());
    }

    // ========== 脏标记（增量写盘：股市数据与玩家账户各自独立） ==========
    public void setDirty() { this.dirty = true; }
    public boolean isDirty() { return this.dirty; }
    public void clearDirty() { this.dirty = false; }
    public void markPlayersDirty() { this.playersDirty = true; }
    public boolean isPlayersDirty() { return this.playersDirty; }
    public void clearPlayersDirty() { this.playersDirty = false; }

    /** 玩家账户单独存储：加载 players.dat。*/
    private static Map<UUID, PlayerAccount> loadPlayers(CompoundTag tag) {
        Map<UUID, PlayerAccount> map = new HashMap<>();
        ListTag acctsTag = tag.getListOrEmpty("playerAccounts");
        for (int i = 0; i < acctsTag.size(); i++) {
            PlayerAccount a = new PlayerAccount(acctsTag.getCompoundOrEmpty(i));
            map.put(a.getPlayerId(), a);
        }
        return map;
    }

    /** 首次启动时随机创建股票（仅执行一次，幂等）。*/
    private synchronized void initializeIfNeeded() {
        if (this.initialized) return;
        createRandomInitialStocks();
        this.initialized = true;
        this.setDirty();
    }

    /** 首次启动：随机创建 N 只股票，起始价 = 该物品在 initial_prices 表中的初始价，名称不重复。*/
    private void createRandomInitialStocks() {
        int target = Config.INITIAL_RANDOM_STOCK_COUNT.get();
        if (target <= 0) {
            MCItemStockMarket.LOGGER.info("StockMarket first-launch: initial_random_stock_count=0, skip");
            return;
        }
        List<String> prefixes = Config.getStockPrefixPool();
        List<String> itemPool = collectProtectedItems();
        if (prefixes.isEmpty() || itemPool.isEmpty()) {
            MCItemStockMarket.LOGGER.warn("StockMarket first-launch: no prefixes or protected items, skip");
            return;
        }
        Random rnd = new Random();
        Set<String> usedNames = new HashSet<>();
        int maxPerItem = Config.NEW_LISTING_MAX_PER_ITEM.get();
        int created = 0;
        int attempts = 0;
        int maxAttempts = target * 25;
        while (created < target && attempts < maxAttempts) {
            attempts++;
            String prefix = prefixes.get(rnd.nextInt(prefixes.size()));
            String itemId = itemPool.get(rnd.nextInt(itemPool.size()));
            // 确保单种物品的普通股票不超过上限（默认 3），超过则换一个物品重试
            long countForItem = this.stocks.values().stream()
                    .filter(x -> x.getItemId().equals(itemId))
                    .count();
            if (countForItem >= maxPerItem) continue;
            String baseName = itemId.contains(":") ? itemId.substring(itemId.indexOf(':') + 1) : itemId;
            String fullName = prefix + "-" + baseName + "股";
            if (usedNames.contains(fullName)) continue;
            if (this.stocks.containsKey(fullName)) { usedNames.add(fullName); continue; }
            double price = Config.getInitialPrice(itemId);
            Stock s = new Stock(prefix, itemId, "", price);
            this.stocks.put(s.getFullName(), s);
            ensureItemTrend(itemId);
            usedNames.add(fullName);
            created++;
        }
        MCItemStockMarket.LOGGER.info("StockMarket first-launch: created {} random stocks (target {})", created, target);
    }

    /** 收集所有配置了保护价的物品 ID。*/
    public static List<String> collectProtectedItems() {
        List<String> ids = new ArrayList<>();
        for (Object o : Config.PROTECTION_PRICES.get()) {
            String s = String.valueOf(o);
            int eq = s.indexOf('=');
            if (eq > 0) ids.add(s.substring(0, eq).trim());
        }
        return ids;
    }

    public void ensureItemTrend(String itemId) {
        this.itemTrends.computeIfAbsent(itemId, id -> new ItemTrend(id, 0.0));
    }

    // ========== 查询辅助 ==========
    public Stock findStockByName(String fullName) {
        return this.stocks.get(fullName);
    }

    public Stock findStockByPrefixItem(String prefix, String itemId, String momentumSuffix) {
        String sfx = momentumSuffix == null ? "" : momentumSuffix;
        for (Stock s : this.stocks.values()) {
            if (s.getPrefix().equals(prefix) && s.getItemId().equals(itemId)
                    && s.getMomentumSuffix().equals(sfx)) {
                return s;
            }
        }
        return null;
    }

    public Collection<Stock> getAllStocks() {
        return this.stocks.values();
    }

    public List<Stock> getStocksByItem(String itemId) {
        return this.stocks.values().stream()
                .filter(s -> s.getItemId().equals(itemId))
                .collect(Collectors.toList());
    }

    /** 某物品下的"普通"（非跟风）存活股票列表。*/
    public List<Stock> getActiveNormalStocksByItem(String itemId) {
        return this.stocks.values().stream()
                .filter(s -> s.getItemId().equals(itemId) && !s.isDelisted() && !s.isMomentum())
                .collect(Collectors.toList());
    }

    /** 某物品下的跟风股票数量（存活）。*/
    public int countActiveMomentumStocks(String itemId) {
        return (int) this.stocks.values().stream()
                .filter(s -> s.getItemId().equals(itemId) && !s.isDelisted() && s.isMomentum())
                .count();
    }

    public ItemTrend getItemTrend(String itemId) {
        ensureItemTrend(itemId);
        return this.itemTrends.get(itemId);
    }

    public Collection<ItemTrend> getAllItemTrends() {
        return this.itemTrends.values();
    }

    public PlayerAccount getOrCreateAccount(UUID playerId) {
        return this.playerAccounts.computeIfAbsent(playerId, pid -> {
            markPlayersDirty(); // 新建账户需写入 players.json
            return new PlayerAccount(pid, Config.STARTING_BALANCE.get());
        });
    }

    public PlayerAccount getAccount(UUID playerId) {
        return this.playerAccounts.get(playerId);
    }

    public List<PendingOrder> getPendingOrders() {
        return this.pendingOrders;
    }

    public void addOrder(PendingOrder order) {
        this.pendingOrders.add(order);
    }

    public void removeOrdersFor(UUID playerId) {
        this.pendingOrders.removeIf(o -> o.getPlayerId().equals(playerId));
    }

    public void addStock(Stock s) {
        this.stocks.put(s.getFullName(), s);
    }

    public void removeStock(Stock s) {
        this.stocks.remove(s.getFullName());
    }

    public Map<String, Long> getMomentumCooldown() {
        return this.momentumCooldown;
    }

    /** 返回所有已注册玩家账户的只读拷贝（用于退市补偿等）。*/
    public java.util.Collection<PlayerAccount> getAllAccounts() {
        return new java.util.ArrayList<>(this.playerAccounts.values());
    }

    // ========== Tick 时间戳 ==========
    public long getLastMinuteTick() { return lastMinuteTick; }
    public void setLastMinuteTick(long t) { this.lastMinuteTick = t; }
    public long getLastTrendTick() { return lastTrendTick; }
    public void setLastTrendTick(long t) { this.lastTrendTick = t; }
    public long getLastDayOpenReset() { return lastDayOpenReset; }
    public void setLastDayOpenReset(long t) { this.lastDayOpenReset = t; }

    // ========== 普通股票定期上市调度 ==========
    public long getNextListingAttempt() { return nextListingAttempt; }
    public void setNextListingAttempt(long t) { this.nextListingAttempt = t; }

    // ========== 跟风事件全局限频 ==========
    /** 记录一次跟风事件时间戳，并清理窗口外旧记录。*/
    public void addMomentumEvent(long timestamp) {
        this.recentMomentumEvents.addLast(timestamp);
        long cutoff = timestamp - momentumEventWindowMs();
        while (!this.recentMomentumEvents.isEmpty() && this.recentMomentumEvents.peekFirst() < cutoff) {
            this.recentMomentumEvents.removeFirst();
        }
    }

    /** 返回当前窗口期内已发生的跟风事件数（同时清理旧记录）。*/
    public int countMomentumEventsInWindow(long now) {
        long cutoff = now - momentumEventWindowMs();
        while (!this.recentMomentumEvents.isEmpty() && this.recentMomentumEvents.peekFirst() < cutoff) {
            this.recentMomentumEvents.removeFirst();
        }
        return this.recentMomentumEvents.size();
    }

    private static long momentumEventWindowMs() {
        return (long) Config.MOMENTUM_EVENT_WINDOW_MINUTES.get() * 50_000L;
    }
}
