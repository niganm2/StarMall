package com.small.api.dto;

import lombok.Data;

import java.util.List;

/**
 * 分页结果（结构与 sm-common 的 PageDTO 保持一致的 JSON 字段：total/pages/list），
 * 但不依赖 MyBatis-Plus，避免把老服务的重依赖带进 ai-service。
 */
@Data
public class PageResult<T> {
    private Long total;
    private Long pages;
    private List<T> list;
}
