export interface GraphNode {
  id: number;
  label: string;
  status?: string;
  x: number;
  y: number;
}

export interface GraphConnection {
  source: number;
  target: number;
}
