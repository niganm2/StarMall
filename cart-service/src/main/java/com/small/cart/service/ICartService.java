package com.small.cart.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.small.cart.domain.dto.CartFormDTO;
import com.small.cart.domain.po.Cart;
import com.small.cart.domain.vo.CartVO;

import java.util.Collection;
import java.util.List;

/**
 * <p>
 * 订单详情表 服务类
 * </p>
 *
 * @author YL
 * @since 2026-07-12
 */
public interface ICartService extends IService<Cart> {

    void addItem2Cart(CartFormDTO cartFormDTO);

    void updateCart(Cart cart);

    void removeMyCartItem(Long id);

    List<CartVO> queryMyCarts();

    void removeByItemIds(Collection<Long> itemIds);
}
