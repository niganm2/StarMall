package com.small.ai.repository;

import com.small.ai.domain.ChatHistory;

import java.util.List;

/**
 * AI 会话目录仓储。
 */
public interface ChatHistoryRepository {

    /**
     * 新增或刷新会话（已存在时只更新最后活跃时间）。
     */
    void save(ChatHistory chatHistory);

    /**
     * 更新会话标题。
     */
    void updateTitle(String chatId, String title);

    /**
     * 查询某用户的全部会话（按最后活跃时间倒序）。
     */
    List<ChatHistory> findByUserId(Long userId);

    /**
     * 按会话ID查询。
     */
    ChatHistory findByChatId(String chatId);

    /**
     * 删除会话目录。
     */
    void deleteByChatId(String chatId);
}
