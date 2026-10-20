package com.small.api.client;

import com.small.api.dto.CartFormDTO;
import com.small.api.dto.CartItemVO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.util.List;

/**
 * 购物车服务客户端。
 * 用户身份以显式请求头传递：流式对话时工具在 Reactor 线程执行，拿不到 RequestContextHolder，
 * 依赖拦截器从当前线程取 user-info 会丢失身份导致下单/加购 500。
 */
@FeignClient("cart-service")
public interface CartClient {

    /**
     * 查询当前登录用户的购物车列表。
     */
    @GetMapping("/carts")
    List<CartItemVO> queryMyCarts(@RequestHeader("user-info") String userId);

    /**
     * 添加商品到购物车。
     */
    @PostMapping("/carts")
    void addItem2Cart(@RequestHeader("user-info") String userId, @RequestBody CartFormDTO form);

    /**
     * 删除购物车中的某条商品记录（入参为购物车条目 id，即 CartItemVO.id）。
     */
    @DeleteMapping("/carts/{id}")
    void deleteCartItem(@RequestHeader("user-info") String userId, @PathVariable("id") Long id);

    /**
     * 更新购物车条目（按条目 id 更新非空字段；只改数量时仅传 id 和 num）。
     */
    @PutMapping("/carts")
    void updateCart(@RequestHeader("user-info") String userId, @RequestBody CartItemVO cart);
}
