package com.jswarm.adapter.lc4j;

import com.jswarm.adapter.lc4j.tool.Lc4jAgentExtractor;
import com.jswarm.adapter.lc4j.tool.Lc4jToolBridge;
import com.jswarm.core.Agent;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.List;
import java.util.Objects;

public interface JAgent extends Agent {

    ChatModel model();

    default StreamingChatModel streamingModel() {
        ChatModel m = model();
        if (m instanceof StreamingChatModel s) {
            return s;
        }
        return null;
    }

    default List<ToolSpecification> externalTools() {
        return List.of();
    }

    default ExternalToolExecutor toolExecutor() {
        return null;
    }

    static DefaultJAgent.Builder builder(String id, String name) {
        return DefaultJAgent.builder(id, name);
    }

    static JAgentDecorator decorate(JAgent delegate) {
        return new JAgentDecorator(Objects.requireNonNull(delegate, "delegate"));
    }

    static JAgent fromTools(String id, String name, String description,
                            String instructions, ChatModel model, Object... toolBeans) {
        Lc4jToolBridge.BridgeResult bridge = Lc4jToolBridge.bridge(toolBeans);
        return new DefaultJAgent(id, name, description, instructions, model, bridge.specs(), bridge.executor());
    }

    static JAgent fromAiService(String id, String name, String description,
                                Class<?> aiServiceInterface, ChatModel model, Object... toolBeans) {
        String instructions = Lc4jAgentExtractor.extractInstructions(aiServiceInterface);
        return fromTools(id, name, description, instructions, model, toolBeans);
    }
}
