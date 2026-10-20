package com.small.user.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.small.user.domain.po.Address;
import com.small.user.mapper.AddressMapper;
import com.small.user.service.IAddressService;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author YL
 * @since 2026-07-12
 */
@Service
public class AddressServiceImpl extends ServiceImpl<AddressMapper, Address> implements IAddressService {

}
