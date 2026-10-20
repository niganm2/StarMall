package com.small.item.handler;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.small.api.dto.ItemDTO;
import com.small.common.exception.BizIllegalException;

import java.util.List;

/**
 * 商品接口的 Sentinel 限流/熔断兜底处理。
 *
 * @author YL
 */
public class ItemBlockHandler {

    /**
     * 商品批量查询被限流或熔断时的兜底处理，直接返回可读的中文提示
     */
    public static List<ItemDTO> queryByIds(List<Long> ids, BlockException e) {
        throw new BizIllegalException("商品查询请求过于频繁，请稍后重试！");
    }
}
