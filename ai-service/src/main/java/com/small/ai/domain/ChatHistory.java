package com.small.ai.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * AI 会话目录（对应 sm-ai.chat_history 表）。
 */
@Data
public class ChatHistory {
    private Long id;
    /** 场景标识，对应具体 xxxChatClient */
    private String type;
    /** 会话ID，与 Spring AI 的 conversation_id 一致 */
    private String chatId;
    /** 所属用户ID（取自网关下传身份，前端不可信） */
    private Long userId;
    /** 会话标题（取首条用户消息截断） */
    private String title;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
