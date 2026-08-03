package com.mcitemstockmarket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 模组配置。
 *
 * 说明：NeoForge 的 {@link ModConfigSpec} 对 Map 类型支持不佳（TOML 表读回时
 * 数值类型不确定），因此保护价表/初始价表用 "itemId=price" 形式的字符串列表存储，
 * 通过 {@link #getProtectionPrice} / {@link #getInitialPrice} 解析为 Map 查询。
 */
@EventBusSubscriber(modid = MCItemStockMarket.MODID)
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    // ===================== 保留 MDK 示例项（主类 commonSetup 引用） =====================
    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    // ===================== 货币 =====================
    public static final ModConfigSpec.ConfigValue<String> CURRENCY_NAME = BUILDER
            .comment("货币名称", "Currency name")
            .define("currency_name", "硬币");

    public static final ModConfigSpec.DoubleValue STARTING_BALANCE = BUILDER
            .comment("新玩家初始余额", "Starting balance for new players")
            .defineInRange("starting_balance", 1000.0, 0.0, Double.MAX_VALUE);

    // ===================== 价格波动 =====================
    public static final ModConfigSpec.DoubleValue MINUTE_FLUCTUATION_PERCENT = BUILDER
            .comment("每 50 秒（1 游戏时）股价随机波动上限（0.05 = 5%，范围 [-5%, +5%]）",
                    "Per-50s (1 game-hour) random fluctuation cap (0.05 = 5%, range [-5%, +5%])")
            .defineInRange("minute_fluctuation_percent", 0.05, 0.0, 10.0);

    public static final ModConfigSpec.IntValue TREND_UPDATE_INTERVAL_MINUTES = BUILDER
            .comment("趋势调整间隔（游戏分钟，1 游戏分 = 50 秒现实时间；默认 24 = 1 游戏天 = 20 分钟现实）",
                    "Trend update interval in game-minutes (1 game-min = 50s real; default 24 = 1 game-day = 20 real min)")
            .defineInRange("trend_update_interval_minutes", 24, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue TREND_FLUCTUATION_PERCENT = BUILDER
            .comment("每 20 分钟（1 游戏天 / 24 游戏时）趋势调整幅度上限（0.05 = 5%，范围 [-5%, +5%]）",
                    "Per-20min (1 game-day) trend fluctuation cap (0.05 = 5%, range [-5%, +5%])")
            .defineInRange("trend_fluctuation_percent", 0.05, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue TRADE_IMPACT_PERCENT = BUILDER
            .comment("累计交易达阈值后对股价的影响（0.0005 = 0.05%）", "Trade impact percent (0.0005 = 0.05%)")
            .defineInRange("trade_impact_percent", 0.0005, 0.0, 1.0);

    public static final ModConfigSpec.IntValue TRADE_VOLUME_THRESHOLD = BUILDER
            .comment("触发交易影响所需的累计股数", "Cumulative share count to trigger trade impact")
            .defineInRange("trade_volume_threshold", 10, 1, Integer.MAX_VALUE);

    // ===================== 保护价 / 退市 =====================
    // 保护价规则：单价个位数(1~9) = 初始价×0.2；十位数(10~99) = 初始价×0.5；其余(≥100) = 初始价×0.7
    // 此表同时决定"上市物品池"（可上市的物品来自 protection_prices 表中的物品）
    public static final ModConfigSpec.ConfigValue<List<? extends String>> PROTECTION_PRICES = BUILDER
            .comment("各基础物品保护价表，每项格式 itemId=price；此表也决定可上市的物品池",
                    "Protection prices per item, each entry formatted as itemId=price; also defines the listable item pool")
            .defineListAllowEmpty("protection_prices",
                    List.of(
                            // 1. 自然方块与建筑方块
                            "minecraft:dirt=0.2",
                            "minecraft:grass_block=0.2",
                            "minecraft:mycelium=0.2",
                            "minecraft:sand=0.2",
                            "minecraft:red_sand=0.2",
                            "minecraft:gravel=0.2",
                            "minecraft:cobblestone=0.2",
                            "minecraft:stone=0.2",
                            "minecraft:granite=0.4",
                            "minecraft:diorite=0.4",
                            "minecraft:andesite=0.4",
                            "minecraft:sandstone=0.6",
                            "minecraft:red_sandstone=0.6",
                            "minecraft:oak_log=1.0",
                            "minecraft:spruce_log=1.0",
                            "minecraft:birch_log=1.0",
                            "minecraft:jungle_log=1.0",
                            "minecraft:acacia_log=1.0",
                            "minecraft:dark_oak_log=1.0",
                            "minecraft:mangrove_log=1.0",
                            "minecraft:cherry_log=1.0",
                            "minecraft:pale_oak_log=1.0",
                            "minecraft:stripped_oak_log=1.0",
                            "minecraft:stripped_spruce_log=1.0",
                            "minecraft:stripped_birch_log=1.0",
                            "minecraft:stripped_jungle_log=1.0",
                            "minecraft:stripped_acacia_log=1.0",
                            "minecraft:stripped_dark_oak_log=1.0",
                            "minecraft:stripped_mangrove_log=1.0",
                            "minecraft:stripped_cherry_log=1.0",
                            "minecraft:stripped_pale_oak_log=1.0",
                            "minecraft:bamboo_block=1.0",
                            "minecraft:stripped_bamboo_block=1.0",
                            "minecraft:oak_sapling=0.4",
                            "minecraft:spruce_sapling=0.4",
                            "minecraft:birch_sapling=0.4",
                            "minecraft:jungle_sapling=0.4",
                            "minecraft:acacia_sapling=0.4",
                            "minecraft:dark_oak_sapling=0.4",
                            "minecraft:mangrove_propagule=0.4",
                            "minecraft:cherry_sapling=0.4",
                            "minecraft:pale_oak_sapling=0.4",
                            "minecraft:oak_leaves=0.2",
                            "minecraft:spruce_leaves=0.2",
                            "minecraft:birch_leaves=0.2",
                            "minecraft:jungle_leaves=0.2",
                            "minecraft:acacia_leaves=0.2",
                            "minecraft:dark_oak_leaves=0.2",
                            "minecraft:mangrove_leaves=0.2",
                            "minecraft:cherry_leaves=0.2",
                            "minecraft:pale_oak_leaves=0.2",
                            "minecraft:azalea_leaves=0.2",
                            "minecraft:flowering_azalea_leaves=0.2",
                            "minecraft:clay=0.8",
                            "minecraft:snow_block=0.8",
                            "minecraft:ice=5.0",
                            "minecraft:packed_ice=15.0",
                            "minecraft:blue_ice=70.0",
                            "minecraft:obsidian=25.0",
                            "minecraft:crying_obsidian=30.0",
                            "minecraft:netherrack=0.4",
                            "minecraft:soul_sand=1.0",
                            "minecraft:soul_soil=1.0",
                            "minecraft:magma_block=7.5",
                            "minecraft:glowstone=10.0",
                            "minecraft:prismarine=105.0",
                            "minecraft:dark_prismarine=175.0",
                            "minecraft:sea_lantern=420.0",
                            "minecraft:sponge=1400.0",
                            // 2. 矿物与基础材料
                            "minecraft:coal=1.6",
                            "minecraft:raw_iron=6.0",
                            "minecraft:iron_ingot=15.0",
                            "minecraft:raw_copper=5.0",
                            "minecraft:copper_ingot=7.5",
                            "minecraft:raw_gold=7.5",
                            "minecraft:gold_ingot=40.0",
                            "minecraft:redstone=1.6",
                            "minecraft:lapis_lazuli=5.0",
                            "minecraft:quartz=1.0",
                            "minecraft:diamond=350.0",
                            "minecraft:emerald=210.0",
                            "minecraft:ancient_debris=1120.0",
                            "minecraft:netherite_scrap=280.0",
                            "minecraft:netherite_ingot=1400.0",
                            "minecraft:amethyst_shard=10.0",
                            "minecraft:flint=1.0",
                            "minecraft:leather=1.0",
                            "minecraft:feather=0.4",
                            "minecraft:string=0.6",
                            "minecraft:bone=0.4",
                            "minecraft:rotten_flesh=0.2",
                            "minecraft:slime_ball=7.5",
                            "minecraft:ender_pearl=25.0",
                            "minecraft:blaze_rod=40.0",
                            "minecraft:ghast_tear=140.0",
                            "minecraft:nautilus_shell=560.0",
                            "minecraft:phantom_membrane=15.0",
                            // 3. 工具、武器与装备
                            "minecraft:wooden_pickaxe=0.8",
                            "minecraft:wooden_axe=0.8",
                            "minecraft:wooden_shovel=0.8",
                            "minecraft:wooden_hoe=0.8",
                            "minecraft:stone_pickaxe=1.6",
                            "minecraft:stone_axe=1.6",
                            "minecraft:stone_shovel=1.6",
                            "minecraft:stone_hoe=1.6",
                            "minecraft:iron_pickaxe=47.5",
                            "minecraft:iron_axe=32.5",
                            "minecraft:iron_shovel=17.5",
                            "minecraft:iron_hoe=32.5",
                            "minecraft:diamond_pickaxe=1120.0",
                            "minecraft:diamond_axe=1120.0",
                            "minecraft:diamond_shovel=385.0",
                            "minecraft:diamond_hoe=735.0",
                            "minecraft:netherite_pickaxe=2520.0",
                            "minecraft:shears=32.5",
                            "minecraft:flint_and_steel=20.0",
                            "minecraft:fishing_rod=7.5",
                            "minecraft:carrot_on_a_stick=12.5",
                            "minecraft:warped_fungus_on_a_stick=12.5",
                            "minecraft:wooden_sword=0.8",
                            "minecraft:stone_sword=1.6",
                            "minecraft:iron_sword=35.0",
                            "minecraft:diamond_sword=770.0",
                            "minecraft:netherite_sword=2800.0",
                            "minecraft:bow=10.0",
                            "minecraft:crossbow=25.0",
                            "minecraft:arrow=0.2",
                            "minecraft:spectral_arrow=0.6",
                            "minecraft:trident=3500.0",
                            "minecraft:shield=20.0",
                            "minecraft:leather_helmet=7.5",
                            "minecraft:leather_boots=7.5",
                            "minecraft:leather_chestplate=12.5",
                            "minecraft:leather_leggings=10.0",
                            "minecraft:iron_helmet=112.0",
                            "minecraft:iron_chestplate=182.0",
                            "minecraft:iron_leggings=161.0",
                            "minecraft:iron_boots=91.0",
                            "minecraft:diamond_helmet=1820.0",
                            "minecraft:diamond_chestplate=2870.0",
                            "minecraft:diamond_leggings=2520.0",
                            "minecraft:diamond_boots=1470.0",
                            "minecraft:netherite_helmet=3220.0",
                            "minecraft:netherite_chestplate=4270.0",
                            "minecraft:netherite_leggings=3920.0",
                            "minecraft:netherite_boots=2870.0",
                            "minecraft:iron_horse_armor=126.0",
                            "minecraft:golden_horse_armor=294.0",
                            "minecraft:diamond_horse_armor=1820.0",
                            "minecraft:saddle=140.0",
                            // 4. 食物
                            "minecraft:wheat=0.4",
                            "minecraft:wheat_seeds=0.2",
                            "minecraft:bread=1.6",
                            "minecraft:potato=0.6",
                            "minecraft:carrot=0.6",
                            "minecraft:baked_potato=1.0",
                            "minecraft:beetroot=0.6",
                            "minecraft:beetroot_soup=10.0",
                            "minecraft:apple=7.5",
                            "minecraft:golden_apple=455.0",
                            "minecraft:enchanted_golden_apple=3500.0",
                            "minecraft:beef=7.5",
                            "minecraft:porkchop=7.5",
                            "minecraft:cooked_beef=12.5",
                            "minecraft:cooked_porkchop=12.5",
                            "minecraft:cod=1.6",
                            "minecraft:cooked_cod=6.0",
                            "minecraft:salmon=5.0",
                            "minecraft:cooked_salmon=7.5",
                            "minecraft:tropical_fish=1.2",
                            "minecraft:pufferfish=1.2",
                            "minecraft:cake=70.0",
                            "minecraft:pumpkin_pie=12.5",
                            "minecraft:cookie=0.4",
                            "minecraft:mushroom_stew=7.5",
                            "minecraft:rabbit_stew=12.5",
                            "minecraft:honey_bottle=10.0",
                            "minecraft:chorus_fruit=7.5",
                            "minecraft:golden_carrot=70.0",
                            // 5. 药水与酿造材料
                            "minecraft:glass_bottle=0.2",
                            "minecraft:potion=0.2",
                            "minecraft:nether_wart=0.4",
                            "minecraft:blaze_powder=20.0",
                            "minecraft:fermented_spider_eye=7.5",
                            "minecraft:glistering_melon_slice=175.0",
                            "minecraft:magma_cream=15.0",
                            "minecraft:sugar=0.4",
                            "minecraft:spider_eye=1.6",
                            "minecraft:dragon_breath=350.0",
                            "minecraft:experience_bottle=210.0",
                            // 6. 红石与交通
                            "minecraft:redstone_torch=6.0",
                            "minecraft:repeater=15.0",
                            "minecraft:comparator=25.0",
                            "minecraft:piston=20.0",
                            "minecraft:sticky_piston=25.0",
                            "minecraft:hopper=40.0",
                            "minecraft:daylight_detector=22.5",
                            "minecraft:tnt=40.0",
                            "minecraft:note_block=7.5",
                            "minecraft:rail=0.8",
                            "minecraft:powered_rail=25.0",
                            "minecraft:detector_rail=17.5",
                            "minecraft:activator_rail=20.0",
                            "minecraft:minecart=10.0",
                            "minecraft:chest_minecart=70.0",
                            "minecraft:hopper_minecart=70.0",
                            "minecraft:furnace_minecart=84.0",
                            "minecraft:oak_boat=5.0",
                            // 7. 装饰与杂项
                            "minecraft:bookshelf=25.0",
                            "minecraft:enchanting_table=700.0",
                            "minecraft:anvil=1050.0",
                            "minecraft:ender_chest=490.0",
                            "minecraft:shulker_box=280.0",
                            "minecraft:beacon=3500.0",
                            "minecraft:painting=7.5",
                            "minecraft:item_frame=12.5",
                            "minecraft:flower_pot=5.0",
                            "minecraft:red_bed=7.5",
                            "minecraft:shulker_shell=420.0"),
                    () -> "", Config::validatePriceEntry);

    public static final ModConfigSpec.DoubleValue DEFAULT_PROTECTION_PRICE = BUILDER
            .comment("未在 protection_prices 表中物品的默认保护价", "Default protection price for unlisted items")
            .defineInRange("default_protection_price", 0.1, 0.0, Double.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> RELAUNCH_DELAY_SECONDS_RANGE = BUILDER
            .comment("退市后重新上市延迟秒数范围 [min, max]", "Relaunch delay range in seconds [min, max]")
            .defineListAllowEmpty("relaunch_delay_seconds_range", List.of(480, 600), () -> 0, o -> o instanceof Integer);

    // ===================== 跟风投资 =====================
    public static final ModConfigSpec.IntValue MOMENTUM_TOP_RANK = BUILDER
            .comment("涨幅前 N 名触发跟风；为 0 则使用百分比模式", "Top-N rank to trigger momentum; 0 = percent mode")
            .defineInRange("momentum_top_rank", 3, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue MOMENTUM_TOP_PERCENT = BUILDER
            .comment("百分比模式下的前 X 比例（0.2 = 20%），仅 momentum_top_rank=0 时生效",
                    "Top percent in percent mode (0.2 = 20%), only when momentum_top_rank=0")
            .defineInRange("momentum_top_percent", 0.2, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue MOMENTUM_MIN_INCREASE_PERCENT = BUILDER
            .comment("触发跟风所需最低涨幅（0.1 = 10%）", "Min increase percent to trigger momentum (0.1 = 10%)")
            .defineInRange("momentum_min_increase_percent", 0.1, 0.0, 10.0);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> MOMENTUM_SUFFIXES = BUILDER
            .comment("跟风股票后缀候选", "Momentum stock suffix candidates")
            .defineListAllowEmpty("momentum_suffixes", List.of("ST", "指数"), () -> "", o -> o instanceof String);

    public static final ModConfigSpec.DoubleValue MOMENTUM_PRICE_MULTIPLIER = BUILDER
            .comment("跟风股票波动放大倍数", "Momentum stock price fluctuation multiplier")
            .defineInRange("momentum_price_multiplier", 2.0, 1.0, 100.0);

    public static final ModConfigSpec.IntValue MOMENTUM_MAX_SAME_ITEM = BUILDER
            .comment("同一物品同时存在的跟风股票上限", "Max simultaneous momentum stocks per item")
            .defineInRange("momentum_max_same_item", 2, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MOMENTUM_COOLDOWN_MINUTES = BUILDER
            .comment("跟风触发后的冷却时间（分钟）", "Momentum trigger cooldown in minutes")
            .defineInRange("momentum_cooldown_minutes", 24, 0, Integer.MAX_VALUE);

    // ===================== 初始价格 / 前缀 =====================
    public static final ModConfigSpec.ConfigValue<List<? extends String>> INITIAL_PRICES = BUILDER
            .comment("各物品初始股价表，每项格式 itemId=price（上市股票的发行价）",
                    "Initial prices per item, each entry formatted as itemId=price (listing price of stocks)")
            .defineListAllowEmpty("initial_prices",
                    List.of(
                            // 1. 自然方块与建筑方块
                            "minecraft:dirt=1.0",
                            "minecraft:grass_block=1.0",
                            "minecraft:mycelium=1.0",
                            "minecraft:sand=1.0",
                            "minecraft:red_sand=1.0",
                            "minecraft:gravel=1.0",
                            "minecraft:cobblestone=1.0",
                            "minecraft:stone=1.0",
                            "minecraft:granite=2.0",
                            "minecraft:diorite=2.0",
                            "minecraft:andesite=2.0",
                            "minecraft:sandstone=3.0",
                            "minecraft:red_sandstone=3.0",
                            "minecraft:oak_log=5.0",
                            "minecraft:spruce_log=5.0",
                            "minecraft:birch_log=5.0",
                            "minecraft:jungle_log=5.0",
                            "minecraft:acacia_log=5.0",
                            "minecraft:dark_oak_log=5.0",
                            "minecraft:mangrove_log=5.0",
                            "minecraft:cherry_log=5.0",
                            "minecraft:pale_oak_log=5.0",
                            "minecraft:stripped_oak_log=5.0",
                            "minecraft:stripped_spruce_log=5.0",
                            "minecraft:stripped_birch_log=5.0",
                            "minecraft:stripped_jungle_log=5.0",
                            "minecraft:stripped_acacia_log=5.0",
                            "minecraft:stripped_dark_oak_log=5.0",
                            "minecraft:stripped_mangrove_log=5.0",
                            "minecraft:stripped_cherry_log=5.0",
                            "minecraft:stripped_pale_oak_log=5.0",
                            "minecraft:bamboo_block=5.0",
                            "minecraft:stripped_bamboo_block=5.0",
                            "minecraft:oak_sapling=2.0",
                            "minecraft:spruce_sapling=2.0",
                            "minecraft:birch_sapling=2.0",
                            "minecraft:jungle_sapling=2.0",
                            "minecraft:acacia_sapling=2.0",
                            "minecraft:dark_oak_sapling=2.0",
                            "minecraft:mangrove_propagule=2.0",
                            "minecraft:cherry_sapling=2.0",
                            "minecraft:pale_oak_sapling=2.0",
                            "minecraft:oak_leaves=1.0",
                            "minecraft:spruce_leaves=1.0",
                            "minecraft:birch_leaves=1.0",
                            "minecraft:jungle_leaves=1.0",
                            "minecraft:acacia_leaves=1.0",
                            "minecraft:dark_oak_leaves=1.0",
                            "minecraft:mangrove_leaves=1.0",
                            "minecraft:cherry_leaves=1.0",
                            "minecraft:pale_oak_leaves=1.0",
                            "minecraft:azalea_leaves=1.0",
                            "minecraft:flowering_azalea_leaves=1.0",
                            "minecraft:clay=4.0",
                            "minecraft:snow_block=4.0",
                            "minecraft:ice=10.0",
                            "minecraft:packed_ice=30.0",
                            "minecraft:blue_ice=100.0",
                            "minecraft:obsidian=50.0",
                            "minecraft:crying_obsidian=60.0",
                            "minecraft:netherrack=2.0",
                            "minecraft:soul_sand=5.0",
                            "minecraft:soul_soil=5.0",
                            "minecraft:magma_block=15.0",
                            "minecraft:glowstone=20.0",
                            "minecraft:prismarine=150.0",
                            "minecraft:dark_prismarine=250.0",
                            "minecraft:sea_lantern=600.0",
                            "minecraft:sponge=2000.0",
                            // 2. 矿物与基础材料
                            "minecraft:coal=8.0",
                            "minecraft:raw_iron=12.0",
                            "minecraft:iron_ingot=30.0",
                            "minecraft:raw_copper=10.0",
                            "minecraft:copper_ingot=15.0",
                            "minecraft:raw_gold=15.0",
                            "minecraft:gold_ingot=80.0",
                            "minecraft:redstone=8.0",
                            "minecraft:lapis_lazuli=10.0",
                            "minecraft:quartz=5.0",
                            "minecraft:diamond=500.0",
                            "minecraft:emerald=300.0",
                            "minecraft:ancient_debris=1600.0",
                            "minecraft:netherite_scrap=400.0",
                            "minecraft:netherite_ingot=2000.0",
                            "minecraft:amethyst_shard=20.0",
                            "minecraft:flint=5.0",
                            "minecraft:leather=5.0",
                            "minecraft:feather=2.0",
                            "minecraft:string=3.0",
                            "minecraft:bone=2.0",
                            "minecraft:rotten_flesh=1.0",
                            "minecraft:slime_ball=15.0",
                            "minecraft:ender_pearl=50.0",
                            "minecraft:blaze_rod=80.0",
                            "minecraft:ghast_tear=200.0",
                            "minecraft:nautilus_shell=800.0",
                            "minecraft:phantom_membrane=30.0",
                            // 3. 工具、武器与装备
                            "minecraft:wooden_pickaxe=4.0",
                            "minecraft:wooden_axe=4.0",
                            "minecraft:wooden_shovel=4.0",
                            "minecraft:wooden_hoe=4.0",
                            "minecraft:stone_pickaxe=8.0",
                            "minecraft:stone_axe=8.0",
                            "minecraft:stone_shovel=8.0",
                            "minecraft:stone_hoe=8.0",
                            "minecraft:iron_pickaxe=95.0",
                            "minecraft:iron_axe=65.0",
                            "minecraft:iron_shovel=35.0",
                            "minecraft:iron_hoe=65.0",
                            "minecraft:diamond_pickaxe=1600.0",
                            "minecraft:diamond_axe=1600.0",
                            "minecraft:diamond_shovel=550.0",
                            "minecraft:diamond_hoe=1050.0",
                            "minecraft:netherite_pickaxe=3600.0",
                            "minecraft:shears=65.0",
                            "minecraft:flint_and_steel=40.0",
                            "minecraft:fishing_rod=15.0",
                            "minecraft:carrot_on_a_stick=25.0",
                            "minecraft:warped_fungus_on_a_stick=25.0",
                            "minecraft:wooden_sword=4.0",
                            "minecraft:stone_sword=8.0",
                            "minecraft:iron_sword=70.0",
                            "minecraft:diamond_sword=1100.0",
                            "minecraft:netherite_sword=4000.0",
                            "minecraft:bow=20.0",
                            "minecraft:crossbow=50.0",
                            "minecraft:arrow=1.0",
                            "minecraft:spectral_arrow=3.0",
                            "minecraft:trident=5000.0",
                            "minecraft:shield=40.0",
                            "minecraft:leather_helmet=15.0",
                            "minecraft:leather_boots=15.0",
                            "minecraft:leather_chestplate=25.0",
                            "minecraft:leather_leggings=20.0",
                            "minecraft:iron_helmet=160.0",
                            "minecraft:iron_chestplate=260.0",
                            "minecraft:iron_leggings=230.0",
                            "minecraft:iron_boots=130.0",
                            "minecraft:diamond_helmet=2600.0",
                            "minecraft:diamond_chestplate=4100.0",
                            "minecraft:diamond_leggings=3600.0",
                            "minecraft:diamond_boots=2100.0",
                            "minecraft:netherite_helmet=4600.0",
                            "minecraft:netherite_chestplate=6100.0",
                            "minecraft:netherite_leggings=5600.0",
                            "minecraft:netherite_boots=4100.0",
                            "minecraft:iron_horse_armor=180.0",
                            "minecraft:golden_horse_armor=420.0",
                            "minecraft:diamond_horse_armor=2600.0",
                            "minecraft:saddle=200.0",
                            // 4. 食物
                            "minecraft:wheat=2.0",
                            "minecraft:wheat_seeds=1.0",
                            "minecraft:bread=8.0",
                            "minecraft:potato=3.0",
                            "minecraft:carrot=3.0",
                            "minecraft:baked_potato=5.0",
                            "minecraft:beetroot=3.0",
                            "minecraft:beetroot_soup=20.0",
                            "minecraft:apple=15.0",
                            "minecraft:golden_apple=650.0",
                            "minecraft:enchanted_golden_apple=5000.0",
                            "minecraft:beef=15.0",
                            "minecraft:porkchop=15.0",
                            "minecraft:cooked_beef=25.0",
                            "minecraft:cooked_porkchop=25.0",
                            "minecraft:cod=8.0",
                            "minecraft:cooked_cod=12.0",
                            "minecraft:salmon=10.0",
                            "minecraft:cooked_salmon=15.0",
                            "minecraft:tropical_fish=6.0",
                            "minecraft:pufferfish=6.0",
                            "minecraft:cake=100.0",
                            "minecraft:pumpkin_pie=25.0",
                            "minecraft:cookie=2.0",
                            "minecraft:mushroom_stew=15.0",
                            "minecraft:rabbit_stew=25.0",
                            "minecraft:honey_bottle=20.0",
                            "minecraft:chorus_fruit=15.0",
                            "minecraft:golden_carrot=100.0",
                            // 5. 药水与酿造材料
                            "minecraft:glass_bottle=1.0",
                            "minecraft:potion=1.0",
                            "minecraft:nether_wart=2.0",
                            "minecraft:blaze_powder=40.0",
                            "minecraft:fermented_spider_eye=15.0",
                            "minecraft:glistering_melon_slice=250.0",
                            "minecraft:magma_cream=30.0",
                            "minecraft:sugar=2.0",
                            "minecraft:spider_eye=8.0",
                            "minecraft:dragon_breath=500.0",
                            "minecraft:experience_bottle=300.0",
                            // 6. 红石与交通
                            "minecraft:redstone_torch=12.0",
                            "minecraft:repeater=30.0",
                            "minecraft:comparator=50.0",
                            "minecraft:piston=40.0",
                            "minecraft:sticky_piston=50.0",
                            "minecraft:hopper=80.0",
                            "minecraft:daylight_detector=45.0",
                            "minecraft:tnt=80.0",
                            "minecraft:note_block=15.0",
                            "minecraft:rail=4.0",
                            "minecraft:powered_rail=50.0",
                            "minecraft:detector_rail=35.0",
                            "minecraft:activator_rail=40.0",
                            "minecraft:minecart=20.0",
                            "minecraft:chest_minecart=100.0",
                            "minecraft:hopper_minecart=100.0",
                            "minecraft:furnace_minecart=120.0",
                            "minecraft:oak_boat=10.0",
                            // 7. 装饰与杂项
                            "minecraft:bookshelf=50.0",
                            "minecraft:enchanting_table=1000.0",
                            "minecraft:anvil=1500.0",
                            "minecraft:ender_chest=700.0",
                            "minecraft:shulker_box=400.0",
                            "minecraft:beacon=5000.0",
                            "minecraft:painting=15.0",
                            "minecraft:item_frame=25.0",
                            "minecraft:flower_pot=10.0",
                            "minecraft:red_bed=15.0",
                            "minecraft:shulker_shell=600.0"),
                    () -> "", Config::validatePriceEntry);

    public static final ModConfigSpec.DoubleValue DEFAULT_INITIAL_PRICE = BUILDER
            .comment("未在 initial_prices 表中物品的默认初始股价", "Default initial price for unlisted items")
            .defineInRange("default_initial_price", 10.0, 0.0, Double.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> STOCK_PREFIXES = BUILDER
            .comment("股票前缀候选列表（默认取自生物名；仅在 use_entity_prefixes=false 时作为前缀池使用，",
                    "否则仍用于旧存档前缀的本地化显示）",
                    "Stock prefix candidates (default mob names; only used as the prefix pool when",
                    "use_entity_prefixes=false, otherwise still used to localize old-save prefixes)")
            .defineListAllowEmpty("stock_prefixes",
                    List.of("村民", "苦力怕", "猪灵", "僵尸", "骷髅", "牛", "羊", "鸡", "河豚", "海豚",
                            "蜘蛛", "末影人", "鱿鱼", "狐狸", "猫猫", "狗狗", "恶魂", "凋灵", "女巫"),
                    () -> "", o -> o instanceof String);

    public static final ModConfigSpec.BooleanValue USE_ENTITY_PREFIXES = BUILDER
            .comment("股票前缀读取游戏内注册的生物（LivingEntity）实体名（如 villager / creeper），自动随当前语言本地化；",
                    "false 时使用上方 stock_prefixes 列表",
                    "Use in-game registered living-entity names as stock prefixes (localized per language);",
                    "false uses the stock_prefixes list above")
            .define("use_entity_prefixes", true);

    // ===================== 其他 =====================
    public static final ModConfigSpec.IntValue HISTORY_RETENTION_MINUTES = BUILDER
            .comment("价格历史保留分钟数（默认一周=168）", "Price history retention in minutes (default 168 = 1 week)")
            .defineInRange("history_retention_minutes", 168, 1, Integer.MAX_VALUE);

    public static final ModConfigSpec.DoubleValue DAILY_ALERT_PERCENT = BUILDER
            .comment("单日（24分钟）涨跌幅超过此值时全服提醒（0.2 = 20%）",
                    "Daily (24min) change threshold for server-wide alert (0.2 = 20%)")
            .defineInRange("daily_alert_percent", 0.2, 0.0, 10.0);

    public static final ModConfigSpec.DoubleValue DAILY_LIMIT_PERCENT = BUILDER
            .comment("单日（24分钟）涨跌停板限制（0.1 = 10%）。",
                    "普通股票价格被钳制在 [开盘价*(1-此值), 开盘价*(1+此值)] 区间内；",
                    "涨幅达到上限（涨停）时买入失败，跌幅达到下限（跌停）时卖出失败。",
                    "跟风股票（momentum）不受此限制，保留其放大波动特性。",
                    "Daily (24min) price limit (0.1 = 10%). Normal stocks are clamped to",
                    "[open*(1-x), open*(1+x)]; buy fails at limit-up, sell fails at limit-down.",
                    "Momentum stocks are exempt.")
            .defineInRange("daily_limit_percent", 0.10, 0.0, 1.0);

    // ===================== 首次启动随机股票 =====================
    public static final ModConfigSpec.IntValue INITIAL_RANDOM_STOCK_COUNT = BUILDER
            .comment("首次启动时随机创建的股票数量", "Number of random stocks created on first launch")
            .defineInRange("initial_random_stock_count", 50, 0, Integer.MAX_VALUE);

    // ===================== 普通股票定期上市 =====================
    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> NEW_LISTING_INTERVAL_SECONDS_RANGE = BUILDER
            .comment("普通股票定期上市尝试间隔（现实秒）[min, max]，默认 5~10 分钟",
                    "Normal stock periodic listing attempt interval (real seconds) [min, max], default 5~10 min")
            .defineListAllowEmpty("new_listing_interval_seconds_range", List.of(300, 600), () -> 0, o -> o instanceof Integer);

    public static final ModConfigSpec.IntValue NEW_LISTING_MAX_PER_ITEM = BUILDER
            .comment("同一物品下普通股票的最大数量（达到上限则不再为该物品上市新股，默认 3）",
                    "Max normal stocks per item; new listings skip items at this cap (default 3)")
            .defineInRange("new_listing_max_per_item", 3, 1, Integer.MAX_VALUE);

    // ===================== 物品 / 货币兑换 =====================
    public static final ModConfigSpec.DoubleValue EXCHANGE_SELL_PRICE_MULTIPLIER = BUILDER
            .comment("物品兑换货币时按 当前股价 × 此倍数 结算（默认 0.8 = 8 折）；货币兑换物品按当前股价原价",
                    "Item-to-currency exchange settles at current price × this multiplier (default 0.8); currency-to-item at full price")
            .defineInRange("exchange_sell_price_multiplier", 0.8, 0.0, 1.0);

    // ===================== 跟风事件限频 =====================
    public static final ModConfigSpec.IntValue MOMENTUM_MAX_EVENTS_PER_WINDOW = BUILDER
            .comment("跟风股票事件在窗口期内最多触发的次数（默认 3）",
                    "Max momentum stock events within the rolling window (default 3)")
            .defineInRange("momentum_max_events_per_window", 3, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.IntValue MOMENTUM_EVENT_WINDOW_MINUTES = BUILDER
            .comment("跟风事件限频窗口（游戏分钟，1 游戏分 = 50 秒现实时间；默认 10 = 500 秒现实）",
                    "Momentum event rate-limit window in game-minutes (1 game-min = 50s real; default 10 = 500s real)")
            .defineInRange("momentum_event_window_minutes", 10, 1, Integer.MAX_VALUE);

    static final ModConfigSpec SPEC = BUILDER.build();

    // ===================== 查询缓存 =====================
    // 配置加载/重载时由 ModConfigEvent 触发重建。
    private static volatile Map<String, Double> protectionPriceMap = new LinkedHashMap<>();
    private static volatile Map<String, Double> initialPriceMap = new LinkedHashMap<>();

    @SubscribeEvent
    public static void onLoad(final ModConfigEvent.Loading event) {
        rebuildCaches();
    }

    @SubscribeEvent
    public static void onReload(final ModConfigEvent.Reloading event) {
        rebuildCaches();
    }

    private static void rebuildCaches() {
        protectionPriceMap = parsePriceEntries(PROTECTION_PRICES.get());
        initialPriceMap = parsePriceEntries(INITIAL_PRICES.get());
    }

    private static Map<String, Double> parsePriceEntries(List<? extends String> entries) {
        Map<String, Double> m = new LinkedHashMap<>();
        if (entries == null) return m;
        for (Object o : entries) {
            String s = String.valueOf(o);
            int eq = s.indexOf('=');
            if (eq <= 0) continue;
            String k = s.substring(0, eq).trim();
            String v = s.substring(eq + 1).trim();
            try {
                m.put(k, Double.parseDouble(v));
            } catch (NumberFormatException ignored) {
            }
        }
        return m;
    }

    /**
     * 取某物品的保护价：显式配置优先；否则按初始价档位规则计算
     * （个位数 1~9 → ×0.2，十位数 10~99 → ×0.5，其余 ≥100 → ×0.7）；都不在表内则返回默认保护价。
     */
    public static double getProtectionPrice(String itemId) {
        Double d = protectionPriceMap.get(itemId);
        if (d != null) return d;
        Double init = initialPriceMap.get(itemId);
        if (init == null) return DEFAULT_PROTECTION_PRICE.get();
        double mult = init < 10 ? 0.2 : init < 100 ? 0.5 : 0.7;
        return init * mult;
    }

    /** 取某物品的初始股价；未定义则返回默认初始价。 */
    public static double getInitialPrice(String itemId) {
        Double d = initialPriceMap.get(itemId);
        return d != null ? d : DEFAULT_INITIAL_PRICE.get();
    }

    /**
     * 股票前缀池。
     * use_entity_prefixes=true 时包含游戏内注册的活体生物实体名（按注册 ID 排序，确定性输出）；
     * 无论开关如何，stock_prefixes 中用户配置的前缀始终并入（去重），保证新配置的前缀立即生效；
     * 若实体池为空（注册表未就绪）则回退到配置列表。
     */
    public static List<String> getStockPrefixPool() {
        LinkedHashSet<String> pool = new LinkedHashSet<>();
        if (USE_ENTITY_PREFIXES.get()) {
            List<String> entities = collectEntityPrefixes();
            if (entities.isEmpty()) {
                MCItemStockMarket.LOGGER.warn("[股市] 实体前缀池为空，当前仅使用配置前缀");
            }
            pool.addAll(entities);
        }
        pool.addAll(STOCK_PREFIXES.get());
        if (pool.isEmpty()) {
            pool.addAll(collectEntityPrefixes());
        }
        return new ArrayList<>(pool);
    }

    /**
     * 收集游戏内注册的所有生物（mob）实体名作为前缀。
     * vanilla 实体取路径名（如 "villager"），mod 实体取完整 ID（如 "modid:entity"），避免跨命名空间歧义。
     * 过滤依据：MobCategory != MISC（排除弹射物/物品/载具/显示实体等）。
     * 注意：不能使用 EntityType.getBaseClass() 判断——1.21.10 该方法对方法引用注册的实体
     * 会因泛型擦除退化返回 Entity.class，导致全部被过滤；MobCategory 是存储字段，可靠。
     */
    public static List<String> collectEntityPrefixes() {
        List<String> list = new ArrayList<>();
        try {
            for (Map.Entry<ResourceKey<EntityType<?>>, EntityType<?>> e : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
                EntityType<?> type = e.getValue();
                if (type == null) continue;
                if (type.getCategory() == MobCategory.MISC) continue; // 排除非生物实体
                ResourceLocation loc = e.getKey().location();
                String ns = loc.getNamespace();
                list.add("minecraft".equals(ns) ? loc.getPath() : loc.toString());
            }
        } catch (Exception e) {
            MCItemStockMarket.LOGGER.error("[股市] 收集实体前缀失败", e);
        }
        list.sort(String::compareTo);
        return list;
    }

    // ===================== 验证器 =====================
    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName
                && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }

    private static boolean validatePriceEntry(final Object obj) {
        return obj instanceof String s && s.indexOf('=') > 0;
    }
}
