package com.small.ai.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.small.ai.tools.CartTools;
import com.small.ai.tools.OrderTools;
import com.small.ai.tools.ShoppingTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * 4 个业务场景的 ChatClient。
 * 每个场景各自注入一次 ChatClient.Builder（该 Builder 为 prototype，彼此独立），
 * 三件套齐全：系统提示词 + 会话记忆 Advisor + 场景化模型参数。
 */
@Configuration
public class AiChatConfig {

    /** 会话记忆窗口：最多保留最近 10 条消息 */
    private static final int MAX_MESSAGES = 10;

    private static final Resource SHOPPING_PROMPT = new ClassPathResource("prompts/shopping.st");
    private static final Resource ORDER_PROMPT = new ClassPathResource("prompts/order.st");
    private static final Resource CART_PROMPT = new ClassPathResource("prompts/cart.st");
    private static final Resource SUPPORT_PROMPT = new ClassPathResource("prompts/support.st");

    /** 导购场景：强创造力的推荐模型 + 商品工具 + 购物车工具（导购要承接"选好了→加入购物车"的漏斗） */
    @Bean
    public ChatClient shoppingChatClient(ChatClient.Builder builder,
                                         ChatMemoryRepository chatMemoryRepository,
                                         ShoppingTools shoppingTools,
                                         CartTools cartTools) {
        return builder
                .defaultSystem(SHOPPING_PROMPT)
                .defaultAdvisors(memoryAdvisor(chatMemoryRepository))
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-plus")
                        .withTemperature(0.8)
                        .build())
                .defaultTools(shoppingTools, cartTools)
                .build();
    }

    /** 订单场景：低温度保证准确性 + 订单工具 + 商品工具（下单时需要 searchItems 查 id） */
    @Bean
    public ChatClient orderChatClient(ChatClient.Builder builder,
                                      ChatMemoryRepository chatMemoryRepository,
                                      OrderTools orderTools,
                                      ShoppingTools shoppingTools) {
        return builder
                .defaultSystem(ORDER_PROMPT)
                .defaultAdvisors(memoryAdvisor(chatMemoryRepository))
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-plus")
                        .withTemperature(0.1)
                        .build())
                .defaultTools(orderTools, shoppingTools)
                .build();
    }

    /** 购物车场景：低温度保证准确性 + 购物车工具 + 商品工具（加购时需要 searchItems 查 id） */
    @Bean
    public ChatClient cartChatClient(ChatClient.Builder builder,
                                     ChatMemoryRepository chatMemoryRepository,
                                     CartTools cartTools,
                                     ShoppingTools shoppingTools) {
        return builder
                .defaultSystem(CART_PROMPT)
                .defaultAdvisors(memoryAdvisor(chatMemoryRepository))
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-plus")
                        .withTemperature(0.1)
                        .build())
                .defaultTools(cartTools, shoppingTools)
                .build();
    }

    /** 通用客服场景：无工具 */
    @Bean
    public ChatClient supportChatClient(ChatClient.Builder builder,
                                        ChatMemoryRepository chatMemoryRepository) {
        return builder
                .defaultSystem(SUPPORT_PROMPT)
                .defaultAdvisors(memoryAdvisor(chatMemoryRepository))
                .defaultOptions(DashScopeChatOptions.builder()
                        .withModel("qwen-plus")
                        .withTemperature(0.7)
                        .build())
                .build();
    }

    /**
     * 会话记忆 Advisor：把消息按 conversationId 持久化到 MySQL，并限制窗口大小。
     * 具体会话 id 由调用方通过 ChatMemory.CONVERSATION_ID 参数逐次传入。
     */
    private MessageChatMemoryAdvisor memoryAdvisor(ChatMemoryRepository chatMemoryRepository) {
        MessageWindowChatMemory chatMemory = MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(MAX_MESSAGES)
                .build();
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}
