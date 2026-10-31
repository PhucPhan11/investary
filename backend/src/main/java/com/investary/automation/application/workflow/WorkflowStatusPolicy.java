package com.investary.automation.application.workflow;

import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

import com.investary.automation.domain.StageStatus;
import com.investary.automation.domain.WorkflowSourceType;
import com.investary.automation.domain.WorkflowStage;
import com.investary.automation.persistence.WorkflowStore.StageRow;

/**
 * Derives a finished workflow's status from its stages.
 * <ul>
 *   <li>FAILED when a mandatory stage failed: an email must be imported, parsed and indexed; a library
 *       document must be indexed. Indexing is what makes the research usable at all.</li>
 *   <li>PARTIAL when an optional stage failed or finished partially (e.g. one report could not be
 *       downloaded, or one assessment failed).</li>
 *   <li>COMPLETED when every stage completed or was skipped for lack of eligible input.</li>
 * </ul>
 */
public final class WorkflowStatusPolicy {

    private static final Set<WorkflowStage> EMAIL_MANDATORY = EnumSet.of(WorkflowStage.EMAIL_IMPORTED, WorkflowStage.EMAIL_PARSED,
            WorkflowStage.KNOWLEDGE_INDEXED);
    private static final Set<WorkflowStage> DOCUMENT_MANDATORY = EnumSet.of(WorkflowStage.KNOWLEDGE_INDEXED);

    private WorkflowStatusPolicy() {
    }

    public static boolean isMandatory(WorkflowSourceType type, WorkflowStage stage) {
        return (type == WorkflowSourceType.EMAIL ? EMAIL_MANDATORY : DOCUMENT_MANDATORY).contains(stage);
    }

    /**
     * @param stages all stages except {@link WorkflowStage#COMPLETED}; all must be terminal
     */
    public static StageStatus finalStatus(WorkflowSourceType type, Collection<StageRow> stages) {
        boolean partial = false;
        for (StageRow stage : stages) {
            if (stage.stage() == WorkflowStage.COMPLETED) {
                continue;
            }
            if (!stage.status().isTerminal()) {
                return StageStatus.RUNNING;
            }
            if (stage.status() == StageStatus.FAILED && isMandatory(type, stage.stage())) {
                return StageStatus.FAILED;
            }
            if (stage.status() == StageStatus.FAILED || stage.status() == StageStatus.PARTIAL) {
                partial = true;
            }
        }
        return partial ? StageStatus.PARTIAL : StageStatus.COMPLETED;
    }
}
