export interface Analytics {
  totalWorkflows: number;

  completedWorkflows: number;

  runningWorkflows: number;

  totalTasks: number;

  averageExecutionTime: number;

  workflowSuccessRate: number;

  workflowFailureRate: number;

  executedTaskCount: number;

  eventCount: number;
}
