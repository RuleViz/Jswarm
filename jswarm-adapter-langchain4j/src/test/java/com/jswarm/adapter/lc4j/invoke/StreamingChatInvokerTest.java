package com.jswarm.adapter.lc4j.invoke;

import com.jswarm.adapter.lc4j.JAgent;
import com.jswarm.core.SwarmContext;
import com.jswarm.core.SwarmEvent;
import com.jswarm.core.SwarmException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class StreamingChatInvokerTest {

    private static final ChatRequest DUMMY_REQUEST = ChatRequest.builder()
            .messages(List.of(UserMessage.from("hi")))
            .build();

    @AfterEach
    void tearDown() {
        SwarmContext.clear();
    }

    @Test
    void shouldStreamTokensInOrder() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent agent = streamingAgent("a1", "Hello", " World", "!");

        SwarmContext ctx = new SwarmContext();
        AiMessage result = StreamingChatInvoker.stream(agent, DUMMY_REQUEST,
                ctx, Duration.ofSeconds(5), events::add);

        assertEquals("Hello World!", result.text());
        assertEquals(3, events.size());
        assertInstanceOf(SwarmEvent.Token.class, events.get(0));
        assertEquals("Hello", ((SwarmEvent.Token) events.get(0)).text());
        assertEquals(" World", ((SwarmEvent.Token) events.get(1)).text());
        assertEquals("!", ((SwarmEvent.Token) events.get(2)).text());
    }

    @Test
    void shouldFallbackToSyncWhenNoStreamingModel() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent agent = JAgent.builder("a", "sync-agent")
                .description("test")
                .instructions("instructions")
                .model(syncStub("sync result"))
                .build();

        SwarmContext ctx = new SwarmContext();
        AiMessage result = StreamingChatInvoker.stream(agent, DUMMY_REQUEST,
                ctx, Duration.ofSeconds(5), events::add);

        assertEquals("sync result", result.text());
        assertEquals(1, events.size());
        assertInstanceOf(SwarmEvent.Token.class, events.get(0));
        assertEquals("sync result", ((SwarmEvent.Token) events.get(0)).text());
    }

    @Test
    void shouldTimeoutWhenModelHangs() {
        List<SwarmEvent> events = new ArrayList<>();
        StreamingChatModel hanging = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
            }
        };
        JAgent agent = streamingAgent("a", hanging);

        SwarmContext ctx = new SwarmContext();
        assertThrows(SwarmException.class, () ->
                StreamingChatInvoker.stream(agent, DUMMY_REQUEST,
                        ctx, Duration.ofMillis(100), events::add));
    }

    @Test
    void shouldPropagateModelError() {
        List<SwarmEvent> events = new ArrayList<>();
        StreamingChatModel errorModel = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onError(new RuntimeException("model exploded"));
            }
        };
        JAgent agent = streamingAgent("a", errorModel);

        SwarmContext ctx = new SwarmContext();
        RuntimeException ex = assertThrows(RuntimeException.class, () ->
                StreamingChatInvoker.stream(agent, DUMMY_REQUEST,
                        ctx, Duration.ofSeconds(5), events::add));
        assertTrue(ex.getMessage().contains("model exploded"));
    }

    @Test
    void shouldSetSwarmContextInCallbackThread() {
        List<SwarmEvent> events = new ArrayList<>();
        AtomicReference<SwarmContext> capturedCtx = new AtomicReference<>();

        StreamingChatModel capturing = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                capturedCtx.set(SwarmContext.current());
                handler.onPartialResponse("ok");
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("ok")).build());
            }
        };
        JAgent agent = streamingAgent("a", capturing);

        SwarmContext ctx = new SwarmContext();
        ctx.put("key", "value");
        SwarmContext.set(ctx);
        StreamingChatInvoker.stream(agent, DUMMY_REQUEST,
                ctx, Duration.ofSeconds(5), events::add);

        assertEquals("value", capturedCtx.get().get("key"));
    }

    @Test
    void shouldStreamWithContextAndResolve() {
        List<SwarmEvent> events = new ArrayList<>();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("Hello ");
                handler.onPartialResponse("user!");
                handler.onCompleteResponse(ChatResponse.builder()
                        .aiMessage(AiMessage.from("Hello user!")).build());
            }
        };
        JAgent agent = streamingAgent("a", model);

        SwarmContext ctx = new SwarmContext();
        ctx.put("user", "test");
        SwarmContext.set(ctx);

        AiMessage result = StreamingChatInvoker.stream(agent, DUMMY_REQUEST,
                ctx, Duration.ofSeconds(5), events::add);

        assertEquals("Hello user!", result.text());
        assertEquals(2, events.size());
        for (var e : events) {
            assertInstanceOf(SwarmEvent.Token.class, e);
            assertEquals("a", ((SwarmEvent.Token) e).agentId());
        }
    }

    @Test
    void shouldAllTokensHaveSameAgentId() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent agent = streamingAgent("tech-agent", "token1", "token2");

        SwarmContext ctx = new SwarmContext();
        StreamingChatInvoker.stream(agent, DUMMY_REQUEST,
                ctx, Duration.ofSeconds(5), events::add);

        for (var e : events) {
            assertEquals("tech-agent", ((SwarmEvent.Token) e).agentId());
        }
    }

    @Test
    void fallbackShouldPreserveSwarmContext() {
        AtomicReference<SwarmContext> captured = new AtomicReference<>();
        ChatModel capturingModel = new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest req) {
                captured.set(SwarmContext.current());
                return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
            }
        };
        JAgent agent = JAgent.builder("a", "sync-agent")
                .description("test")
                .instructions("instructions")
                .model(capturingModel)
                .build();

        List<SwarmEvent> events = new ArrayList<>();
        SwarmContext ctx = new SwarmContext();
        ctx.put("x", "y");
        StreamingChatInvoker.stream(agent, DUMMY_REQUEST,
                ctx, Duration.ofSeconds(5), events::add);

        assertEquals("y", captured.get().get("x"));
    }

    @Test
    void fallbackShouldEmitRunCompletedViaToken() {
        List<SwarmEvent> events = new ArrayList<>();
        JAgent agent = JAgent.builder("a", "agent")
                .description("test")
                .instructions("hi")
                .model(syncStub("final answer"))
                .build();

        SwarmContext ctx = new SwarmContext();
        AiMessage result = StreamingChatInvoker.stream(agent, DUMMY_REQUEST,
                ctx, Duration.ofSeconds(5), events::add);

        assertEquals("final answer", result.text());
        assertEquals(1, events.size());
        assertEquals("final answer", ((SwarmEvent.Token) events.get(0)).text());
    }

    private static JAgent streamingAgent(String id, StreamingChatModel streamingModel) {
        return JAgent.builder(id, id)
                .description("test " + id)
                .instructions("instructions")
                .model(syncStub(""))
                .streamingModel(streamingModel)
                .build();
    }

    private static JAgent streamingAgent(String id, String... tokens) {
        return streamingAgent(id, stubStreaming(tokens));
    }

    private static StreamingChatModel stubStreaming(String... tokens) {
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

    private static ChatModel syncStub(String text) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest req) {
                return ChatResponse.builder().aiMessage(AiMessage.from(text)).build();
            }
        };
    }
}
