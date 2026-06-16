// 流式 run 的统一事件模型
package com.jswarm.core;

public sealed interface SwarmEvent {

    record RunStarted(String sessionId, String entryAgentId) implements SwarmEvent {}
    record AgentEnter(String agentId, String source) implements SwarmEvent {}
    record AgentExit(String agentId) implements SwarmEvent {}
    record Token(String agentId, String text) implements SwarmEvent {}
    record ToolCall(String agentId, String toolName, String args) implements SwarmEvent {}
    record ToolResult(String agentId, String toolName, String result) implements SwarmEvent {}
    record Handoff(String from, String to) implements SwarmEvent {}
    record DelegateStarted(String parent, String delegateAgent, String task) implements SwarmEvent {}
    record DelegateFinished(String parent, String delegateAgent) implements SwarmEvent {}
    record RecoveryTriggered(String agentId, String reason) implements SwarmEvent {}
    record RunCompleted(String finalText) implements SwarmEvent {}
    record RunFailed(String agentId, String error) implements SwarmEvent {}
}
