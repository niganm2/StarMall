package com.small.ai.tools;

import com.small.api.client.CartClient;
import com.small.api.client.ItemClient;
import com.small.api.dto.CartFormDTO;
import com.small.api.dto.CartItemVO;
import com.small.api.dto.ItemDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * 购物车场景工具：通过 OpenFeign 调用购物车服务与商品服务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CartTools {

    private final CartClient cartClient;

    private final ItemClient itemClient;

    @Tool(description = "查询当前登录用户的购物车列表，返回购物车中的商品、数量与价格")
    public List<CartItemVO> listMyCart(ToolContext toolContext) {
        String userId = toolContext == null ? null : (String) toolContext.getContext().get("userId");
        if (userId == null) {
            return Collections.emptyList();
        }
        try {
            List<CartItemVO> carts = cartClient.queryMyCarts(userId);
            return carts == null ? Collections.emptyList() : carts;
        } catch (Exception e) {
            log.error("查询购物车失败", e);
            return Collections.emptyList();
        }
    }

    @Tool(description = "将指定商品加入当前登录用户的购物车。" +
            "必须传入真实存在的商品id，禁止虚构商品id和加入结果。" +
            "调用后必须根据本方法的返回结果回复用户，成功就说成功，失败就告知失败原因。")
    public String addToCart(
            @ToolParam(description = "商品 id（数字），必须来自 searchItems 返回结果或对话中出现过的商品列表，禁止编造") Long itemId,
            ToolContext toolContext) {
        String userId = toolContext == null ? null : (String) toolContext.getContext().get("userId");
        log.info("【加购工具被调用】itemId={}, userId={}", itemId, userId);
        if (userId == null) {
            return "未获取到登录用户，无法加入购物车，请先登录。";
        }
        if (itemId == null) {
            return "未提供商品 id，无法加入购物车。";
        }
        try {
            ItemDTO item = itemClient.queryItemById(itemId);
            if (item == null || item.getId() == null) {
                return "未找到 id 为 " + itemId + " 的商品，无法加入购物车。";
            }
            CartFormDTO form = new CartFormDTO();
            form.setItemId(item.getId());
            form.setName(item.getName());
            form.setSpec(item.getSpec());
            form.setPrice(item.getPrice());
            form.setImage(item.getImage());
            cartClient.addItem2Cart(userId, form);
            return String.format("已把「%s」加入购物车（数量加一），单价 %s元。", item.getName(), toYuan(item.getPrice()));
        } catch (Exception e) {
            log.error("加入购物车失败，itemId={}", itemId, e);
            return "购物车服务暂时不可用，请稍后再试。";
        }
    }

    @Tool(description = "从当前登录用户的购物车中移除指定商品（用户说『从购物车删掉/移除某商品』时使用）。" +
            "入参是商品 id，工具内部会自动定位购物车里对应的记录并删除；商品不在购物车时会如实告知。")
    public String removeFromCart(
            @ToolParam(description = "商品 id（数字），必须来自 listMyCart/searchItems 返回结果或对话中出现过的商品列表，禁止编造") Long itemId,
            ToolContext toolContext) {
        String userId = toolContext == null ? null : (String) toolContext.getContext().get("userId");
        log.info("【移除购物车工具被调用】itemId={}, userId={}", itemId, userId);
        if (userId == null) {
            return "未获取到登录用户，无法移除购物车商品，请先登录。";
        }
        if (itemId == null) {
            return "未提供商品 id，无法移除购物车商品。";
        }
        try {
            List<CartItemVO> carts = cartClient.queryMyCarts(userId);
            if (carts == null || carts.isEmpty()) {
                return "你的购物车当前是空的，没有可移除的商品。";
            }
            CartItemVO target = carts.stream()
                    .filter(c -> itemId.equals(c.getItemId()))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return "购物车中没有商品 id 为 " + itemId + " 的商品，无需移除。";
            }
            cartClient.deleteCartItem(userId, target.getId());
            log.debug("AI 移除购物车商品成功，cartId={}, itemId={}, name={}", target.getId(), itemId, target.getName());
            return String.format("已从购物车移除「%s」。", target.getName());
        } catch (Exception e) {
            log.error("移除购物车商品失败，itemId={}", itemId, e);
            return "购物车服务暂时不可用，请稍后再试。";
        }
    }

    @Tool(description = "修改当前登录用户购物车中某商品的数量（用户说『数量加一/减一/改成N件』时使用）。" +
            "入参是商品 id 和修改后的目标数量，工具内部会自动定位购物车里对应的记录并更新；商品不在购物车时会如实告知。")
    public String updateCartQuantity(
            @ToolParam(description = "商品 id（数字），必须来自 listMyCart/searchItems 返回结果或对话中出现过的商品列表，禁止编造") Long itemId,
            @ToolParam(description = "修改后的目标数量（整数，最小 1）。用户说『加一』时先调 listMyCart 查当前数量再加一") Integer num,
            ToolContext toolContext) {
        String userId = toolContext == null ? null : (String) toolContext.getContext().get("userId");
        log.info("【改数量工具被调用】itemId={}, num={}, userId={}", itemId, num, userId);
        if (userId == null) {
            return "未获取到登录用户，无法修改购物车数量，请先登录。";
        }
        if (itemId == null) {
            return "未提供商品 id，无法修改购物车数量。";
        }
        if (num == null || num < 1) {
            return "目标数量必须是不小于 1 的整数。";
        }
        try {
            List<CartItemVO> carts = cartClient.queryMyCarts(userId);
            if (carts == null || carts.isEmpty()) {
                return "你的购物车当前是空的，没有可修改的商品。";
            }
            CartItemVO target = carts.stream()
                    .filter(c -> itemId.equals(c.getItemId()))
                    .findFirst()
                    .orElse(null);
            if (target == null) {
                return "购物车中没有商品 id 为 " + itemId + " 的商品。";
            }
            CartItemVO update = new CartItemVO();
            update.setId(target.getId());
            update.setNum(num);
            cartClient.updateCart(userId, update);
            log.debug("AI 修改购物车数量成功，cartId={}, itemId={}, num={} -> {}", target.getId(), itemId, target.getNum(), num);
            return String.format("已将「%s」的数量由 %d 件改为 %d 件，单价 %s元。",
                    target.getName(), target.getNum(), num, toYuan(target.getPrice()));
        } catch (Exception e) {
            log.error("修改购物车数量失败，itemId={}, num={}", itemId, num, e);
            return "购物车服务暂时不可用，请稍后再试。";
        }
    }

    private static String toYuan(Integer fen) {
        if (fen == null) {
            return "未知";
        }
        return new BigDecimal(fen).movePointLeft(2).stripTrailingZeros().toPlainString();
    }
}
