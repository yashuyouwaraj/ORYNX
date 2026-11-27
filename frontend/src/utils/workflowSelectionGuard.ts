/**
 * Tracks the active selected workflow ID and monotonic request sequence to prevent
 * unrelated WebSocket events or stale out-of-order async API responses from
 * overwriting the currently displayed workflow's Task DAG and Execution Timeline.
 */
export interface WorkflowSelectionToken {
  workflowId: number;
  seq: number;
}

export interface WorkflowSelectionGuard {
  selectWorkflow: (workflowId: number) => WorkflowSelectionToken;
  getSelectedWorkflowId: () => number | null;
  isCurrentSelection: (workflowId: number) => boolean;
  beginFetchForCurrentSelection: (workflowId: number) => WorkflowSelectionToken | null;
  shouldApplyFetchResult: (token: WorkflowSelectionToken) => boolean;
}

export function createWorkflowSelectionGuard(
  initialWorkflowId: number | null = null,
): WorkflowSelectionGuard {
  let selectedId: number | null = initialWorkflowId;
  let seq = 0;

  return {
    selectWorkflow(workflowId: number): WorkflowSelectionToken {
      selectedId = workflowId;
      seq += 1;
      return { workflowId, seq };
    },

    getSelectedWorkflowId(): number | null {
      return selectedId;
    },

    isCurrentSelection(workflowId: number): boolean {
      return selectedId !== null && selectedId === workflowId;
    },

    beginFetchForCurrentSelection(workflowId: number): WorkflowSelectionToken | null {
      if (selectedId === null || selectedId !== workflowId) {
        return null;
      }
      seq += 1;
      return { workflowId, seq };
    },

    shouldApplyFetchResult(token: WorkflowSelectionToken): boolean {
      return selectedId === token.workflowId && seq === token.seq;
    },
  };
}
