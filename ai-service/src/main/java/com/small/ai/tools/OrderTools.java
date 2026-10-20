package com.small.ai.tools;

import com.small.api.client.TradeClient;
import com.small.api.dto.OrderDetailDTO;
import com.small.api.dto.OrderFormDTO;
import com.small.api.dto.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 订单场景工具：通过 OpenFeign 调用订单服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTools {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TradeClient tradeClient;

    @Tool(description = "根据商品订单号查询订单详情（订单状态、订单金额、下单时间、支付时间、发货时间）")
    public String queryOrderById(@ToolParam(description = "商品订单号，纯数字") Long orderId) {
        if (orderId == null) {
            return "未提供订单号，无法查询。";
        }
        try {
            OrderVO order = tradeClient.queryOrderById(orderId);
            if (order == null || order.getId() == null) {
                return "未找到订单号 " + orderId + " 对应的订单。";
            }
            return String.format("订单号：%d，状态：%s，订单金额：%s元，下单时间：%s，支付时间：%s，发货时间：%s",
                    order.getId(), statusText(order.getStatus()), toYuan(order.getTotalFee()),
                    formatTime(order.getCreateTime()), formatTime(order.getPayTime()), formatTime(order.getConsignTime()));
        } catch (Exception e) {
            log.error("查询订单失败，orderId={}", orderId, e);
            return "订单服务暂时不可用，请稍后再试。";
        }
    }

    @Tool(description = "为用户直接创建新订单（用户说『下单/购买/买下/立即购买某商品』时使用，无需经过购物车）。创建成功后订单为待付款状态")
    public String createOrder(
            @ToolParam(description = "商品 id（数字），必须来自 searchItems 返回结果或对话中出现过的商品列表，禁止编造") Long itemId,
            @ToolParam(description = "购买数量，不传时默认 1", required = false) Integer num,
            ToolContext toolContext) {
        String userId = toolContext == null ? null : (String) toolContext.getContext().get("userId");
        if (userId == null) {
            return "未获取到登录用户，无法下单，请先登录。";
        }
        if (itemId == null) {
            return "未提供商品 id，无法下单。";
        }
        try {
            OrderDetailDTO detail = new OrderDetailDTO();
            detail.setItemId(itemId);
            detail.setNum(num == null || num < 1 ? 1 : num);
            OrderFormDTO form = new OrderFormDTO();
            // payment_type 列 NOT NULL：与门户 order-confirm.html 默认值保持一致（3=余额扣减，支付页可再改）
            form.setPaymentType(3);
            form.setDetails(List.of(detail));
            Long orderId = tradeClient.createOrder(userId, form);
            if (orderId == null) {
                return "下单失败，请稍后再试。";
            }
            OrderVO order = tradeClient.queryOrderById(orderId);
            String fee = order == null || order.getTotalFee() == null ? "未知" : toYuan(order.getTotalFee());
            log.debug("AI 创建订单成功，orderId={}, itemId={}, num={}", orderId, itemId, detail.getNum());
            return String.format("下单成功，订单号：%d，购买数量：%d，应付：%s元。订单当前待付款，请到订单页完成支付。",
                    orderId, detail.getNum(), fee);
        } catch (Exception e) {
            log.error("AI 创建订单失败，itemId={}, num={}", itemId, num, e);
            return "下单失败，可能是商品不存在或库存不足，请稍后再试。";
        }
    }

    @Tool(description = "取消订单（用户说『取消订单』『不要这个订单了』『取消某订单』时使用；仅未付款订单可取消，取消后库存自动恢复）")
    public String cancelOrder(
            @ToolParam(description = "订单号（数字），必须来自 queryOrderById 返回结果或对话中出现过的订单列表，禁止编造") Long orderId,
            ToolContext toolContext) {
        String userId = toolContext == null ? null : (String) toolContext.getContext().get("userId");
        if (userId == null) {
            return "未获取到登录用户，无法取消订单，请先登录。";
        }
        if (orderId == null) {
            return "未提供订单号，无法取消。";
        }
        try {
            tradeClient.cancelOrder(userId, orderId);
            log.debug("AI 取消订单成功，orderId={}", orderId);
            return String.format("订单 %d 已成功取消，商品库存已恢复。", orderId);
        } catch (Exception e) {
            log.error("AI 取消订单失败，orderId={}", orderId, e);
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("订单不存在")) {
                return "订单 " + orderId + " 不存在，请确认订单号是否正确。";
            }
            if (msg.contains("订单状态不允许取消")) {
                return "订单 " + orderId + " 当前状态不允许取消（仅未付款订单可取消）。";
            }
            return "取消订单失败，请稍后再试。";
        }
    }

    @Tool(description = "删除订单（用户说『删除订单』『删掉某订单』时使用；物理删除连同订单明细和物流记录一起删除，不可恢复）")
    public String deleteOrder(
            @ToolParam(description = "订单号（数字），必须来自 queryOrderById 返回结果或对话中出现过的订单列表，禁止编造") Long orderId,
            ToolContext toolContext) {
        String userId = toolContext == null ? null : (String) toolContext.getContext().get("userId");
        if (userId == null) {
            return "未获取到登录用户，无法删除订单，请先登录。";
        }
        if (orderId == null) {
            return "未提供订单号，无法删除。";
        }
        try {
            tradeClient.deleteOrder(userId, orderId);
            log.debug("AI 删除订单成功，orderId={}", orderId);
            return String.format("订单 %d 已成功删除，不可恢复。", orderId);
        } catch (Exception e) {
            log.error("AI 删除订单失败，orderId={}", orderId, e);
            return "删除订单失败，请稍后再试。";
        }
    }

    private static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 1 -> "未付款";
            case 2 -> "已付款，未发货";
            case 3 -> "已发货，未确认";
            case 4 -> "确认收货，交易成功";
            case 5 -> "交易取消，订单关闭";
            case 6 -> "交易结束，已评价";
            default -> "未知状态(" + status + ")";
        };
    }

    private static String toYuan(Integer fen) {
        if (fen == null) {
            return "未知";
        }
        return new BigDecimal(fen).movePointLeft(2).stripTrailingZeros().toPlainString();
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "无" : TIME_FORMATTER.format(time);
    }
}
