package org.example.memory;

import org.example.service.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

/**
 * 基于大模型的记忆摘要生成器
 * <p>
 * 使用标准对话模型（温度 0.7）完成摘要任务：摘要是"辅助任务"，不需要 AIOps 那样的低温度与长输出配置。
 * 模型实例懒加载并缓存，避免每轮对话都重复构建。
 */
@Component
public class LlmMemorySummarizer implements MemorySummarizer {

    private static final Logger logger = LoggerFactory.getLogger(LlmMemorySummarizer.class);

    private final ChatService chatService;

    /** 摘要模型（懒加载，只创建一次） */
    private volatile ChatModel summaryModel;

    public LlmMemorySummarizer(ChatService chatService) {
        this.chatService = chatService;
    }

    @Override
    public String summarize(String prompt) {
        ChatModel model = resolveSummaryModel();
        String summary = model.call(prompt);

        if (summary == null || summary.isBlank()) {
            throw new IllegalStateException("模型返回了空的记忆摘要");
        }

        logger.debug("记忆摘要生成完成，长度: {} 字", summary.length());
        return summary.trim();
    }

    /**
     * 懒加载摘要模型
     *
     * @return 聊天模型
     */
    private ChatModel resolveSummaryModel() {
        ChatModel cached = summaryModel;
        if (cached != null) {
            return cached;
        }

        synchronized (this) {
            if (summaryModel == null) {
                summaryModel = chatService.createStandardChatModel();
            }
            return summaryModel;
        }
    }
}
