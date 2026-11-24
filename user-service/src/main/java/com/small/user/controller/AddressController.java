package com.small.user.controller;


import com.small.common.exception.BadRequestException;
import com.small.common.utils.BeanUtils;
import com.small.common.utils.CollUtils;
import com.small.common.utils.UserContext;
import com.small.user.domain.dto.AddressDTO;
import com.small.user.domain.po.Address;
import com.small.user.service.IAddressService;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author YL
 */
@RestController
@RequestMapping("/addresses")
@RequiredArgsConstructor
@Api(tags = "收货地址管理接口")
public class AddressController {

    private final IAddressService addressService;

    @ApiOperation("根据id查询地址")
    @GetMapping("{addressId}")
    public AddressDTO findAddressById(@ApiParam("地址id") @PathVariable("addressId") Long id) {
        // 1.根据id查询
        Address address = addressService.getById(id);
        // 2.判断地址是否存在
        if (address == null) {
            throw new BadRequestException("收货地址不存在");
        }
        // 3.判断当前用户
        Long userId = UserContext.getUser();
        if(!address.getUserId().equals(userId)){
            throw new BadRequestException("地址不属于当前登录用户");
        }
        return BeanUtils.copyBean(address, AddressDTO.class);
    }
    @ApiOperation("查询当前用户地址列表")
    @GetMapping
    public List<AddressDTO> findMyAddresses() {
        // 1.查询列表
        List<Address> list = addressService.query().eq("user_id", UserContext.getUser()).list();
        // 2.判空
        if (CollUtils.isEmpty(list)) {
            return CollUtils.emptyList();
        }
        // 3.转vo
        return BeanUtils.copyList(list, AddressDTO.class);
    }

    @ApiOperation("新增收货地址")
    @PostMapping
    public Long saveAddress(@RequestBody AddressDTO dto) {
        // 1.参数校验
        validateAddress(dto);
        // 2.保存，用户id取自登录信息
        Address address = BeanUtils.copyBean(dto, Address.class);
        address.setId(null);
        address.setUserId(UserContext.getUser());
        if (address.getIsDefault() == null) {
            address.setIsDefault(0);
        }
        if (address.getIsDefault() == 1) {
            clearDefault();
        }
        addressService.save(address);
        return address.getId();
    }

    @ApiOperation("修改收货地址")
    @PutMapping("{addressId}")
    public void updateAddress(@ApiParam("地址id") @PathVariable("addressId") Long id,
                              @RequestBody AddressDTO dto) {
        // 1.校验地址归属
        Address old = getOwnAddress(id);
        validateAddress(dto);
        // 2.更新
        Address address = BeanUtils.copyBean(dto, Address.class);
        address.setId(id);
        address.setUserId(old.getUserId());
        if (address.getIsDefault() == null) {
            address.setIsDefault(old.getIsDefault());
        }
        if (address.getIsDefault() == 1) {
            clearDefault();
        }
        addressService.updateById(address);
    }

    @ApiOperation("删除收货地址")
    @DeleteMapping("{addressId}")
    public void deleteAddress(@ApiParam("地址id") @PathVariable("addressId") Long id) {
        getOwnAddress(id);
        addressService.removeById(id);
    }

    @ApiOperation("批量删除收货地址")
    @DeleteMapping
    public void deleteAddresses(@ApiParam("地址id列表") @RequestParam("ids") List<Long> ids) {
        if (CollUtils.isEmpty(ids)) {
            throw new BadRequestException("请选择要删除的地址");
        }
        addressService.remove(new LambdaQueryWrapper<Address>()
                .in(Address::getId, ids)
                .eq(Address::getUserId, UserContext.getUser()));
    }

    @ApiOperation("设为默认地址")
    @PutMapping("{addressId}/default")
    public void setDefaultAddress(@ApiParam("地址id") @PathVariable("addressId") Long id) {
        // 1.校验地址归属
        getOwnAddress(id);
        // 2.先清除其它默认，再设置当前为默认（保证只存在一个默认地址）
        clearDefault();
        addressService.update(new LambdaUpdateWrapper<Address>()
                .eq(Address::getId, id)
                .eq(Address::getUserId, UserContext.getUser())
                .set(Address::getIsDefault, 1));
    }

    /**
     * 校验地址存在且属于当前登录用户
     */
    private Address getOwnAddress(Long id) {
        Address address = addressService.getById(id);
        if (address == null || !address.getUserId().equals(UserContext.getUser())) {
            throw new BadRequestException("收货地址不存在");
        }
        return address;
    }

    /**
     * 参数校验
     */
    private void validateAddress(AddressDTO dto) {
        if (StrUtil.isBlank(dto.getContact()) || StrUtil.isBlank(dto.getMobile()) || StrUtil.isBlank(dto.getStreet())) {
            throw new BadRequestException("姓名、电话和详细地址不能为空");
        }
        if (!dto.getMobile().matches("^1[3-9]\\d{9}$")) {
            throw new BadRequestException("请输入正确的11位手机号");
        }
    }

    /**
     * 清除当前用户其他地址的默认标记
     */
    private void clearDefault() {
        addressService.update(new LambdaUpdateWrapper<Address>()
                .eq(Address::getUserId, UserContext.getUser())
                .set(Address::getIsDefault, 0));
    }
}
