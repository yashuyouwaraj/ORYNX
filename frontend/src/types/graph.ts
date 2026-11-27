export interface GraphNode {
  id: number;
  label: string;
  status?: string;
  x: number;
  y: number;
  taskKey?: string;
  taskType?: string;
  retryCount?: number;
  depth?: number;
}

export interface GraphConnection {
  source: number;
  target: number;
}

export interface WorkflowTaskNode {
  id: number;
  taskKey?: string;
  name: string;
  taskType?: string;
  status?: string;
  executionOrder?: number;
  dependsOn?: string[];
  retryCount?: number;
  maxRetries?: number;
}
