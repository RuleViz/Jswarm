// Showcase 应用入口
package com.jswarm.examples.showcase;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;

import java.nio.file.Path;

public final class ShowcaseApplication {

    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getProperty("deepseek.api.key");
        }
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("请设置环境变量 DEEPSEEK_API_KEY");
            System.exit(1);
        }

        ChatModel model = OpenAiChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .modelName("deepseek-chat")
                .build();

        StreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .modelName("deepseek-chat")
                .build();

        ShowcaseSwarmFactory.BuildResult build = ShowcaseSwarmFactory.build(model, streamingModel);
        Path dbPath = Path.of("data", "showcase.db");
        ShowcaseSessionStore sessionStore = new ShowcaseSessionStore(dbPath);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                sessionStore.close();
            } catch (Exception ignored) {
            }
        }));
        new ShowcaseHttpServer(build, sessionStore).start();
        System.out.println("会话持久化: " + dbPath.toAbsolutePath());
    }
}
