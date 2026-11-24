package com.small.cart.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.small.api.client.ItemClient;
import com.small.api.dto.ItemDTO;
import com.small.cart.config.CartProperties;
import com.small.cart.domain.dto.CartFormDTO;
import com.small.cart.domain.po.Cart;
import com.small.cart.domain.vo.CartVO;
import com.small.cart.mapper.CartMapper;
import com.small.cart.service.ICartService;
import com.small.common.exception.BizIllegalException;
import com.small.common.utils.BeanUtils;
import com.small.common.utils.CollUtils;
import com.small.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>
 * 订单详情表 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2023-05-05
 */
@Service
@RequiredArgsConstructor
public class CartServiceImpl extends ServiceImpl<CartMapper, Cart> implements ICartService {

    private final ItemClient itemClient;

    private final CartProperties cartProperties;

    @Override
    public void addItem2Cart(CartFormDTO cartFormDTO) {
        // 1.获取登录用户
        Long userId = UserContext.getUser();
        // 2.查询商品：商品名称、价格、图片一律以后端为准，避免前端篡改
        List<ItemDTO> items = itemClient.queryItemByIds(Collections.singletonList(cartFormDTO.getItemId()));
        if (CollUtils.isEmpty(items)) {
            throw new BizIllegalException("商品不存在");
        }
        ItemDTO item = items.get(0);

        // 3.判断是否已经存在
        Cart existing = lambdaQuery()
                .eq(Cart::getUserId, userId)
                .eq(Cart::getItemId, item.getId())
                .one();
        if (existing != null) {
            // 3.1.存在，则数量+1，且不能超过库存
            int newNum = existing.getNum() + 1;
            checkStock(item, newNum);
            lambdaUpdate().set(Cart::getNum, newNum).eq(Cart::getId, existing.getId()).update();
            return;
        }
        // 3.2.不存在，判断购物车容量和库存
        checkCartsFull(userId);
        checkStock(item, 1);

        // 4.新增购物车条目（商品信息取自商品服务）
        Cart cart = new Cart();
        cart.setUserId(userId);
        cart.setItemId(item.getId());
        cart.setName(item.getName());
        cart.setSpec(cartFormDTO.getSpec());
        cart.setPrice(item.getPrice());
        cart.setImage(item.getImage());
        cart.setNum(1);
        save(cart);
    }

    @Override
    public void updateCart(Cart cart) {
        // 1.查询购物车条目并校验归属
        Cart db = getById(cart.getId());
        if (db == null) {
            throw new BizIllegalException("购物车条目不存在");
        }
        if (!db.getUserId().equals(UserContext.getUser())) {
            throw new BizIllegalException("无权操作该购物车");
        }
        // 2.校验数量
        Integer num = cart.getNum();
        if (num == null || num < 1) {
            throw new BizIllegalException("商品数量必须大于0");
        }
        // 3.校验库存
        List<ItemDTO> items = itemClient.queryItemByIds(Collections.singletonList(db.getItemId()));
        if (CollUtils.isEmpty(items)) {
            throw new BizIllegalException("商品不存在");
        }
        checkStock(items.get(0), num);
        // 4.只允许修改数量
        lambdaUpdate().set(Cart::getNum, num).eq(Cart::getId, db.getId()).update();
    }

    @Override
    public void removeMyCartItem(Long id) {
        boolean removed = remove(new QueryWrapper<Cart>().lambda()
                .eq(Cart::getId, id)
                .eq(Cart::getUserId, UserContext.getUser()));
        if (!removed) {
            throw new BizIllegalException("购物车条目不存在");
        }
    }

    private void checkStock(ItemDTO item, int num) {
        if (item.getStatus() != null && item.getStatus() != 1) {
            throw new BizIllegalException("商品已下架");
        }
        if (item.getStock() != null && num > item.getStock()) {
            throw new BizIllegalException("商品库存不足，最多可购买" + item.getStock() + "件");
        }
    }

    @Override
    public List<CartVO> queryMyCarts() {
        // 1.查询我的购物车列表
        List<Cart> carts = lambdaQuery().eq(Cart::getUserId, UserContext.getUser()).list();
        if (CollUtils.isEmpty(carts)) {
            return CollUtils.emptyList();
        }

        // 2.转换VO
        List<CartVO> vos = BeanUtils.copyList(carts, CartVO.class);

        // 3.处理VO中的商品信息
        handleCartItems(vos);

        // 4.返回
        return vos;
    }

    private void handleCartItems(List<CartVO> vos) {
        // 1.获取商品id
        Set<Long> itemIds = vos.stream().map(CartVO::getItemId).collect(Collectors.toSet());

        // 2.批量查询商品信息
        List<ItemDTO> items = itemClient.queryItemByIds(itemIds);
        if (CollUtils.isEmpty(items)) {
            return;
        }
        // 3.转为 id 到 item的map
        Map<Long, ItemDTO> itemMap = items.stream().collect(Collectors.toMap(ItemDTO::getId, Function.identity()));
        // 4.写入vo
        for (CartVO v : vos) {
            ItemDTO item = itemMap.get(v.getItemId());
            if (item == null) {
                continue;
            }
            v.setNewPrice(item.getPrice());
            v.setStatus(item.getStatus());
            v.setStock(item.getStock());
        }
    }

    @Override
    @Transactional
    public void removeByItemIds(Collection<Long> itemIds) {
        // 1.构建删除条件，userId和itemId
        QueryWrapper<Cart> queryWrapper = new QueryWrapper<Cart>();
        queryWrapper.lambda()
                .eq(Cart::getUserId, UserContext.getUser())
                .in(Cart::getItemId, itemIds);
        // 2.删除
        remove(queryWrapper);
    }

    private void checkCartsFull(Long userId) {
        Long count = lambdaQuery().eq(Cart::getUserId, userId).count();
        if (count >= cartProperties.getMaxItems()) {
            throw new BizIllegalException(StrUtil.format("用户购物车商品不能超过{}", cartProperties.getMaxItems()));
        }
    }
}