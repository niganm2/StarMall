package com.small.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话目录视图对象。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionVO {
    private String chatId;
    private String title;
    private String scene;
    private String updateTime;
}
