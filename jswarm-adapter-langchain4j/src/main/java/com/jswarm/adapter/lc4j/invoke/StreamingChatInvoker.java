// 流式 LLM 调用点：StreamingChatModel 桥接 + 自动降级 + watchdog 超时
package com.jswarm.adapter.lc4j.invoke;

import com.jswarm.adapter.lc4j.JAgent;
import com.jswarm.core.SwarmContext;
import com.jswarm.core.SwarmEvent;
import com.jswarm.core.SwarmException;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class StreamingChatInvoker {

    private StreamingChatInvoker() {
    }

    public static AiMessage stream(JAgent agent, ChatRequest request, SwarmContext context,
                                   Duration timeout, Consumer<SwarmEvent> sink) {
        StreamingChatModel streamingModel = agent.streamingModel();
        if (streamingModel == null) {
            return fallbackSync(agent, request, context, timeout, sink);
        }

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AiMessage> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        SwarmContext captured = context;
        String agentId = agent.id();

        streamingModel.chat(request, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                SwarmContext earlier = SwarmContext.current();
                SwarmContext.set(captured);
                try {
                    sink.accept(new SwarmEvent.Token(agentId, partialResponse));
                } finally {
                    if (earlier != null) {
                        SwarmContext.set(earlier);
                    } else {
                        SwarmContext.clear();
                    }
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                SwarmContext earlier = SwarmContext.current();
                SwarmContext.set(captured);
                try {
                    resultRef.set(completeResponse.aiMessage());
                } finally {
                    if (earlier != null) {
                        SwarmContext.set(earlier);
                    } else {
                        SwarmContext.clear();
                    }
                    latch.countDown();
                }
            }

            @Override
            public void onError(Throwable error) {
                SwarmContext earlier = SwarmContext.current();
                SwarmContext.set(captured);
                try {
                    errorRef.set(error);
                } finally {
                    if (earlier != null) {
                        SwarmContext.set(earlier);
                    } else {
                        SwarmContext.clear();
                    }
                    latch.countDown();
                }
            }
        });

        try {
            boolean completed = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                throw new SwarmException("Streaming LLM call timed out after " + timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SwarmException("Streaming LLM call interrupted", e);
        }

        if (errorRef.get() != null) {
            Throwable error = errorRef.get();
            if (error instanceof RuntimeException re) {
                throw re;
            }
            throw new SwarmException("Streaming LLM call failed", error);
        }

        return resultRef.get();
    }

    private static AiMessage fallbackSync(JAgent agent, ChatRequest request, SwarmContext context,
                                          Duration timeout, Consumer<SwarmEvent> sink) {
        SwarmContext previous = SwarmContext.current();
        SwarmContext.set(context);
        try {
            System.Logger logger = System.getLogger(StreamingChatInvoker.class.getName());
            logger.log(System.Logger.Level.WARNING,
                    "Agent '" + agent.id() + "' has no StreamingChatModel configured; falling back to synchronous ChatModel");
            AiMessage result = ChatInvoker.invoke(agent, request, timeout);
            String text = result.text();
            if (text != null) {
                sink.accept(new SwarmEvent.Token(agent.id(), text));
            }
            return result;
        } finally {
            if (previous != null) {
                SwarmContext.set(previous);
            } else {
                SwarmContext.clear();
            }
        }
    }
}
