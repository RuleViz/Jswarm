package com.jswarm.adapter.lc4j.run;

import com.jswarm.adapter.lc4j.JAgent;
import com.jswarm.core.Swarm;
import com.jswarm.core.SwarmContext;
import com.jswarm.core.SwarmEvent;
import com.jswarm.core.SwarmException;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class SwarmStreamingTest {

    @AfterEach
    void tearDown() {
        SwarmContext.clear();
    }

    @Test
    void shouldEmitEventSequenceForSimpleReply() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent a = streamingAgent("a", "instructions", stubStreamingModel("hello"));

        Swarm swarm = Swarm.create("test").agent(a).entry("a").build();
        SwarmRunner runner = SwarmRunner.create(swarm);
        runner.runStreaming("hi", new SwarmContext(), events::add);

        assertEquals(5, events.size());
        assertInstanceOf(SwarmEvent.RunStarted.class, events.get(0));
        assertEquals("a", ((SwarmEvent.RunStarted) events.get(0)).entryAgentId());
        assertInstanceOf(SwarmEvent.AgentEnter.class, events.get(1));
        assertEquals("a", ((SwarmEvent.AgentEnter) events.get(1)).agentId());
        assertEquals("ENTRY", ((SwarmEvent.AgentEnter) events.get(1)).source());
        assertInstanceOf(SwarmEvent.Token.class, events.get(2));
        assertEquals("hello", ((SwarmEvent.Token) events.get(2)).text());
        assertInstanceOf(SwarmEvent.AgentExit.class, events.get(3));
        assertEquals("a", ((SwarmEvent.AgentExit) events.get(3)).agentId());
        assertInstanceOf(SwarmEvent.RunCompleted.class, events.get(4));
        assertEquals("hello", ((SwarmEvent.RunCompleted) events.get(4)).finalText());
    }

    @Test
    void shouldStreamTokensInOrder() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent a = streamingAgent("a", "instructions", tokenStreamModel("one", " two", " three"));

        Swarm swarm = Swarm.create("test").agent(a).entry("a").build();
        SwarmRunner runner = SwarmRunner.create(swarm);
        runner.runStreaming("hi", new SwarmContext(), events::add);

        List<SwarmEvent> tokens = events.stream()
                .filter(e -> e instanceof SwarmEvent.Token).toList();
        assertEquals(3, tokens.size());
        assertEquals("one", ((SwarmEvent.Token) tokens.get(0)).text());
        assertEquals(" two", ((SwarmEvent.Token) tokens.get(1)).text());
        assertEquals(" three", ((SwarmEvent.Token) tokens.get(2)).text());
    }

    @Test
    void shouldHandleExternalToolCall() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent a = JAgent.builder("a", "agent-a")
                .description("test")
                .instructions("instructions")
                .model(syncStub(""))
                .streamingModel(streamingToolCallModel("getWeather", "{\"city\": \"beijing\"}", "sunny"))
                .tools(new WeatherTool())
                .build();

        Swarm swarm = Swarm.create("test").agent(a).entry("a").build();
        SwarmRunner runner = SwarmRunner.create(swarm);
        runner.runStreaming("weather", new SwarmContext(), events::add);

        assertTrue(events.stream().anyMatch(e -> e instanceof SwarmEvent.ToolCall));
        assertTrue(events.stream().anyMatch(e -> e instanceof SwarmEvent.ToolResult));
        SwarmEvent.ToolCall tc = (SwarmEvent.ToolCall) events.stream()
                .filter(e -> e instanceof SwarmEvent.ToolCall).findFirst().get();
        assertEquals("getWeather", tc.toolName());
        SwarmEvent.RunCompleted rc = (SwarmEvent.RunCompleted) events.stream()
                .filter(e -> e instanceof SwarmEvent.RunCompleted).findFirst().get();
        assertTrue(rc.finalText().contains("sunny"));
    }

    @Test
    void shouldEmitHandoffEvents() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent a = streamingAgent("a", "router instructions", streamingHandoffModel("b"));
        JAgent b = streamingAgent("b", "target instructions", stubStreamingModel("done"));

        Swarm swarm = Swarm.create("test")
                .agent(a).agent(b)
                .entry("a")
                .handoff("a", "b")
                .build();
        SwarmRunner runner = SwarmRunner.create(swarm);
        runner.runStreaming("help", new SwarmContext(), events::add);

        assertTrue(events.stream().anyMatch(e -> e instanceof SwarmEvent.Handoff));
        SwarmEvent.Handoff h = (SwarmEvent.Handoff) events.stream()
                .filter(e -> e instanceof SwarmEvent.Handoff).findFirst().get();
        assertEquals("a", h.from());
        assertEquals("b", h.to());
        assertTrue(events.stream().anyMatch(e -> e instanceof SwarmEvent.AgentExit
                && "a".equals(((SwarmEvent.AgentExit) e).agentId())));
        assertTrue(events.stream().anyMatch(e -> e instanceof SwarmEvent.AgentEnter
                && "b".equals(((SwarmEvent.AgentEnter) e).agentId())));
    }

    @Test
    void shouldEmitDelegateEvents() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent a = streamingAgent("a", "router", twoPhaseModel(
                streamingDelegateModel("b", "analyze sales"),
                stubStreamingModel("summary done")));

        Swarm swarm = Swarm.create("test")
                .agent(a)
                .entry("a")
                .delegate("a", "b")
                .agent(streamingB())
                .build();
        SwarmRunner runner = SwarmRunner.create(swarm);
        runner.runStreaming("analyze", new SwarmContext(), events::add);

        assertTrue(events.stream().anyMatch(e -> e instanceof SwarmEvent.DelegateStarted));
        assertTrue(events.stream().anyMatch(e -> e instanceof SwarmEvent.DelegateFinished));
        SwarmEvent.DelegateStarted ds = (SwarmEvent.DelegateStarted) events.stream()
                .filter(e -> e instanceof SwarmEvent.DelegateStarted).findFirst().get();
        assertEquals("a", ds.parent());
        assertEquals("b", ds.delegateAgent());
        SwarmEvent.RunCompleted rc = (SwarmEvent.RunCompleted) events.stream()
                .filter(e -> e instanceof SwarmEvent.RunCompleted).findFirst().get();
        assertTrue(rc.finalText().contains("summary done"));
    }

    @Test
    void delegateStreamingShouldPassThroughSubAgentTokens() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent a = streamingAgent("a", "router", twoPhaseModel(
                streamingDelegateModel("b", "analyze"),
                stubStreamingModel("done")));
        JAgent b = JAgent.builder("b", "analyst")
                .description("analyst")
                .instructions("analyze")
                .model(syncStub(""))
                .streamingModel(tokenStreamModel("analyzing ", "data..."))
                .build();

        Swarm swarm = Swarm.create("test")
                .agent(a).agent(b)
                .entry("a")
                .delegate("a", "b")
                .build();
        SwarmRunner runner = SwarmRunner.create(swarm,
                SwarmRunOptions.builder().delegateStreaming(true).build());
        runner.runStreaming("go", new SwarmContext(), events::add);

        List<SwarmEvent.Token> tokens = events.stream()
                .filter(e -> e instanceof SwarmEvent.Token)
                .map(e -> (SwarmEvent.Token) e)
                .toList();
        assertTrue(tokens.size() >= 2, "should have at least 2 token events from sub-agent");
        assertTrue(tokens.stream().anyMatch(t -> "b".equals(t.agentId())),
                "sub-agent tokens should have agentId 'b'");
        assertTrue(tokens.stream().anyMatch(t -> t.text().contains("analyzing")),
                "sub-agent token text should appear in events");
    }

    @Test
    void delegateStreamingDisabledShouldNotPassThroughTokens() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent a = streamingAgent("a", "router", twoPhaseModel(
                streamingDelegateModel("b", "analyze"),
                stubStreamingModel("done")));
        JAgent b = JAgent.builder("b", "analyst")
                .description("analyst")
                .instructions("analyze")
                .model(syncStub(""))
                .streamingModel(tokenStreamModel("analyzing ", "data..."))
                .build();

        Swarm swarm = Swarm.create("test")
                .agent(a).agent(b)
                .entry("a")
                .delegate("a", "b")
                .build();
        SwarmRunner runner = SwarmRunner.create(swarm,
                SwarmRunOptions.builder().delegateStreaming(false).build());
        runner.runStreaming("go", new SwarmContext(), events::add);

        List<SwarmEvent.Token> tokens = events.stream()
                .filter(e -> e instanceof SwarmEvent.Token)
                .map(e -> (SwarmEvent.Token) e)
                .filter(t -> "b".equals(t.agentId()))
                .toList();
        assertTrue(tokens.isEmpty(), "should not have sub-agent tokens when delegateStreaming=false");
    }

    @Test
    void shouldMatchSyncResult() {
        ChatModel syncModel = syncStub("sync result");
        JAgent syncAgent = JAgent.builder("a", "agent")
                .description("test")
                .instructions("instructions")
                .model(syncModel)
                .build();

        Swarm swarm = Swarm.create("test").agent(syncAgent).entry("a").build();
        SwarmRunner runner = SwarmRunner.create(swarm);
        String syncResult = runner.run("hi");

        List<SwarmEvent> events = new ArrayList<>();
        SwarmContext ctx = new SwarmContext();
        runner.runStreaming("hi", ctx, events::add);

        SwarmEvent.RunCompleted rc = (SwarmEvent.RunCompleted) events.stream()
                .filter(e -> e instanceof SwarmEvent.RunCompleted).findFirst().get();
        assertEquals(syncResult, rc.finalText());
    }

    @Test
    void shouldTriggerOnEnterAndOnExit() {
        AtomicReference<String> entered = new AtomicReference<>();
        AtomicReference<String> exited = new AtomicReference<>();

        JAgent a = new com.jswarm.adapter.lc4j.DefaultJAgent(
                "a", "agent-a", "test", "instructions",
                syncStub("ok"),
                java.util.List.of(), null) {
            @Override
            public StreamingChatModel streamingModel() {
                return stubStreamingModel("ok");
            }

            @Override
            public void onEnter(SwarmContext context) {
                entered.set("entered");
            }

            @Override
            public void onExit(SwarmContext context) {
                exited.set("exited");
            }
        };

        Swarm swarm = Swarm.create("test").agent(a).entry("a").build();
        SwarmRunner runner = SwarmRunner.create(swarm);
        runner.runStreaming("hi", new SwarmContext(), e -> {});

        assertEquals("entered", entered.get());
        assertEquals("exited", exited.get());
    }

    @Test
    void shouldNotThrowWhenNoStreamingModelConfigured() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent a = JAgent.builder("a", "agent")
                .description("test")
                .instructions("instructions")
                .model(syncStub("fallback ok"))
                .build();

        Swarm swarm = Swarm.create("test").agent(a).entry("a").build();
        SwarmRunner runner = SwarmRunner.create(swarm);
        assertDoesNotThrow(() -> runner.runStreaming("hi", new SwarmContext(), events::add));

        SwarmEvent.RunCompleted rc = (SwarmEvent.RunCompleted) events.stream()
                .filter(e -> e instanceof SwarmEvent.RunCompleted).findFirst().get();
        assertEquals("fallback ok", rc.finalText());
    }

    @Test
    void shouldEmitRunFailedOnMaxTurns() {
        List<SwarmEvent> events = new ArrayList<>();
        StreamingChatModel looping = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("loop");
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                                .name("handoff")
                                .arguments("{\"target\": \"nonexistent\"}")
                                .build()))
                        .build());
            }
        };
        JAgent a = streamingAgent("a", "instructions", looping);

        Swarm swarm = Swarm.create("test").agent(a).entry("a").build();
        SwarmRunner runner = SwarmRunner.create(swarm, 1);
        assertThrows(SwarmException.class,
                () -> runner.runStreaming("hi", new SwarmContext(), events::add));

        assertTrue(events.stream().anyMatch(e -> e instanceof SwarmEvent.RunFailed));
    }

    static class WeatherTool {
        @Tool(name = "getWeather", value = "get weather by city")
        String getWeather(String city) {
            return "sunny in " + city;
        }
    }

    private static JAgent streamingAgent(String id, String instructions, StreamingChatModel streamingModel) {
        return JAgent.builder(id, id)
                .description("test " + id)
                .instructions(instructions)
                .model(syncStub(""))
                .streamingModel(streamingModel)
                .build();
    }

    private static StreamingChatModel stubStreamingModel(String text) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse(text);
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(text)).build());
            }
        };
    }

    private static StreamingChatModel tokenStreamModel(String... tokens) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                StringBuilder full = new StringBuilder();
                for (String t : tokens) {
                    handler.onPartialResponse(t);
                    full.append(t);
                }
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(full.toString())).build());
            }
        };
    }

    private static StreamingChatModel streamingHandoffModel(String target) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                ToolExecutionRequest tc = ToolExecutionRequest.builder()
                        .name("handoff")
                        .arguments("{\"target\": \"" + target + "\"}")
                        .build();
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(tc)).build());
            }
        };
    }

    private static StreamingChatModel streamingDelegateModel(String target, String task) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                ToolExecutionRequest tc = ToolExecutionRequest.builder()
                        .name("delegate")
                        .arguments("{\"target\": \"" + target + "\", \"task\": \"" + task + "\"}")
                        .build();
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from(tc)).build());
            }
        };
    }

    private static StreamingChatModel streamingToolCallModel(String toolName, String args, String toolResult) {
        return new StreamingChatModel() {
            int callCount = 0;
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                if (callCount++ == 0) {
                    ToolExecutionRequest tc = ToolExecutionRequest.builder()
                            .name(toolName)
                            .arguments(args)
                            .build();
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from(tc)).build());
                } else {
                    handler.onPartialResponse(toolResult);
                    handler.onCompleteResponse(ChatResponse.builder()
                            .aiMessage(AiMessage.from(toolResult)).build());
                }
            }
        };
    }

    private static StreamingChatModel twoPhaseModel(
            StreamingChatModel first, StreamingChatModel second) {
        return new StreamingChatModel() {
            int calls = 0;
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                (calls++ == 0 ? first : second).chat(request, handler);
            }
        };
    }

    private static JAgent streamingB() {
        return JAgent.builder("b", "analyst")
                .description("analyst agent")
                .instructions("analyst instructions")
                .model(new ChatModel() {
                    @Override
                    public ChatResponse chat(ChatRequest req) {
                        return ChatResponse.builder()
                                .aiMessage(AiMessage.from("analysis done")).build();
                    }
                })
                .build();
    }

    private static ChatModel syncStub(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest req) {
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        };
    }
}
