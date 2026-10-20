package com.small.ai.controller;

import com.small.ai.context.UserContext;
import com.small.ai.domain.ChatHistory;
import com.small.ai.domain.ChatMessageVO;
import com.small.ai.domain.ChatRequest;
import com.small.ai.domain.ChatSessionVO;
import com.small.ai.repository.ChatHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import reactor.core.publisher.Flux;

/**
 * AI 助手接口。
 * 全部接口都要求登录：用户身份取自网关解析 JWT 后透传的 user-info，前端传入的 userId 不可信。
 */
@Slf4j
@RestController
@RequestMapping("/ai")
@RequiredArgsConstructor
public class ChatController {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int TITLE_MAX_LENGTH = 20;

    private static final String SCENE_SHOPPING = "shopping";
    private static final String SCENE_ORDER = "order";
    private static final String SCENE_CART = "cart";
    private static final String SCENE_SUPPORT = "support";
    private static final Set<String> SCENE_NAMES = Set.of(SCENE_SHOPPING, SCENE_ORDER, SCENE_CART, SCENE_SUPPORT);

    private static final List<String> ORDER_KEYWORDS = List.of("订单", "物流", "发货", "收货", "运单", "付款", "支付", "下单", "购买", "买下", "结算", "取消订单", "删除订单");
    private static final List<String> CART_KEYWORDS = List.of("购物车", "加购", "加入购物车", "清空购物车");
    private static final List<String> SUPPORT_KEYWORDS = List.of("客服", "售后", "投诉", "发票", "退换", "规则", "运费");
    /** 用户的确认/应答类输入：不触发重新场景路由，而是延续上一个场景 */
    private static final List<String> CONFIRM_KEYWORDS = List.of("确认", "好的", "可以", "执行", "对", "嗯", "是", "是的", "ok", "OK", "OK了", "好", "行", "没问题", "确定", "没错", "就这样");
    /** 用户表示放弃/取消的输入：也不触发重新路由，延续上一个场景让模型处理 */
    private static final List<String> DECLINE_KEYWORDS = List.of("不用了", "取消", "算了", "不要了", "不买了", "不加了", "先不", "等一下", "等会儿");

    @Qualifier("shoppingChatClient")
    private final ChatClient shoppingChatClient;
    @Qualifier("orderChatClient")
    private final ChatClient orderChatClient;
    @Qualifier("cartChatClient")
    private final ChatClient cartChatClient;
    @Qualifier("supportChatClient")
    private final ChatClient supportChatClient;

    private final ChatHistoryRepository chatHistoryRepository;

    private final ChatMemoryRepository springAiChatMemoryRepository;

