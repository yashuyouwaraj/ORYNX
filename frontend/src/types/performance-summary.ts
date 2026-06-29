export interface PerformanceSummary {
  fastestWorkflow: string;
  fastestDuration: number;

  slowestWorkflow: string;
  slowestDuration: number;

  averageDuration: number;

  completedWorkflowCount: number;
}