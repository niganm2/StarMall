package com.small.user.domain.dto;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
@ApiModel(description = "注册表单实体")
public class RegisterFormDTO {

    @ApiModelProperty(value = "用户名", required = true)
    @NotBlank(message = "用户名不能为空")
    @Size(min = 4, max = 20, message = "用户名长度需在 4~20 位之间")
    @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
    private String username;

    @ApiModelProperty(value = "密码", required = true)
    @NotBlank(message = "密码不能为空")
    @Size(min = 6, max = 20, message = "密码长度需在 6~20 位之间")
    private String password;
}
