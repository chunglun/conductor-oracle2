/*
 * Copyright 2023 Conductor Authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.netflix.conductor.contribs.listener.archive;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.conductor.core.dal.ExecutionDAOFacade;
import com.netflix.conductor.core.listener.WorkflowStatusListener;
import com.netflix.conductor.metrics.Monitors;
import com.netflix.conductor.model.WorkflowModel;

import jakarta.annotation.*;

public class ArchivingWithTTLWorkflowStatusListener implements WorkflowStatusListener {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(ArchivingWithTTLWorkflowStatusListener.class);

    private final ExecutionDAOFacade executionDAOFacade;
    private final int archiveTTLSeconds;
    private final int firstDelayArchiveSeconds;
    private final int secondDelayArchiveSeconds;
    private final ScheduledThreadPoolExecutor scheduledThreadPoolExecutor;

    public ArchivingWithTTLWorkflowStatusListener(
            ExecutionDAOFacade executionDAOFacade, ArchivingWorkflowListenerProperties properties) {
        this.executionDAOFacade = executionDAOFacade;
        this.archiveTTLSeconds = (int) properties.getTtlDuration().getSeconds();
        this.firstDelayArchiveSeconds = properties.getWorkflowFirstArchivalDelay();
        this.secondDelayArchiveSeconds = properties.getWorkflowSecondArchivalDelay();

        this.scheduledThreadPoolExecutor =
                new ScheduledThreadPoolExecutor(
                        properties.getDelayQueueWorkerThreadCount(),
                        (runnable, executor) -> {
                            LOGGER.warn(
                                    "Request {} to delay archiving index dropped in executor {}",
                                    runnable,
                                    executor);
                            Monitors.recordDiscardedArchivalCount();
                        });
        this.scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
        LOGGER.warn(
                "Workflow removal with TTL is no longer supported, "
                        + "when using this class, workflows will be removed immediately");
    }

    @PreDestroy
    public void shutdownExecutorService() {
        try {
            LOGGER.info("Gracefully shutdown executor service");
            scheduledThreadPoolExecutor.shutdown();
            if (scheduledThreadPoolExecutor.awaitTermination(
                    firstDelayArchiveSeconds, TimeUnit.SECONDS)) {
                LOGGER.debug("tasks completed, shutting down");
            } else {
                LOGGER.warn(
                        "Forcing shutdown after waiting for {} seconds", firstDelayArchiveSeconds);
                scheduledThreadPoolExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            LOGGER.warn(
                    "Shutdown interrupted, invoking shutdownNow on scheduledThreadPoolExecutor for delay queue");
            scheduledThreadPoolExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void onWorkflowCompleted(WorkflowModel workflow) {
        LOGGER.info("Archiving workflow {} on completion ", workflow.getWorkflowId());
        if (firstDelayArchiveSeconds > 0) {
            scheduledThreadPoolExecutor.schedule(
                    new DelayArchiveWorkflow(workflow, executionDAOFacade),
                    firstDelayArchiveSeconds,
                    TimeUnit.SECONDS);
        } else {
            this.executionDAOFacade.removeWorkflow(workflow.getWorkflowId(), true);
            Monitors.recordWorkflowArchived(workflow.getWorkflowName(), workflow.getStatus());
        }
    }

    @Override
    public void onWorkflowTerminated(WorkflowModel workflow) {
        LOGGER.info("Archiving workflow {} on termination", workflow.getWorkflowId());
        LOGGER.info(
                "workflow {} is not in COMPLETED state ({})",
                workflow.getWorkflowId(),
                workflow.getStatus());
        if (secondDelayArchiveSeconds > 0) {
            scheduledThreadPoolExecutor.schedule(
                    new DelayArchiveWorkflow(workflow, executionDAOFacade),
                    secondDelayArchiveSeconds,
                    TimeUnit.SECONDS);
        } else {
            this.executionDAOFacade.removeWorkflow(workflow.getWorkflowId(), true);
            Monitors.recordWorkflowArchived(workflow.getWorkflowName(), workflow.getStatus());
        }
    }

    private class DelayArchiveWorkflow implements Runnable {

        private final String workflowId;
        private final String workflowName;
        private final WorkflowModel.Status status;
        private final ExecutionDAOFacade executionDAOFacade;

        DelayArchiveWorkflow(WorkflowModel workflow, ExecutionDAOFacade executionDAOFacade) {
            this.workflowId = workflow.getWorkflowId();
            this.workflowName = workflow.getWorkflowName();
            this.status = workflow.getStatus();
            this.executionDAOFacade = executionDAOFacade;
        }

        @Override
        public void run() {
            try {
                this.executionDAOFacade.removeWorkflow(workflowId, true);
                LOGGER.info("Archived workflow {}", workflowId);
                Monitors.recordWorkflowArchived(workflowName, status);
                Monitors.recordArchivalDelayQueueSize(
                        scheduledThreadPoolExecutor.getQueue().size());
            } catch (Exception e) {
                LOGGER.error("Unable to archive workflow: {}", workflowId, e);
            }
        }
    }
}
