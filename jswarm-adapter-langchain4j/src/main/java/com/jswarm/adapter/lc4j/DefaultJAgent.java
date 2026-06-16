package com.jswarm.adapter.lc4j;

import com.jswarm.adapter.lc4j.tool.Lc4jToolBridge;
import com.jswarm.core.SwarmContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class DefaultJAgent implements JAgent {

    private final String id;
    private final String name;
    private final String description;
    private final String instructions;
    private final Function<SwarmContext, String> instructionsProvider;
    private final ChatModel model;
    private final StreamingChatModel streamingModel;
    private final List<ToolSpecification> externalTools;
    private final ExternalToolExecutor toolExecutor;
    private final Consumer<SwarmContext> onEnter;
    private final Consumer<SwarmContext> onExit;
    private final BiConsumer<SwarmContext, String> onDelegateEnter;
    private final DelegateExitHook onDelegateExit;

    protected DefaultJAgent(String id, String name, String description, String instructions, ChatModel model,
                            List<ToolSpecification> externalTools, ExternalToolExecutor toolExecutor) {
        validateCoreFields(description, instructions, null, model);
        this.id = id;
        this.name = name;
        this.description = description;
        this.instructions = instructions;
        this.instructionsProvider = null;
        this.model = model;
        this.streamingModel = null;
        this.externalTools = externalTools != null ? List.copyOf(externalTools) : List.of();
        this.toolExecutor = toolExecutor;
        this.onEnter = null;
        this.onExit = null;
        this.onDelegateEnter = null;
        this.onDelegateExit = null;
    }

    protected DefaultJAgent(Builder builder) {
        validateCoreFields(builder.description, builder.instructions, builder.instructionsProvider, builder.model);
        Lc4jToolBridge.BridgeResult bridge = Lc4jToolBridge.bridge(builder.toolBeans);
        this.id = builder.id;
        this.name = builder.name;
        this.description = builder.description;
        this.instructions = builder.instructions;
        this.instructionsProvider = builder.instructionsProvider;
        this.model = builder.model;
        this.streamingModel = builder.streamingModel;
        this.externalTools = bridge.specs();
        this.toolExecutor = bridge.executor();
        this.onEnter = builder.onEnter;
        this.onExit = builder.onExit;
        this.onDelegateEnter = builder.onDelegateEnter;
        this.onDelegateExit = builder.onDelegateExit;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String description() {
        return description;
    }

    @Override
    public String instructions() {
        if (instructionsProvider != null) {
            SwarmContext ctx = SwarmContext.current();
            if (ctx != null) {
                String resolved = instructionsProvider.apply(ctx);
                if (resolved == null || resolved.isBlank()) {
                    throw new IllegalStateException("dynamic instructions must not return null or blank");
                }
                return resolved;
            }
            if (instructions != null && !instructions.isBlank()) {
                return instructions;
            }
            throw new IllegalStateException("SwarmContext required when resolving dynamic instructions");
        }
        return instructions;
    }

    private static void validateCoreFields(String description, String instructions,
                                           Function<SwarmContext, String> instructionsProvider, ChatModel model) {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be null or blank");
        }
        if (instructionsProvider == null) {
            if (instructions == null || instructions.isBlank()) {
                throw new IllegalArgumentException("instructions must not be null or blank");
            }
        }
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
    }

    @Override
    public ChatModel model() {
        return model;
    }

    @Override
    public StreamingChatModel streamingModel() {
        if (streamingModel != null) {
            return streamingModel;
        }
        return JAgent.super.streamingModel();
    }

    @Override
    public List<ToolSpecification> externalTools() {
        return externalTools;
    }

    @Override
    public ExternalToolExecutor toolExecutor() {
        return toolExecutor;
    }

    @Override
    public void onEnter(SwarmContext context) {
        if (onEnter != null) {
            onEnter.accept(context);
        }
    }

    @Override
    public void onExit(SwarmContext context) {
        if (onExit != null) {
            onExit.accept(context);
        }
    }

    @Override
    public void onDelegateEnter(SwarmContext context, String task) {
        if (onDelegateEnter != null) {
            onDelegateEnter.accept(context, task);
        }
    }

    @Override
    public void onDelegateExit(SwarmContext context, String task, String result) {
        if (onDelegateExit != null) {
            onDelegateExit.accept(context, task, result);
        }
    }

    public static Builder builder(String id, String name) {
        return new Builder(id, name);
    }

    public static class Builder {
        private final String id;
        private final String name;
        private String description;
        private String instructions;
        private Function<SwarmContext, String> instructionsProvider;
        private ChatModel model;
        private StreamingChatModel streamingModel;
        private Object[] toolBeans = new Object[0];
        private Consumer<SwarmContext> onEnter;
        private Consumer<SwarmContext> onExit;
        private BiConsumer<SwarmContext, String> onDelegateEnter;
        private DelegateExitHook onDelegateExit;

        private Builder(String id, String name) {
            this.id = id;
            this.name = name;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder instructions(String instructions) {
            this.instructions = instructions;
            this.instructionsProvider = null;
            return this;
        }

        public Builder instructions(Function<SwarmContext, String> instructionsProvider) {
            this.instructionsProvider = instructionsProvider;
            this.instructions = null;
            return this;
        }

        public Builder model(ChatModel model) {
            this.model = model;
            return this;
        }

        public Builder streamingModel(StreamingChatModel streamingModel) {
            this.streamingModel = streamingModel;
            return this;
        }

        public Builder tools(Object... toolBeans) {
            this.toolBeans = toolBeans != null ? toolBeans : new Object[0];
            return this;
        }

        public Builder onEnter(Consumer<SwarmContext> onEnter) {
            this.onEnter = onEnter;
            return this;
        }

        public Builder onExit(Consumer<SwarmContext> onExit) {
            this.onExit = onExit;
            return this;
        }

        public Builder onDelegateEnter(BiConsumer<SwarmContext, String> onDelegateEnter) {
            this.onDelegateEnter = onDelegateEnter;
            return this;
        }

        public Builder onDelegateExit(DelegateExitHook onDelegateExit) {
            this.onDelegateExit = onDelegateExit;
            return this;
        }

        public JAgent build() {
            return new DefaultJAgent(this);
        }
    }
}
