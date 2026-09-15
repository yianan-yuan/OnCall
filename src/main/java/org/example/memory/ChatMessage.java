package org.example.memory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/**
 * 一条对话消息
 * <p>
 * 只保留角色与内容两个字段：这是压缩与上下文拼装真正需要的信息，
 * 时间戳等展示类字段放在会话状态上，避免每条消息都冗余存储。
 */
@Setter
@Getter
public class ChatMessage {

    /** 用户角色 */
    public static final String ROLE_USER = "user";

    /** 助手角色 */
    public static final String ROLE_ASSISTANT = "assistant";

    /**
     * 角色：user / assistant
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    public ChatMessage() {
    }

    public ChatMessage(String role, String content) {
        this.role = role;
        this.content = content;
    }

    /**
     * 创建一条用户消息
     *
     * @param content 内容
     * @return 消息对象
     */
    public static ChatMessage user(String content) {
        return new ChatMessage(ROLE_USER, content);
    }

    /**
     * 创建一条助手消息
     *
     * @param content 内容
     * @return 消息对象
     */
    public static ChatMessage assistant(String content) {
        return new ChatMessage(ROLE_ASSISTANT, content);
    }

    /**
     * 判断是否为助手消息（加 @JsonIgnore，避免被当成数据字段序列化进会话 JSON）
     *
     * @return true 表示助手回复
     */
    @JsonIgnore
    public boolean isAssistant() {
        return ROLE_ASSISTANT.equalsIgnoreCase(role);
    }

    /**
     * 判断是否为用户消息
     *
     * @return true 表示用户提问
     */
    @JsonIgnore
    public boolean isUser() {
        return ROLE_USER.equalsIgnoreCase(role);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChatMessage)) {
            return false;
        }
        ChatMessage that = (ChatMessage) o;
        return Objects.equals(role, that.role) && Objects.equals(content, that.content);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, content);
    }

    @Override
    public String toString() {
        return "ChatMessage{" +
                "role='" + role + '\'' +
                ", contentLength=" + (content == null ? 0 : content.length()) +
                '}';
    }
}
