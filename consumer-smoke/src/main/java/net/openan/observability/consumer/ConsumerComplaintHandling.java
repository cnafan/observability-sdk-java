/*
 * Copyright (c) 2026 Huawei Technologies Co., Ltd.
 * All Rights Reserved.
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 *    Licensed under the Apache License, Version 2.0 (the "License"); you may
 *    not use this file except in compliance with the License. You may obtain
 *    a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *    WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *    License for the specific language governing permissions and limitations
 *    under the License.
 */

package net.openan.observability.consumer;

import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.openan.observability.LogSeverity;
import net.openan.observability.ObservationSpan;
import net.openan.observability.Observability;
import net.openan.observability.ObservabilitySdk;
import net.openan.observability.SdkConfig;
import net.openan.observability.SignalKind;

/** Emits the IG1504D enterprise private-line complaint-handling workflow. */
public final class ConsumerComplaintHandling {
    private static final String[] STEPS = {"投诉受理", "投诉识别", "定界定位", "根因定位", "问题处置", "修复验证", "投诉报结"};
    private static final String[] OWNERS = {"WAIMO Complaint Agent", "WAIMO Complaint Agent", "WAIMO Complaint Agent",
        "Transport Complaint Agent", "West-GD NETSpirit", "Transport Complaint Agent", "WAIMO Complaint Agent"};

    private ConsumerComplaintHandling() { }

    public static void main(String[] args) {
        String runId = args.length == 0 ? "local" : args[0];
        try (var sdk = ObservabilitySdk.start(SdkConfig.builder().serviceName("ig1504d-complaint-" + runId).build())) {
            emit(sdk.observe(), runId);
            if (!sdk.flush(20, TimeUnit.SECONDS)) throw new IllegalStateException("OTLP export failed or timed out");
        }
    }

    private static void emit(Observability observe, String runId) {
        String workflowId = "IG1504D-" + runId;
        Map<String, Object> workflow = Map.of("workflow.id", workflowId, "workflow.type", "enterprise-private-line-complaint-handling",
            "workflow.status", "completed", "workflow.session_id", "sla-complaint-" + runId);
        try (var root = observe.trace("a2at.workflow", SignalKind.INTERNAL, workflow)) {
            observe.log("gen_ai.agent.a2at.workflow", LogSeverity.INFO, "Complaint handling started", workflow, root);
            for (int i = 0; i < STEPS.length; i++) emitStep(observe, root, runId, i + 1);
            observe.metric("workflow.duration", 9720, Map.of("workflow.type", "enterprise-private-line-complaint-handling", "workflow.status", "completed"));
            observe.metric("workflow.count", 1, Map.of("workflow.type", "enterprise-private-line-complaint-handling", "workflow.status", "completed"));
            observe.log("gen_ai.agent.a2at.workflow", LogSeverity.INFO, "Complaint handling completed", workflow, root);
            root.ok();
        }
    }

    private static void emitStep(Observability observe, ObservationSpan root, String runId, int index) {
        String owner = OWNERS[index - 1];
        Map<String, Object> step = Map.of("workflow.step", STEPS[index - 1], "workflow.step.index", index,
            "workflow.step.status", "completed", "workflow.step.owner_agent", owner);
        try (var stepSpan = observe.trace("a2at.step.collect", SignalKind.INTERNAL, step, root)) {
            try (var substep = observe.trace("a2at.substep.analyze", SignalKind.INTERNAL,
                    Map.of("gen_ai.agent.name", owner, "substep.name", STEPS[index - 1], "substep.status", "completed"), stepSpan)) {
                observe.log("gen_ai.agent.a2at.substep", LogSeverity.INFO, STEPS[index - 1] + " completed", step, substep);
                observe.log("gen_ai.agent.a2at.agent", LogSeverity.INFO, owner + " completed " + STEPS[index - 1],
                    Map.of("gen_ai.agent.name", owner, "agent.state", "completed"), substep);
                if (index == 4) pair(observe, substep, runId, "West-GD NETSpirit");
                if (index == 4) pair(observe, substep, runId, "East-GD NETSpirit");
                if (index == 5) pair(observe, substep, runId, "West-GD NETSpirit");
                observe.metric("substep.duration", 600, Map.of("gen_ai.agent.name", owner, "substep.name", STEPS[index - 1]));
                substep.ok();
            }
            observe.metric("step.duration", 720, Map.of("workflow.step", STEPS[index - 1], "gen_ai.agent.name", owner));
            stepSpan.ok();
        }
    }

    private static void pair(Observability observe, ObservationSpan parent, String runId, String target) {
        Map<String, Object> attrs = Map.of("operation.name", "SendMessage", "task_id", "complaint-" + runId,
            "context_id", "complaint-context-" + runId, "extension.name", "task-t", "task.type", "fault-location", "task.object", target);
        try (var client = observe.trace("SendMessage", SignalKind.CLIENT, attrs, parent);
                var server = observe.trace("SendMessage", SignalKind.SERVER, attrs, client)) {
            observe.log("gen_ai.agent.a2at.message", LogSeverity.INFO, "Complaint task dispatched to " + target, attrs, client);
            observe.metric("task.count", 1, Map.of("task.type", "fault-location", "task.object", target));
            server.ok(); client.ok();
        }
    }
}
