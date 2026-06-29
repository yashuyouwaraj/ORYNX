export interface TaskExecutionEvent {
  workflowId: number;
  taskId: number;
  taskName: string;
  status: string;
}
