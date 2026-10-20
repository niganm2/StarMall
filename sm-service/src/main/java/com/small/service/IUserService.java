package com.small.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.small.domain.dto.LoginFormDTO;
import com.small.domain.po.User;
import com.small.domain.vo.UserLoginVO;

/**
 * <p>
 * 用户表 服务类
 * </p>
 *
 * @author YL
 * @since 2026-07-12
 */
public interface IUserService extends IService<User> {

    UserLoginVO login(LoginFormDTO loginFormDTO);

    void deductMoney(String pw, Integer totalFee);
}
