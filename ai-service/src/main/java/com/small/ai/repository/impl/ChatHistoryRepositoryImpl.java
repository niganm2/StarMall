package com.small.ai.repository.impl;

import com.small.ai.domain.ChatHistory;
import com.small.ai.repository.ChatHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class ChatHistoryRepositoryImpl implements ChatHistoryRepository {

    private static final RowMapper<ChatHistory> ROW_MAPPER = (rs, rowNum) -> {
        ChatHistory history = new ChatHistory();
        history.setId(rs.getLong("id"));
        history.setType(rs.getString("type"));
        history.setChatId(rs.getString("chat_id"));
        history.setUserId(rs.getLong("user_id"));
        history.setTitle(rs.getString("title"));
        history.setCreateTime(toLocalDateTime(rs.getTimestamp("create_time")));
        history.setUpdateTime(toLocalDateTime(rs.getTimestamp("update_time")));
        return history;
    };

    private static final String SQL_SAVE = """
            INSERT INTO chat_history (type, chat_id, user_id, title)
            VALUES (?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE update_time = CURRENT_TIMESTAMP
            """;
    private static final String SQL_UPDATE_TITLE = "UPDATE chat_history SET title = ? WHERE chat_id = ?";
    private static final String SQL_FIND_BY_USER = "SELECT * FROM chat_history WHERE user_id = ? ORDER BY update_time DESC";
    private static final String SQL_FIND_BY_CHAT_ID = "SELECT * FROM chat_history WHERE chat_id = ? LIMIT 1";
    private static final String SQL_DELETE = "DELETE FROM chat_history WHERE chat_id = ?";

    private final JdbcTemplate jdbcTemplate;

    @Override
    public void save(ChatHistory chatHistory) {
        jdbcTemplate.update(SQL_SAVE, chatHistory.getType(), chatHistory.getChatId(),
                chatHistory.getUserId(), chatHistory.getTitle());
    }

    @Override
    public void updateTitle(String chatId, String title) {
        jdbcTemplate.update(SQL_UPDATE_TITLE, title, chatId);
    }

    @Override
    public List<ChatHistory> findByUserId(Long userId) {
        return jdbcTemplate.query(SQL_FIND_BY_USER, ROW_MAPPER, userId);
    }

    @Override
    public ChatHistory findByChatId(String chatId) {
        List<ChatHistory> list = jdbcTemplate.query(SQL_FIND_BY_CHAT_ID, ROW_MAPPER, chatId);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public void deleteByChatId(String chatId) {
        jdbcTemplate.update(SQL_DELETE, chatId);
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
