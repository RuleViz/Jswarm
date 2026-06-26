package com.jswarm.adapter.lc4j.run;

import com.jswarm.adapter.lc4j.ExternalToolExecutor;
import com.jswarm.adapter.lc4j.JAgent;
import com.jswarm.adapter.lc4j.ToolProvider;
import com.jswarm.adapter.lc4j.invoke.StreamingChatInvoker;
import com.jswarm.core.Agent;
import com.jswarm.core.SwarmContext;
import com.jswarm.core.SwarmEvent;
import com.jswarm.adapter.lc4j.filter.FilterDecision;
import com.jswarm.adapter.lc4j.filter.SwarmFilter;
import com.jswarm.adapter.lc4j.invoke.ChatInvoker;
import com.jswarm.adapter.lc4j.tool.SwarmToolInjector;
import com.jswarm.adapter.lc4j.tool.ToolExecutionMerger;
import com.jswarm.core.Swarm;
import com.jswarm.core.SwarmException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class SwarmRunner {

    private final Swarm swarm;
    private final SwarmRunOptions options;
    private final SwarmFilter filter;
    private final ExternalToolExecutor swarmToolExecutor;
    private SwarmRunListener listener;

    private SwarmRunner(Swarm swarm, SwarmRunOptions options, ExternalToolExecutor swarmToolExecutor, SwarmRunListener listener) {
        this.swarm = swarm;
        this.options = options;
        this.filter = new SwarmFilter(swarm);
        this.swarmToolExecutor = swarmToolExecutor;
        this.listener = listener;
    }

    public void setListener(SwarmRunListener listener) {
        this.listener = listener;
    }

    public static SwarmRunner create(Swarm swarm) {
        return new SwarmRunner(swarm, SwarmRunOptions.defaults(), null, null);
    }

    public static SwarmRunner create(Swarm swarm, int maxTurns) {
        return new SwarmRunner(swarm,
                SwarmRunOptions.builder().maxTurns(maxTurns).build(),
                null, null);
    }

    public static SwarmRunner create(Swarm swarm, int maxTurns, ToolProvider toolProvider) {
        return create(swarm, maxTurns, (ExternalToolExecutor) toolProvider);
    }

    public static SwarmRunner create(Swarm swarm, int maxTurns, ExternalToolExecutor swarmToolExecutor) {
        return new SwarmRunner(swarm,
                SwarmRunOptions.builder().maxTurns(maxTurns).build(),
                swarmToolExecutor, null);
    }

    public static SwarmRunner create(Swarm swarm, SwarmRunOptions options) {
        return new SwarmRunner(swarm, options, null, null);
    }

    public static SwarmRunner create(Swarm swarm, SwarmRunOptions options, ToolProvider toolProvider) {
        return create(swarm, options, (ExternalToolExecutor) toolProvider);
    }

    public static SwarmRunner create(Swarm swarm, SwarmRunOptions options, ExternalToolExecutor swarmToolExecutor) {
        return new SwarmRunner(swarm, options, swarmToolExecutor, null);
    }

    public static SwarmRunner create(Swarm swarm, SwarmRunOptions options, ExternalToolExecutor swarmToolExecutor, SwarmRunListener listener) {
        return new SwarmRunner(swarm, options, swarmToolExecutor, listener);
    }

    public String run(String userMessage) {
        return run(userMessage, new SwarmContext());
    }

    public String run(String userMessage, SwarmContext context) {
        SwarmContext previous = SwarmContext.current();
        SwarmContext.set(context);
        try {
            return runInternal(userMessage);
        } finally {
            if (previous != null) {
                SwarmContext.set(previous);
            } else {
                SwarmContext.clear();
            }
        }
    }

    public record RunResult(String reply, String currentAgentId, List<ChatMessage> updatedHistory) {
    }

    public RunResult runWithHistory(String userMessage, List<ChatMessage> priorHistory,
                                    String startAgentId, SwarmContext context, boolean skipEntryHook) {
        SwarmContext previous = SwarmContext.current();
        SwarmContext.set(context);
        try {
            return runWithHistoryInternal(userMessage, priorHistory, startAgentId, skipEntryHook);
        } finally {
            if (previous != null) {
                SwarmContext.set(previous);
            } else {
                SwarmContext.clear();
            }
        }
    }

    private String runInternal(String userMessage) {
        return runWithHistoryInternal(userMessage, null, swarm.entryAgentId(), false).reply();
    }

    private RunResult runWithHistoryInternal(String userMessage, List<ChatMessage> priorHistory,
                                              String startAgentId, boolean skipEntryHook) {
        String currentAgentId = startAgentId;
        List<ChatMessage> currentMessages = null;
        String activeAgentId = null;
        int recoveryAttempts = 0;

        RuntimeException failure = null;
        try {
            Agent entry = swarm.getAgent(currentAgentId);
            requireJAgent(entry);
            if (!skipEntryHook) {
                entry.onEnter(SwarmContext.current());
            }
            activeAgentId = currentAgentId;
            fireOnEnter(currentAgentId, skipEntryHook ? "RESUME" : "ENTRY");

            for (int turn = 0; turn < options.maxTurns(); turn++) {
                Agent agent = swarm.getAgent(currentAgentId);
                JAgent runtimeAgent = requireJAgent(agent);

                List<ChatMessage> messages;
                if (currentMessages != null) {
                    messages = currentMessages;
                } else if (priorHistory != null && !priorHistory.isEmpty()) {
                    messages = new ArrayList<>(priorHistory);
                    messages.add(UserMessage.from(userMessage));
                    fireOnMessageHistoryUpdated(messages);
                } else {
                    messages = new ArrayList<>();
                    String instructions = agent.instructions();
                    if (instructions == null) {
                        throw new SwarmException("Agent '" + currentAgentId + "' has no instructions configured");
                    }
                    messages.add(SystemMessage.from(
                            SwarmContext.current().resolve(instructions)));
                    messages.add(UserMessage.from(userMessage));
                }

                List<ToolSpecification> tools = SwarmToolInjector.generateTools(
                        swarm, currentAgentId, runtimeAgent.externalTools());
                ExternalToolExecutor exec = ToolExecutionMerger.merge(runtimeAgent.toolExecutor(), swarmToolExecutor);

                ChatRequest request = ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(tools)
                        .build();

                AiMessage aiMessage = ChatInvoker.invoke(runtimeAgent, request, options.modelTimeout());

                if (!aiMessage.hasToolExecutionRequests()) {
                    messages.add(aiMessage);
                    fireOnMessageHistoryUpdated(messages);
                    activeAgentId = null;
                    agent.onExit(SwarmContext.current());
                    fireOnExit(currentAgentId);
                    String reply = aiMessage.text() != null ? aiMessage.text() : "";
                    fireOnRunComplete(reply);
                    return new RunResult(reply, currentAgentId, messages);
                }

                ToolExecutionRequest toolCall = aiMessage.toolExecutionRequests().get(0);

                FilterDecision decision;
                try {
                    decision = filter.decide(toolCall);
                } catch (SwarmException e) {
                    ensureCanRecover(recoveryAttempts);
                    recoveryAttempts++;
                    fireOnRecovery(currentAgentId, "Invalid tool args: " + e.getMessage());
                    recoverToolCall(messages, aiMessage, toolCall,
                            "Jswarm recovery: tool call arguments are invalid. Please call the tool again using valid JSON arguments. Error: " + e.getMessage());
                    fireOnMessageHistoryUpdated(messages);
                    currentMessages = messages;
                    userMessage = null;
                    continue;
                }

                if (decision instanceof FilterDecision.Handoff h) {
                    activeAgentId = null;
                    agent.onExit(SwarmContext.current());
                    fireOnExit(currentAgentId);
                    fireOnHandoff(currentAgentId, h.targetAgentId());
                    currentAgentId = h.targetAgentId();
                    Agent to = swarm.getAgent(currentAgentId);
                    to.onEnter(SwarmContext.current());
                    activeAgentId = currentAgentId;
                    fireOnEnter(currentAgentId, "HANDOFF");
                    String targetInstructions = to.instructions();
                    targetInstructions = SwarmContext.current().resolve(targetInstructions);
                    List<ChatMessage> preserved = new ArrayList<>(messages);
                    preserved.set(0, SystemMessage.from(targetInstructions));
                    currentMessages = preserved;
                    fireOnMessageHistoryUpdated(currentMessages);
                    continue;
                }

                if (decision instanceof FilterDecision.Delegate d) {
                    fireOnDelegateStart(currentAgentId, d.targetAgentId(), d.task());
                    String result;
                    try {
                        result = filter.executeDelegate(d.targetAgentId(), d.task(), swarmToolExecutor, options);
                    } catch (RuntimeException e) {
                        ensureCanRecover(recoveryAttempts);
                        recoveryAttempts++;
                        result = "Jswarm recovery: delegate to '" + d.targetAgentId()
                                + "' failed. Please handle the user request directly. Error: " + e.getMessage();
                        fireOnRecovery(currentAgentId, "Delegate failed: " + e.getMessage());
                    }
                    fireOnDelegateEnd(currentAgentId, d.targetAgentId());
                    messages.add(aiMessage);
                    messages.add(ToolExecutionResultMessage.from(toolCall, result));
                    fireOnMessageHistoryUpdated(messages);
                    currentMessages = messages;
                    userMessage = null;
                    continue;
                }

                fireOnToolCall(currentAgentId, toolCall.name(), toolCall.arguments());
                String result;
                try {
                    result = exec.execute(toolCall);
                } catch (RuntimeException e) {
                    ensureCanRecover(recoveryAttempts);
                    recoveryAttempts++;
                    result = "Jswarm recovery: tool '" + toolCall.name()
                            + "' failed. Please answer directly or try another available tool. Error: " + e.getMessage();
                    fireOnRecovery(currentAgentId, "Tool '" + toolCall.name() + "' failed: " + e.getMessage());
                }
                fireOnToolResult(currentAgentId, toolCall.name(), truncate(result, 200));
                messages.add(aiMessage);
                messages.add(ToolExecutionResultMessage.from(toolCall, result));
                fireOnMessageHistoryUpdated(messages);
                currentMessages = messages;
                userMessage = null;
            }

            throw new SwarmException("Max turns (" + options.maxTurns() + ") exceeded");
        } catch (RuntimeException e) {
            failure = e;
            fireOnRunFail(
                    activeAgentId != null ? activeAgentId : currentAgentId, e.getMessage());
            throw e;
        } finally {
            if (activeAgentId != null) {
                try {
                    swarm.getAgent(activeAgentId).onExit(SwarmContext.current());
                    fireOnExit(activeAgentId);
                } catch (RuntimeException hookEx) {
                    if (failure != null) {
                        failure.addSuppressed(hookEx);
                    } else {
                        throw hookEx;
                    }
                }
            }
        }
    }

    private void fireOnEnter(String agentId, String source) {
        if (listener != null) try { listener.onAgentEnter(agentId, source); } catch (RuntimeException ignored) {}
    }

    private void fireOnExit(String agentId) {
        if (listener != null) try { listener.onAgentExit(agentId); } catch (RuntimeException ignored) {}
    }

    private void fireOnToolCall(String agentId, String toolName, String args) {
        if (listener != null) try { listener.onToolCall(agentId, toolName, args); } catch (RuntimeException ignored) {}
    }

    private void fireOnToolResult(String agentId, String toolName, String result) {
        if (listener != null) try { listener.onToolResult(agentId, toolName, result); } catch (RuntimeException ignored) {}
    }

    private void fireOnHandoff(String from, String to) {
        if (listener != null) try { listener.onHandoff(from, to); } catch (RuntimeException ignored) {}
    }

    private void fireOnDelegateStart(String parent, String target, String task) {
        if (listener != null) try { listener.onDelegateStart(parent, target, task); } catch (RuntimeException ignored) {}
    }

    private void fireOnDelegateEnd(String parent, String target) {
        if (listener != null) try { listener.onDelegateEnd(parent, target); } catch (RuntimeException ignored) {}
    }

    private void fireOnRecovery(String agentId, String reason) {
        if (listener != null) try { listener.onRecovery(agentId, reason); } catch (RuntimeException ignored) {}
    }

    private void fireOnRunComplete(String finalText) {
        if (listener != null) try { listener.onRunComplete(finalText); } catch (RuntimeException ignored) {}
    }

    private void fireOnRunFail(String agentId, String error) {
        if (listener != null) try { listener.onRunFail(agentId, error); } catch (RuntimeException ignored) {}
    }

    private void fireOnMessageHistoryUpdated(List<ChatMessage> messages) {
        if (listener != null) try { listener.onMessageHistoryUpdated(messages); } catch (RuntimeException ignored) {}
    }

    public void runStreaming(String userMessage, SwarmContext context, Consumer<SwarmEvent> sink) {
        SwarmContext previous = SwarmContext.current();
        SwarmContext.set(context);
        try {
            runStreamingInternal(userMessage, sink);
        } finally {
            if (previous != null) {
                SwarmContext.set(previous);
            } else {
                SwarmContext.clear();
            }
        }
    }

    private void runStreamingInternal(String userMessage, Consumer<SwarmEvent> sink) {
        String currentAgentId = swarm.entryAgentId();
        List<ChatMessage> currentMessages = null;
        String activeAgentId = null;
        int recoveryAttempts = 0;
        SwarmContext ctx = SwarmContext.current();

        RuntimeException failure = null;
        try {
            Agent entry = swarm.getAgent(currentAgentId);
            requireJAgent(entry);
            entry.onEnter(ctx);
            activeAgentId = currentAgentId;
            sink.accept(new SwarmEvent.RunStarted("", currentAgentId));
            sink.accept(new SwarmEvent.AgentEnter(currentAgentId, "ENTRY"));

            for (int turn = 0; turn < options.maxTurns(); turn++) {
                Agent agent = swarm.getAgent(currentAgentId);
                JAgent runtimeAgent = requireJAgent(agent);

                List<ChatMessage> messages;
                if (currentMessages != null) {
                    messages = currentMessages;
                } else {
                    messages = new ArrayList<>();
                    String instructions = agent.instructions();
                    if (instructions == null) {
                        throw new SwarmException("Agent '" + currentAgentId + "' has no instructions configured");
                    }
                    messages.add(SystemMessage.from(ctx.resolve(instructions)));
                    messages.add(UserMessage.from(userMessage));
                }

                List<ToolSpecification> tools = SwarmToolInjector.generateTools(
                        swarm, currentAgentId, runtimeAgent.externalTools());
                ExternalToolExecutor exec = ToolExecutionMerger.merge(runtimeAgent.toolExecutor(), swarmToolExecutor);

                ChatRequest request = ChatRequest.builder()
                        .messages(messages)
                        .toolSpecifications(tools)
                        .build();

                AiMessage aiMessage = StreamingChatInvoker.stream(runtimeAgent, request, ctx,
                        options.modelTimeout(), sink);

                if (!aiMessage.hasToolExecutionRequests()) {
                    activeAgentId = null;
                    agent.onExit(ctx);
                    sink.accept(new SwarmEvent.AgentExit(currentAgentId));
                    sink.accept(new SwarmEvent.RunCompleted(aiMessage.text()));
                    return;
                }

                ToolExecutionRequest toolCall = aiMessage.toolExecutionRequests().get(0);

                FilterDecision decision;
                try {
                    decision = filter.decide(toolCall);
                } catch (SwarmException e) {
                    ensureCanRecover(recoveryAttempts);
                    recoveryAttempts++;
                    sink.accept(new SwarmEvent.RecoveryTriggered(currentAgentId,
                            "Invalid tool call arguments: " + e.getMessage()));
                    recoverToolCall(messages, aiMessage, toolCall,
                            "Jswarm recovery: tool call arguments are invalid. Please call the tool again using valid JSON arguments. Error: " + e.getMessage());
                    currentMessages = messages;
                    userMessage = null;
                    continue;
                }

                if (decision instanceof FilterDecision.Handoff h) {
                    activeAgentId = null;
                    agent.onExit(ctx);
                    sink.accept(new SwarmEvent.AgentExit(currentAgentId));
                    sink.accept(new SwarmEvent.Handoff(currentAgentId, h.targetAgentId()));
                    currentAgentId = h.targetAgentId();
                    Agent to = swarm.getAgent(currentAgentId);
                    to.onEnter(ctx);
                    activeAgentId = currentAgentId;
                    sink.accept(new SwarmEvent.AgentEnter(currentAgentId, "HANDOFF"));
                    String targetInstructions = ctx.resolve(to.instructions());
                    List<ChatMessage> preserved = new ArrayList<>(messages);
                    preserved.set(0, SystemMessage.from(targetInstructions));
                    currentMessages = preserved;
                    continue;
                }

                if (decision instanceof FilterDecision.Delegate d) {
                    sink.accept(new SwarmEvent.DelegateStarted(currentAgentId, d.targetAgentId(), d.task()));
                    String result;
                    try {
                        if (options.delegateStreaming()) {
                            result = filter.executeDelegateStreaming(
                                    d.targetAgentId(), d.task(), swarmToolExecutor, options, sink);
                        } else {
                            result = filter.executeDelegate(
                                    d.targetAgentId(), d.task(), swarmToolExecutor, options);
                        }
                    } catch (RuntimeException e) {
                        ensureCanRecover(recoveryAttempts);
                        recoveryAttempts++;
                        result = "Jswarm recovery: delegate to '" + d.targetAgentId()
                                + "' failed. Please handle the user request directly. Error: " + e.getMessage();
                        sink.accept(new SwarmEvent.RecoveryTriggered(currentAgentId,
                                "Delegate failed: " + e.getMessage()));
                    }
                    sink.accept(new SwarmEvent.DelegateFinished(currentAgentId, d.targetAgentId()));
                    messages.add(aiMessage);
                    messages.add(ToolExecutionResultMessage.from(toolCall, result));
                    currentMessages = messages;
                    userMessage = null;
                    continue;
                }

                sink.accept(new SwarmEvent.ToolCall(currentAgentId, toolCall.name(), toolCall.arguments()));
                String result;
                try {
                    result = exec.execute(toolCall);
                } catch (RuntimeException e) {
                    ensureCanRecover(recoveryAttempts);
                    recoveryAttempts++;
                    result = "Jswarm recovery: tool '" + toolCall.name()
                            + "' failed. Please answer directly or try another available tool. Error: " + e.getMessage();
                    sink.accept(new SwarmEvent.RecoveryTriggered(currentAgentId,
                            "External tool '" + toolCall.name() + "' failed: " + e.getMessage()));
                }
                sink.accept(new SwarmEvent.ToolResult(currentAgentId, toolCall.name(), truncate(result, 200)));
                messages.add(aiMessage);
                messages.add(ToolExecutionResultMessage.from(toolCall, result));
                currentMessages = messages;
                userMessage = null;
            }

            throw new SwarmException("Max turns (" + options.maxTurns() + ") exceeded");
        } catch (RuntimeException e) {
            failure = e;
            sink.accept(new SwarmEvent.RunFailed(
                    activeAgentId != null ? activeAgentId : currentAgentId, e.getMessage()));
            throw e;
        } finally {
            if (activeAgentId != null) {
                try {
                    swarm.getAgent(activeAgentId).onExit(ctx);
                    sink.accept(new SwarmEvent.AgentExit(activeAgentId));
                } catch (RuntimeException hookEx) {
                    if (failure != null) {
                        failure.addSuppressed(hookEx);
                    } else {
                        throw hookEx;
                    }
                }
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void ensureCanRecover(int recoveryAttempts) {
        if (recoveryAttempts >= options.maxRecoveryAttempts()) {
            throw new SwarmException("Recovery attempts exceeded: " + options.maxRecoveryAttempts());
        }
    }

    private void recoverToolCall(
            List<ChatMessage> messages,
            AiMessage aiMessage,
            ToolExecutionRequest toolCall,
            String result) {
        messages.add(aiMessage);
        messages.add(ToolExecutionResultMessage.from(toolCall, result));
    }

    private JAgent requireJAgent(Agent agent) {
        if (agent instanceof JAgent jAgent) {
            return jAgent;
        }
        throw new SwarmException("SwarmRunner requires JAgent, but got: " + agent.getClass().getName());
    }
}
