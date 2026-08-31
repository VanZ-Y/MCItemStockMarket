package com.mcitemstockmarket.network;

import java.util.ArrayList;
import java.util.List;

import com.mcitemstockmarket.MCItemStockMarket;
import com.mcitemstockmarket.data.PlayerAccount;
import com.mcitemstockmarket.data.Stock;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 所有 Payload 类集中定义，简化维护。
 * 每个 payload 都实现 CustomPacketPayload，提供 TYPE + STREAM_CODEC。
 */
public final class Payloads {
    private Payloads() {}

    // =============================================================
    // S2C 全量同步：股票列表 + 玩家账户
    // =============================================================
    public record ClientboundFullSync(List<Stock> stocks, PlayerAccount account)
            implements CustomPacketPayload {
        public static final Type<ClientboundFullSync> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MCItemStockMarket.MODID, "full_sync"));
        public static final StreamCodec<ByteBuf, ClientboundFullSync> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    Stock.LIST_STREAM_CODEC.encode(buf, p.stocks);
                    PlayerAccount.STREAM_CODEC.encode(buf, p.account);
                },
                buf -> new ClientboundFullSync(
                        Stock.LIST_STREAM_CODEC.decode(buf),
                        PlayerAccount.STREAM_CODEC.decode(buf)
                ));
        @Override public Type<ClientboundFullSync> type() { return TYPE; }
    }

    // =============================================================
    // S2C 价格更新（仅变化的股票列表）
    // 全量历史版本：仅在玩家加入 / 打开 GUI 时使用。
    // =============================================================
    public record ClientboundPriceUpdate(List<Stock> stocks) implements CustomPacketPayload {
        public static final Type<ClientboundPriceUpdate> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MCItemStockMarket.MODID, "price_update"));
        public static final StreamCodec<ByteBuf, ClientboundPriceUpdate> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> Stock.LIST_STREAM_CODEC.encode(buf, p.stocks),
                buf -> new ClientboundPriceUpdate(Stock.LIST_STREAM_CODEC.decode(buf))
        );
        @Override public Type<ClientboundPriceUpdate> type() { return TYPE; }
    }

    // =============================================================
    // S2C 增量价格补丁（每分钟周期性广播）
    // 仅含 fullName + 当前价 + 涨跌幅 + 最新时间戳，无历史点。
    // 客户端收到后合并到本地累积的历史曲线，大幅降低分配与网络尖峰。
    // =============================================================
    public record ClientboundPricePatch(List<Stock.PricePatch> patches) implements CustomPacketPayload {
        public static final Type<ClientboundPricePatch> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MCItemStockMarket.MODID, "price_patch"));
        public static final StreamCodec<ByteBuf, ClientboundPricePatch> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeInt(p.patches.size());
                    for (Stock.PricePatch pp : p.patches) {
                        Stock.PricePatch.STREAM_CODEC.encode(buf, pp);
                    }
                },
                buf -> {
                    int n = buf.readInt();
                    // 安全修复 #4：限制补丁数量上限，防止恶意/损坏数据包导致 OOM
                    if (n < 0 || n > 10000) {
                        throw new IllegalArgumentException("非法补丁数量: " + n);
                    }
                    List<Stock.PricePatch> list = new ArrayList<>(n);
                    for (int i = 0; i < n; i++) {
                        list.add(Stock.PricePatch.STREAM_CODEC.decode(buf));
                    }
                    return new ClientboundPricePatch(list);
                });
        @Override public Type<ClientboundPricePatch> type() { return TYPE; }
    }

    // =============================================================
    // S2C 玩家账户更新（余额/持仓变动）
    // =============================================================
    public record ClientboundAccountUpdate(PlayerAccount account) implements CustomPacketPayload {
        public static final Type<ClientboundAccountUpdate> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MCItemStockMarket.MODID, "account_update"));
        public static final StreamCodec<ByteBuf, ClientboundAccountUpdate> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> PlayerAccount.STREAM_CODEC.encode(buf, p.account),
                buf -> new ClientboundAccountUpdate(PlayerAccount.STREAM_CODEC.decode(buf))
        );
        @Override public Type<ClientboundAccountUpdate> type() { return TYPE; }
    }

    // =============================================================
    // S2C 事件广播（聊天消息文本，使用 JSON 字符串编码 Component 更稳健）
    // =============================================================
    public record ClientboundEvent(Component message) implements CustomPacketPayload {
        public static final Type<ClientboundEvent> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MCItemStockMarket.MODID, "event"));
        public static final StreamCodec<ByteBuf, ClientboundEvent> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> writeComponent(buf, p.message),
                buf -> new ClientboundEvent(readComponent(buf))
        );
        @Override public Type<ClientboundEvent> type() { return TYPE; }

        static void writeComponent(ByteBuf buf, Component c) {
            ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.encode(buf, c);
        }
        static Component readComponent(ByteBuf buf) {
            return ComponentSerialization.TRUSTED_CONTEXT_FREE_STREAM_CODEC.decode(buf);
        }
    }

    // =============================================================
    // C2S 打开 GUI 请求（服务端返回 ClientboundFullSync）
    // =============================================================
    public record ServerboundOpenGui() implements CustomPacketPayload {
        public static final Type<ServerboundOpenGui> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MCItemStockMarket.MODID, "open_gui"));
        public static final StreamCodec<ByteBuf, ServerboundOpenGui> STREAM_CODEC = StreamCodec.unit(new ServerboundOpenGui());
        @Override public Type<ServerboundOpenGui> type() { return TYPE; }
    }

    // =============================================================
    // C2S 提交买卖委托
    // =============================================================
    public record ServerboundSubmitOrder(boolean isBuy, String stockFullName, int quantity)
            implements CustomPacketPayload {
        public static final Type<ServerboundSubmitOrder> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MCItemStockMarket.MODID, "submit_order"));
        public static final StreamCodec<ByteBuf, ServerboundSubmitOrder> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.isBuy);
                    writeUtf(buf, p.stockFullName);
                    buf.writeInt(p.quantity);
                },
                buf -> new ServerboundSubmitOrder(
                        buf.readBoolean(), readUtf(buf), buf.readInt()
                ));
        @Override public Type<ServerboundSubmitOrder> type() { return TYPE; }
    }

    // =============================================================
    // C2S 取消本人所有待成交委托
    // =============================================================
    public record ServerboundCancelOrders() implements CustomPacketPayload {
        public static final Type<ServerboundCancelOrders> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MCItemStockMarket.MODID, "cancel_orders"));
        public static final StreamCodec<ByteBuf, ServerboundCancelOrders> STREAM_CODEC = StreamCodec.unit(new ServerboundCancelOrders());
        @Override public Type<ServerboundCancelOrders> type() { return TYPE; }
    }

    // =============================================================
    // C2S 货币 <-> 物品兑换
    // buyItem=true : 货币 -> 物品（消耗货币，获得物品）
    // buyItem=false: 物品 -> 货币（消耗物品，获得货币）
    // =============================================================
    public record ServerboundExchange(boolean buyItem, String stockFullName, int quantity)
            implements CustomPacketPayload {
        public static final Type<ServerboundExchange> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MCItemStockMarket.MODID, "exchange"));
        public static final StreamCodec<ByteBuf, ServerboundExchange> STREAM_CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeBoolean(p.buyItem);
                    writeUtf(buf, p.stockFullName);
                    buf.writeInt(p.quantity);
                },
                buf -> new ServerboundExchange(
                        buf.readBoolean(), readUtf(buf), buf.readInt()
                ));
        @Override public Type<ServerboundExchange> type() { return TYPE; }
    }

    // ================ UTF 读写辅助 ================
    private static final java.nio.charset.Charset UTF8 = java.nio.charset.StandardCharsets.UTF_8;
    /** 股票全名等字符串的最大字节数（256 字符 × 4 字节 UTF-8 = 1024），防止恶意客户端 OOM。*/
    private static final int MAX_STRING_BYTES = 1024;
    static void writeUtf(ByteBuf buf, String s) {
        byte[] bs = s.getBytes(UTF8);
        buf.writeInt(bs.length);
        buf.writeBytes(bs);
    }
    static String readUtf(ByteBuf buf) {
        int len = buf.readInt();
        // 安全修复 #1：限制字符串长度，防止恶意客户端发送超大 len 导致 OOM 崩服
        if (len < 0 || len > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("非法字符串长度: " + len + " (最大 " + MAX_STRING_BYTES + ")");
        }
        if (buf.readableBytes() < len) {
            throw new IllegalArgumentException("缓冲区可读字节不足: 需要 " + len + " 实有 " + buf.readableBytes());
        }
        byte[] bs = new byte[len];
        buf.readBytes(bs);
        return new String(bs, UTF8);
    }
}
