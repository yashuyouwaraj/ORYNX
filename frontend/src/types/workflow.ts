export interface Workflow {
  id: number;

  name: string;

  goal: string;

  status: string;
}

export interface WorkflowEvent {
  workflowId: number;

  workflowName: string;

  status: string;
}
