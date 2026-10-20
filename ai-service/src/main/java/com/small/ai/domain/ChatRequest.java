package com.small.ai.domain;

import lombok.Data;

/**
 * /ai/chat 的请求体，与门户前端 js/ai-chat.js 的 axios 请求保持一致：
 * axios.post("/ai/chat", { content: "用户消息" })。
 */
@Data
public class ChatRequest {
    /** 用户消息内容 */
    private String content;
    /** 会话ID，可空；为空时后端自动复用当前用户最近一次会话（没有则新建） */
    private String chatId;
    /** 场景：shopping/order/cart/support，可空；为空时按内容关键字自动路由 */
    private String scene;
}
