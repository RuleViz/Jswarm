// 多轮会话引擎，对齐 SwarmRunner 语义并采集轨迹
package com.jswarm.examples.showcase;

import com.jswarm.adapter.lc4j.ExternalToolExecutor;
import com.jswarm.adapter.lc4j.JAgent;
import com.jswarm.adapter.lc4j.filter.FilterDecision;
import com.jswarm.adapter.lc4j.filter.SwarmFilter;
import com.jswarm.adapter.lc4j.invoke.ChatInvoker;
import com.jswarm.adapter.lc4j.run.ChatMessageCodec;
import com.jswarm.adapter.lc4j.run.SwarmRunOptions;
import com.jswarm.adapter.lc4j.tool.SwarmToolInjector;
import com.jswarm.adapter.lc4j.tool.ToolExecutionMerger;
import com.jswarm.core.Agent;
import com.jswarm.core.Swarm;
import com.jswarm.core.SwarmContext;
import com.jswarm.core.SwarmException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class ShowcaseSessionEngine {

    private final Swarm swarm;
    private final SwarmFilter filter;
    private final ExternalToolExecutor swarmToolExecutor;
    private final SwarmRunOptions options;

    public ShowcaseSessionEngine(Swarm swarm, ExternalToolExecutor swarmToolExecutor) {
        this.swarm = swarm;
        this.filter = new SwarmFilter(swarm);
        this.swarmToolExecutor = swarmToolExecutor;
        this.options = SwarmRunOptions.builder().maxTurns(12).maxRecoveryAttempts(2).build();
    }

    public ChatResult chat(ShowcaseSession session, String userMessage) {
        ShowcaseEventCollector collector = ShowcaseEventCollector.start();
        SwarmContext previous = SwarmContext.current();
        SwarmContext.set(session.context());
        try {
            return chatInternal(session, userMessage, collector);
        } finally {
            if (previous != null) {
                SwarmContext.set(previous);
            } else {
                SwarmContext.clear();
            }
            ShowcaseEventCollector.clear();
        }
    }

    private ChatResult chatInternal(ShowcaseSession session, String userMessage, ShowcaseEventCollector collector) {
        List<ChatMessage> currentMessages = session.history().isEmpty()
                ? null
                : new ArrayList<>(session.history());
        if (currentMessages != null) {
            currentMessages = new ArrayList<>(ChatMessageCodec.forPersistence(currentMessages));
            currentMessages.add(UserMessage.from(userMessage));
        }
        int recoveryAttempts = 0;

        if (!session.entryHookFired()) {
            Agent entry = swarm.getAgent(session.currentAgentId());
            entry.onEnter(SwarmContext.current());
            session.setEntryHookFired(true);
        }

        for (int turn = 0; turn < options.maxTurns(); turn++) {
            Agent agent = swarm.getAgent(session.currentAgentId());
            JAgent runtimeAgent = requireJAgent(agent);

            List<ChatMessage> messages;
            if (currentMessages != null) {
                messages = currentMessages;
            } else {
                messages = new ArrayList<>();
                String instructions = agent.instructions();
                if (instructions == null) {
                    throw new SwarmException("Agent '" + session.currentAgentId() + "' has no instructions");
                }
                messages.add(SystemMessage.from(SwarmContext.current().resolve(instructions)));
                messages.add(UserMessage.from(userMessage));
            }

            List<ToolSpecification> tools = SwarmToolInjector.generateTools(
                    swarm, session.currentAgentId(), runtimeAgent.externalTools());
            ExternalToolExecutor exec = ToolExecutionMerger.merge(runtimeAgent.toolExecutor(), swarmToolExecutor);

            AiMessage aiMessage = ChatInvoker.invoke(runtimeAgent,
                    ChatRequest.builder().messages(messages).toolSpecifications(tools).build(),
                    options.modelTimeout());

            if (!aiMessage.hasToolExecutionRequests()) {
                Optional<String> forcedTarget = resolveForcedHandoff(session, userMessage);
                if (forcedTarget.isPresent()) {
                    currentMessages = applyHandoff(session, agent, messages, forcedTarget.get(), collector);
                    continue;
                }
                messages.add(aiMessage);
                session.setHistory(ChatMessageCodec.forPersistence(messages));
                return new ChatResult(aiMessage.text(), session.currentAgentId(),
                        collector.snapshotMaps(), contextSnapshot(session.context()));
            }

            ToolExecutionRequest toolCall = aiMessage.toolExecutionRequests().get(0);
            FilterDecision decision;
            try {
                decision = filter.decide(toolCall);
            } catch (SwarmException e) {
                ensureCanRecover(recoveryAttempts);
                recoveryAttempts++;
                messages.add(aiMessage);
                messages.add(ToolExecutionResultMessage.from(toolCall,
                        "Jswarm recovery: invalid tool args. Error: " + e.getMessage()));
                currentMessages = messages;
                continue;
            }

            if (decision instanceof FilterDecision.Handoff h) {
                currentMessages = applyHandoff(session, agent, messages, h.targetAgentId(), collector);
                continue;
            }

            if (decision instanceof FilterDecision.Delegate d) {
                collector.add(ShowcaseEvent.delegate(session.currentAgentId(), d.targetAgentId(), d.task()));
                String result;
                try {
                    result = filter.executeDelegate(d.targetAgentId(), d.task(), swarmToolExecutor, options);
                } catch (RuntimeException e) {
                    ensureCanRecover(recoveryAttempts);
                    recoveryAttempts++;
                    result = "Jswarm recovery: delegate failed. Error: " + e.getMessage();
                }
                messages.add(aiMessage);
                messages.add(ToolExecutionResultMessage.from(toolCall, result));
                currentMessages = messages;
                continue;
            }

            String result;
            try {
                result = exec.execute(toolCall);
                collector.add(ShowcaseEvent.tool(session.currentAgentId(), toolCall.name(), truncate(result, 80)));
            } catch (RuntimeException e) {
                ensureCanRecover(recoveryAttempts);
                recoveryAttempts++;
                result = "Jswarm recovery: tool failed. Error: " + e.getMessage();
            }
            messages.add(aiMessage);
            messages.add(ToolExecutionResultMessage.from(toolCall, result));
            currentMessages = messages;
        }

        throw new SwarmException("Max turns (" + options.maxTurns() + ") exceeded");
    }

    private Optional<String> resolveForcedHandoff(ShowcaseSession session, String userMessage) {
        if (!swarm.entryAgentId().equals(session.currentAgentId())) {
            return Optional.empty();
        }
        Optional<String> target = ShowcaseIntentRouter.handoffTarget(userMessage);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        return swarm.getHandoffTargets(session.currentAgentId()).contains(target.get())
                ? target
                : Optional.empty();
    }

    private List<ChatMessage> applyHandoff(
            ShowcaseSession session,
            Agent fromAgent,
            List<ChatMessage> messages,
            String targetAgentId,
            ShowcaseEventCollector collector) {
        String from = session.currentAgentId();
        fromAgent.onExit(SwarmContext.current());
        collector.add(ShowcaseEvent.onExit(from));
        session.setCurrentAgentId(targetAgentId);
        Agent to = swarm.getAgent(targetAgentId);
        to.onEnter(SwarmContext.current());
        collector.add(ShowcaseEvent.handoff(from, targetAgentId));
        String targetInstructions = SwarmContext.current().resolve(to.instructions());
        List<ChatMessage> preserved = new ArrayList<>(messages);
        preserved.set(0, SystemMessage.from(targetInstructions));
        return preserved;
    }

    private void ensureCanRecover(int recoveryAttempts) {
        if (recoveryAttempts >= options.maxRecoveryAttempts()) {
            throw new SwarmException("Recovery attempts exceeded: " + options.maxRecoveryAttempts());
        }
    }

    private static Map<String, Object> contextSnapshot(SwarmContext ctx) {
        Map<String, Object> snap = new LinkedHashMap<>();
        for (String key : List.of("user_id", "user_name", "vip_level", "total_spent",
                "session_id", "trace_id", "entered_at", "order_status")) {
            Object v = ctx.get(key);
            if (v != null) {
                snap.put(key, v);
            }
        }
        return snap;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private JAgent requireJAgent(Agent agent) {
        if (agent instanceof JAgent jAgent) {
            return jAgent;
        }
        throw new SwarmException("Showcase requires JAgent: " + agent.getClass().getName());
    }

    public record ChatResult(
            String reply,
            String currentAgent,
            List<Map<String, String>> events,
            Map<String, Object> context) {
    }
}
