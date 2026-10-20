package com.small.ai.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 会话历史消息视图对象。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageVO {
    /** USER / ASSISTANT / SYSTEM / TOOL */
    private String role;
    private String content;
}
