export interface TaskExecutionEvent {
  workflowId: number;
  taskId: number;
  taskName: string;
  status: string;
  generation?: number;
  eventId?: string;
  timestamp?: number;
}
