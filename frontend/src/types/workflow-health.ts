export interface WorkflowHealth {
  workflowName: string;
  durationMs: number;
  health: string;
  reason: string;
}