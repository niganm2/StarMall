package com.small.service.impl;

import com.small.domain.po.Address;
import com.small.mapper.AddressMapper;
import com.small.service.IAddressService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
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