    /**
     * 与用户对话，返回 AI 回复的纯文本（门户前端 axios 会一次性拿到全文）。
     */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public String chat(@RequestBody ChatRequest request) {
        ChatPrep prep = prepare(request);
        long start = System.currentTimeMillis();
        String answer = resolveClient(prep.scene()).prompt()
                .user(prep.content())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, prep.chatId()))
                .toolContext(Map.of("userId", String.valueOf(prep.userId())))
                .call()
                .content();
        log.debug("AI 对话完成，scene={}, chatId={}, userId={}, 耗时={}ms",
                prep.scene(), prep.chatId(), prep.userId(), System.currentTimeMillis() - start);
        return answer == null ? "" : answer;
    }

    /**
     * 与用户对话的流式版本：以 SSE 逐段返回回复，首字延迟从数秒降到 1 秒左右。
     * 响应头 X-Accel-Buffering: no 用于让 nginx 对本响应关闭代理缓冲，否则分段会被攒成整块。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<String>> chatStream(@RequestBody ChatRequest request) {
        ChatPrep prep = prepare(request);
        long start = System.currentTimeMillis();
        Flux<String> body = resolveClient(prep.scene()).prompt()
                .user(prep.content())
                .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, prep.chatId()))
                .toolContext(Map.of("userId", String.valueOf(prep.userId())))
                .stream()
                .content()
                .doOnComplete(() -> log.debug("AI 流式对话完成，scene={}, chatId={}, userId={}, 耗时={}ms",
                        prep.scene(), prep.chatId(), prep.userId(), System.currentTimeMillis() - start));
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    /**
     * 对话前置处理：校验登录、解析场景与会话 id、确保会话目录存在。
     */
    private ChatPrep prepare(ChatRequest request) {
        Long userId = requireLogin();
        String content = request == null ? null : request.getContent();
        if (!StringUtils.hasText(content)) {
            throw new IllegalArgumentException("请输入要发送的内容");
        }
        content = content.trim();

        String chatId = resolveChatId(userId, request.getChatId());
        String scene = resolveScene(request.getScene(), content, chatId);

        ChatHistory session = chatHistoryRepository.findByChatId(chatId);
        if (session == null) {
            ChatHistory created = new ChatHistory();
            created.setType(scene);
            created.setChatId(chatId);
            created.setUserId(userId);
            created.setTitle(truncateTitle(content));
            chatHistoryRepository.save(created);
            log.debug("新建 AI 会话，chatId={}, userId={}, scene={}", chatId, userId, scene);
        }
        return new ChatPrep(userId, content, scene, chatId);
    }

    private record ChatPrep(Long userId, String content, String scene, String chatId) {}

    /**
     * 查询当前登录用户的会话列表。
     */
    @GetMapping("/sessions")
    public List<ChatSessionVO> sessions() {
        Long userId = requireLogin();
        return chatHistoryRepository.findByUserId(userId).stream()
                .map(history -> new ChatSessionVO(history.getChatId(), history.getTitle(), history.getType(),
                        formatTime(history.getUpdateTime())))
                .toList();
    }

    /**
     * 查询某个会话的历史消息（仅允许访问自己的会话）。
     */
    @GetMapping("/sessions/{chatId}/messages")
    public List<ChatMessageVO> messages(@PathVariable("chatId") String chatId) {
        requireOwner(chatId);
        List<Message> messages = springAiChatMemoryRepository.findByConversationId(chatId);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
                .map(message -> new ChatMessageVO(message.getMessageType().name(), message.getText()))
                .toList();
    }

    /**
     * 删除某个会话（目录 + 记忆消息，仅允许删除自己的会话）。
     */
    @DeleteMapping("/sessions/{chatId}")
    public void deleteSession(@PathVariable("chatId") String chatId) {
        requireOwner(chatId);
        springAiChatMemoryRepository.deleteByConversationId(chatId);
        chatHistoryRepository.deleteByChatId(chatId);
        log.debug("删除 AI 会话，chatId={}", chatId);
    }

    private Long requireLogin() {
        Long userId = UserContext.getUser();
        if (userId == null) {
            throw new IllegalStateException("未获取到登录用户，请先登录后再使用 AI 助手");
        }
        return userId;
    }

    private void requireOwner(String chatId) {
        Long userId = requireLogin();
        ChatHistory session = chatHistoryRepository.findByChatId(chatId);
        if (session == null || !userId.equals(session.getUserId())) {
            throw new IllegalStateException("无权访问该会话");
        }
    }

    /**
     * 解析会话ID：显式传入时校验归属；未传入时复用当前用户最近一次会话，保证多轮对话连续。
     */
    private String resolveChatId(Long userId, String chatId) {
        if (StringUtils.hasText(chatId)) {
            String trimmed = chatId.trim();
            ChatHistory session = chatHistoryRepository.findByChatId(trimmed);
            if (session != null && !userId.equals(session.getUserId())) {
                throw new IllegalStateException("无权访问该会话");
            }
            return trimmed;
        }
        List<ChatHistory> sessions = chatHistoryRepository.findByUserId(userId);
        if (!sessions.isEmpty()) {
            return sessions.get(0).getChatId();
        }
        return UUID.randomUUID().toString();
    }

    /**
     * 解析场景：显式指定优先；否则按内容关键字路由；
     * 确认/放弃词、或未命中任何关键字时，延续该会话上一个场景；
     * 仅全新会话兜底为导购场景。路由决策打 info 日志便于排查"模型臆想操作"类问题。
     */
    private String resolveScene(String scene, String content, String chatId) {
        if (StringUtils.hasText(scene)) {
            String trimmed = scene.trim();
            if (!SCENE_NAMES.contains(trimmed)) {
                throw new IllegalArgumentException("不支持的场景：" + trimmed + "，可选 shopping、order、cart、support");
            }
            log.info("【场景路由】显式指定 scene={}, content={}", trimmed, abbreviate(content));
            return trimmed;
        }
        // 用户说确认/放弃词 → 从会话历史取上一个场景延续
        if (isConfirmOrDecline(content)) {
            ChatHistory session = chatHistoryRepository.findByChatId(chatId);
            if (session != null && StringUtils.hasText(session.getType())) {
                log.info("【场景路由】确认/放弃词，延续上个场景 scene={}, content={}", session.getType(), abbreviate(content));
                return session.getType();
            }
        }
        if (containsAny(content, ORDER_KEYWORDS)) {
            log.info("【场景路由】命中订单关键字 scene=order, content={}", abbreviate(content));
            return SCENE_ORDER;
        }
        if (containsAny(content, CART_KEYWORDS)) {
            log.info("【场景路由】命中购物车关键字 scene=cart, content={}", abbreviate(content));
            return SCENE_CART;
        }
        if (containsAny(content, SUPPORT_KEYWORDS)) {
            log.info("【场景路由】命中客服关键字 scene=support, content={}", abbreviate(content));
            return SCENE_SUPPORT;
        }
        // 未命中任何关键字：延续上个场景（避免"就第一款吧"这类指代回落到无对应工具的场景）
        ChatHistory session = chatHistoryRepository.findByChatId(chatId);
        if (session != null && StringUtils.hasText(session.getType())) {
            log.info("【场景路由】未命中关键字，延续上个场景 scene={}, content={}", session.getType(), abbreviate(content));
            return session.getType();
        }
        log.info("【场景路由】全新会话默认导购场景 scene=shopping, content={}", abbreviate(content));
        return SCENE_SHOPPING;
    }

    private static String abbreviate(String content) {
        if (content == null) {
            return "";
        }
        String trimmed = content.trim();
        return trimmed.length() <= 30 ? trimmed : trimmed.substring(0, 30) + "...";
    }

    private static boolean isConfirmOrDecline(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String trimmed = content.trim().toLowerCase();
        for (String kw : CONFIRM_KEYWORDS) {
            if (trimmed.equals(kw.toLowerCase()) || trimmed.contains(kw.toLowerCase())) {
                return true;
            }
        }
        for (String kw : DECLINE_KEYWORDS) {
            if (trimmed.equals(kw.toLowerCase()) || trimmed.contains(kw.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private ChatClient resolveClient(String scene) {
        return switch (scene) {
            case SCENE_ORDER -> orderChatClient;
            case SCENE_CART -> cartChatClient;
            case SCENE_SUPPORT -> supportChatClient;
            default -> shoppingChatClient;
        };
    }

    private static boolean containsAny(String content, List<String> keywords) {
        if (content == null) {
            return false;
        }
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static String truncateTitle(String content) {
        return content.length() <= TITLE_MAX_LENGTH ? content : content.substring(0, TITLE_MAX_LENGTH);
    }

    private static String formatTime(LocalDateTime time) {
        return time == null ? "" : TIME_FORMATTER.format(time);
    }
}
