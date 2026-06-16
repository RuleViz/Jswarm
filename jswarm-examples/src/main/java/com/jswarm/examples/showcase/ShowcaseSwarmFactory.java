// 构建 Showcase 全能力 Swarm 与 Swarm 级工具
package com.jswarm.examples.showcase;

import com.jswarm.adapter.lc4j.ExternalToolExecutor;
import com.jswarm.adapter.lc4j.JAgent;
import com.jswarm.adapter.lc4j.tool.Lc4jAgentExtractor;
import com.jswarm.core.Swarm;
import com.jswarm.core.SwarmContext;
import com.jswarm.examples.lc4jlegacy.OrderAssistant;
import com.jswarm.examples.lc4jlegacy.OrderRepository;
import com.jswarm.examples.lc4jlegacy.OrderServiceTools;
import com.jswarm.examples.lc4jlegacy.RouterAssistant;
import com.jswarm.examples.lc4jlegacy.UserProfileRepository;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ShowcaseSwarmFactory {

    public record BuildResult(
            Swarm swarm,
            ExternalToolExecutor swarmToolExecutor,
            OrderRepository orderRepository,
            UserProfileRepository userProfileRepository) {
    }

    private ShowcaseSwarmFactory() {
    }

    public static BuildResult build(ChatModel model) {
        return build(model, null);
    }

    public static BuildResult build(ChatModel model, StreamingChatModel streamingModel) {
        OrderRepository orderRepository = new OrderRepository();
        UserProfileRepository userProfileRepository = new UserProfileRepository();
        OrderServiceTools orderTools = new OrderServiceTools(orderRepository);
        ShowcaseAuditTools auditTools = new ShowcaseAuditTools();

        String routerInstructions = Lc4jAgentExtractor.extractInstructions(RouterAssistant.class);
        JAgent routerBase = JAgent.builder("router", "路由专员")
                .description("分析意图并分发")
                .instructions(routerInstructions)
                .model(model)
                .streamingModel(streamingModel)
                .tools(auditTools)
                .build();

        JAgent router = JAgent.decorate(routerBase)
                .onEnter(ctx -> {
                    String traceId = UUID.randomUUID().toString().substring(0, 8);
                    ctx.put("trace_id", traceId);
                    ShowcaseEventCollector c = ShowcaseEventCollector.current();
                    if (c != null) {
                        c.add(ShowcaseEvent.onEnter("router", "trace_id=" + traceId));
                        c.add(ShowcaseEvent.context("trace_id", traceId));
                    }
                })
                .build();

        JAgent tech = JAgent.builder("tech", "技术支持专员")
                .description("解决软件激活、安装与故障问题")
                .instructions("你是技术支持专员。当前用户：{user_name}（{vip_level}）。" +
                        "VIP 用户给出更详尽的排查步骤。结合对话历史回答。")
                .model(model)
                .streamingModel(streamingModel)
                .onEnter(ctx -> {
                    ctx.put("entered_at", Instant.now().toString());
                    ShowcaseEventCollector c = ShowcaseEventCollector.current();
                    if (c != null) {
                        c.add(ShowcaseEvent.onEnter("tech", "entered_at set"));
                    }
                })
                .build();

        JAgent sales = JAgent.builder("sales", "销售专员")
                .description("产品咨询、报价与购买")
                .instructions(ctx -> {
                    int spent = ctx.get("total_spent", Integer.class) != null
                            ? ctx.get("total_spent", Integer.class) : 0;
                    String tier = spent >= 10000 ? "高价值客户，推荐旗舰版与专属优惠"
                            : spent >= 1000 ? "活跃客户，推荐进阶版与组合套餐"
                            : "新客/低消费，推荐入门版与限时活动";
                    return "你是销售专员。当前用户：{user_name}（{vip_level}），历史消费 {total_spent} 元。" +
                            tier + "。热情解答价格与购买问题。";
                })
                .model(model)
                .streamingModel(streamingModel)
                .onEnter(ctx -> {
                    ShowcaseEventCollector c = ShowcaseEventCollector.current();
                    if (c != null) {
                        c.add(ShowcaseEvent.onEnter("sales", "dynamic instructions resolved"));
                    }
                })
                .build();

        String orderInstructions = Lc4jAgentExtractor.extractInstructions(OrderAssistant.class);
        JAgent orderBase = JAgent.builder("order", "订单专员")
                .description("订单查询、物流与退换货")
                .instructions(orderInstructions)
                .model(model)
                .streamingModel(streamingModel)
                .tools(orderTools)
                .build();
        JAgent order = JAgent.decorate(orderBase)
                .onDelegateEnter((ctx, task) -> {
                    String userId = ctx.get("user_id", String.class);
                    if (userId != null) {
                        String summary = orderRepository.summarizeForContext(orderRepository.findByUserId(userId));
                        ctx.put("order_status", summary);
                    }
                    ShowcaseEventCollector c = ShowcaseEventCollector.current();
                    if (c != null) {
                        c.add(ShowcaseEvent.onEnter("order", "onDelegateEnter: order_status from repository"));
                        c.add(ShowcaseEvent.context("order_status", ctx.get("order_status", String.class)));
                    }
                })
                .onDelegateExit((ctx, task, result) -> {
                    ShowcaseEventCollector c = ShowcaseEventCollector.current();
                    if (c != null) {
                        c.add(ShowcaseEvent.tool("order", "onDelegateExit",
                                result != null ? "result length " + result.length() : "null"));
                    }
                })
                .build();

        JAgent analyst = JAgent.builder("analyst", "数据分析师")
                .description("订单与消费数据摘要分析")
                .instructions("你是数据分析师。根据 task 给出简洁的数据摘要与建议，用条目列出。")
                .model(model)
                .streamingModel(streamingModel)
                .build();

        Swarm swarm = Swarm.create("Jswarm Showcase 智能客服")
                .agent(router)
                .agent(tech)
                .agent(sales)
                .agent(order)
                .agent(analyst)
                .entry("router")
                .handoff("router", "tech", "sales", "order")
                .delegate("router", "order")
                .delegate("order", "analyst")
                .build();

        ExternalToolExecutor swarmToolExecutor = req -> {
            if ("auditLog".equals(req.name())) {
                String message = extractJsonField(req.arguments(), "message");
                if (message == null || message.isBlank()) {
                    message = req.arguments();
                }
                ShowcaseEventCollector c = ShowcaseEventCollector.current();
                if (c != null) {
                    c.add(ShowcaseEvent.tool("swarm", "auditLog", message));
                }
                return "swarm audit recorded: " + message;
            }
            throw new com.jswarm.adapter.lc4j.tool.ToolNotHandledException(req.name());
        };

        return new BuildResult(swarm, swarmToolExecutor, orderRepository, userProfileRepository);
    }

    public static SwarmContext buildUserContext(UserProfileRepository repo, String userId, String sessionId) {
        UserProfileRepository.UserProfile profile = repo.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("未知用户: " + userId));
        SwarmContext ctx = new SwarmContext();
        ctx.put("user_id", profile.userId());
        ctx.put("user_name", profile.name());
        ctx.put("vip_level", profile.vipLevel());
        ctx.put("total_spent", profile.totalSpent());
        ctx.put("session_id", sessionId);
        return ctx;
    }

    public static List<Map<String, Object>> featureCatalog() {
        return List.of(
                Map.of("id", "fromAiService", "label", "fromAiService + decorate", "agent", "router"),
                Map.of("id", "fromTools", "label", "fromTools + @Tool", "agent", "order"),
                Map.of("id", "builderHooks", "label", "builder 生命周期钩子", "agent", "tech / order"),
                Map.of("id", "dynamicInstructions", "label", "动态 instructions", "agent", "sales"),
                Map.of("id", "handoff", "label", "handoff 换人", "agent", "router → *"),
                Map.of("id", "delegate", "label", "delegate 子任务", "agent", "router / order"),
                Map.of("id", "swarmContext", "label", "SwarmContext {key}", "agent", "全部"),
                Map.of("id", "externalTools", "label", "Swarm ExternalToolExecutor", "agent", "auditLog"),
                Map.of("id", "recovery", "label", "错误恢复 SwarmRunOptions", "agent", "全局")
        );
    }

    private static String extractJsonField(String json, String field) {
        if (json == null || json.isBlank()) {
            return null;
        }
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) {
            return null;
        }
        int colon = json.indexOf(':', i);
        if (colon < 0) {
            return null;
        }
        int start = json.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        int end = json.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return json.substring(start + 1, end);
    }
}
