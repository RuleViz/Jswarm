// Showcase HTTP 服务与 REST API
package com.jswarm.examples.showcase;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jswarm.adapter.lc4j.run.SwarmRunner;
import com.jswarm.core.Swarm;
import com.jswarm.core.SwarmContext;
import com.jswarm.core.SwarmEvent;
import com.jswarm.core.SwarmException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;

public final class ShowcaseHttpServer {

    private static final int PORT = 8080;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Swarm swarm;
    private final ShowcaseSwarmFactory.BuildResult buildResult;
    private final ShowcaseSessionEngine engine;
    private final SwarmRunner runner;
    private final ShowcaseSessionStore sessionStore;

    public ShowcaseHttpServer(ShowcaseSwarmFactory.BuildResult buildResult, ShowcaseSessionStore sessionStore) {
        this.buildResult = buildResult;
        this.swarm = buildResult.swarm();
        this.engine = new ShowcaseSessionEngine(swarm, buildResult.swarmToolExecutor());
        this.runner = SwarmRunner.create(swarm, 12, buildResult.swarmToolExecutor());
        this.sessionStore = sessionStore;
    }

    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);
        server.createContext("/", this::handleStatic);
        server.createContext("/api/chat", this::handleChat);
        server.createContext("/api/chat/stream", this::handleChatStream);
        server.createContext("/api/reset", this::handleReset);
        server.createContext("/api/features", this::handleFeatures);
        server.createContext("/api/scenario/", this::handleScenario);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        System.out.println("Jswarm Showcase: http://localhost:" + PORT);
        System.out.println("需要环境变量 DEEPSEEK_API_KEY");
    }

    private void handleStatic(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/".equals(path)) {
            path = "/web/showcase/index.html";
        }
        String resourcePath = path.startsWith("/") ? path.substring(1) : path;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            byte[] bytes = in.readAllBytes();
            String contentType = resourcePath.endsWith(".html") ? "text/html; charset=utf-8"
                    : resourcePath.endsWith(".css") ? "text/css; charset=utf-8"
                    : resourcePath.endsWith(".js") ? "application/javascript; charset=utf-8"
                    : "text/plain";
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }

    private void handleChat(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> req = MAPPER.readValue(exchange.getRequestBody(), Map.class);
        String sessionId = req.getOrDefault("sessionId", UUID.randomUUID().toString());
        String userId = req.getOrDefault("userId", "u001");
        String message = req.get("message");
        if (message == null || message.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "message is required"));
            return;
        }

        ShowcaseSession session = loadOrCreateSession(sessionId, userId);
        if (!userId.equals(session.context().get("user_id", String.class))) {
            session.resetContext(ShowcaseSwarmFactory.buildUserContext(
                    buildResult.userProfileRepository(), userId, sessionId));
            session.setCurrentAgentId(swarm.entryAgentId());
            session.setEntryHookFired(false);
        }

        try {
            ShowcaseSessionEngine.ChatResult result = engine.chat(session, message);
            sessionStore.save(session);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("sessionId", sessionId);
            body.put("reply", result.reply());
            body.put("currentAgent", result.currentAgent());
            body.put("events", result.events());
            body.put("context", result.context());
            sendJson(exchange, 200, body);
        } catch (SwarmException e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            sendJson(exchange, 500, Map.of("error", "Internal error: " + e.getMessage()));
        }
    }

    private void handleChatStream(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> req = MAPPER.readValue(exchange.getRequestBody(), Map.class);
        String sessionId = req.getOrDefault("sessionId", UUID.randomUUID().toString());
        String userId = req.getOrDefault("userId", "u001");
        String message = req.get("message");
        if (message == null || message.isBlank()) {
            sendJson(exchange, 400, Map.of("error", "message is required"));
            return;
        }

        ShowcaseSession session = loadOrCreateSession(sessionId, userId);
        if (!userId.equals(session.context().get("user_id", String.class))) {
            session.resetContext(ShowcaseSwarmFactory.buildUserContext(
                    buildResult.userProfileRepository(), userId, sessionId));
            session.setCurrentAgentId(swarm.entryAgentId());
            session.setEntryHookFired(false);
        }

        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream os = exchange.getResponseBody()) {
            try {
                runner.runStreaming(message, session.context(), event -> {
                    try {
                        String eventType = event.getClass().getSimpleName();
                        String json = MAPPER.writeValueAsString(event);
                        byte[] sseBytes = ("event: " + eventType + "\ndata: " + json + "\n\n")
                                .getBytes(StandardCharsets.UTF_8);
                        os.write(sseBytes);
                        os.flush();
                    } catch (IOException e) {
                        throw new RuntimeException("SSE write failed", e);
                    }
                });
                sessionStore.save(session);
            } catch (SwarmException e) {
                String errJson = MAPPER.writeValueAsString(Map.of("error", e.getMessage()));
                byte[] sseBytes = ("event: RunFailed\ndata: " + errJson + "\n\n")
                        .getBytes(StandardCharsets.UTF_8);
                os.write(sseBytes);
                os.flush();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void handleReset(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, String> req = MAPPER.readValue(exchange.getRequestBody(), Map.class);
        String sessionId = req.get("sessionId");
        String userId = req.getOrDefault("userId", "u001");
        if (sessionId != null) {
            sessionStore.delete(sessionId);
        }
        String newSessionId = UUID.randomUUID().toString();
        sendJson(exchange, 200, Map.of("ok", true, "sessionId", newSessionId));
    }

    private ShowcaseSession loadOrCreateSession(String sessionId, String userId) {
        return sessionStore.load(sessionId, swarm.entryAgentId())
                .orElseGet(() -> newSession(sessionId, userId));
    }

    private void handleFeatures(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        sendJson(exchange, 200, Map.of("features", ShowcaseSwarmFactory.featureCatalog()));
    }

    private void handleScenario(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            return;
        }
        String path = exchange.getRequestURI().getPath();
        String scenarioId = path.substring("/api/scenario/".length());
        @SuppressWarnings("unchecked")
        Map<String, String> req = MAPPER.readValue(exchange.getRequestBody(), Map.class);
        String userId = req.getOrDefault("userId", "u001");
        String sessionId = UUID.randomUUID().toString();
        SwarmContext ctx = ShowcaseSwarmFactory.buildUserContext(
                buildResult.userProfileRepository(), userId, sessionId);

        String message = switch (scenarioId) {
            case "tech" -> "我的软件激活码一直提示无效，请帮我排查";
            case "order" -> "帮我查一下订单 ORD-10086 的物流状态";
            case "sales" -> "旗舰版多少钱？有什么优惠？";
            case "delegate" -> "请帮我分析一下最近订单消费情况，给老板汇报";
            default -> throw new SwarmException("Unknown scenario: " + scenarioId);
        };

        ShowcaseEventCollector collector = ShowcaseEventCollector.start();
        try {
            String reply = runner.run(message, ctx);
            sendJson(exchange, 200, Map.of(
                    "scenario", scenarioId,
                    "reply", reply,
                    "events", collector.snapshotMaps(),
                    "context", snapshotContext(ctx)));
        } catch (SwarmException e) {
            sendJson(exchange, 500, Map.of("error", e.getMessage()));
        } finally {
            ShowcaseEventCollector.clear();
        }
    }

    private ShowcaseSession newSession(String sessionId, String userId) {
        SwarmContext ctx = ShowcaseSwarmFactory.buildUserContext(
                buildResult.userProfileRepository(), userId, sessionId);
        return new ShowcaseSession(sessionId, swarm.entryAgentId(), ctx);
    }

    private static Map<String, Object> snapshotContext(SwarmContext ctx) {
        Map<String, Object> snap = new LinkedHashMap<>();
        for (String key : new String[]{"user_name", "vip_level", "total_spent", "trace_id", "order_status"}) {
            Object v = ctx.get(key);
            if (v != null) {
                snap.put(key, v);
            }
        }
        return snap;
    }

    private void sendJson(HttpExchange exchange, int status, Object data) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(data);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, no-cache, must-revalidate");
        exchange.getResponseHeaders().set("Pragma", "no-cache");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
