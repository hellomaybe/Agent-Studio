export interface GraphPosition {
  x: number;
  y: number;
}

export type ConfigValue = string | number | boolean | null;

export interface ConfigField {
  key: string;
  label: string;
  type: 'text' | 'textarea' | 'number' | 'boolean' | 'select';
  options?: (string | { label: string; value: ConfigValue })[] | null;
  min?: number | null;
  max?: number | null;
}

export interface Capability {
  liveModel?: boolean;
  type: string;
  label: string;
  inputs: Record<string, string>;
  outputs: Record<string, string>;
  ports: string[];
  config: Record<string, ConfigValue>;
  configFields?: ConfigField[];
  exampleInputs?: Record<string, unknown>;
  version?: string | number;
}

export interface GraphNode {
  id: string;
  type: string;
  config: Record<string, ConfigValue>;
  bindings: Record<string, string>;
  position?: GraphPosition | null;
}

export interface GraphEdge {
  from: string;
  port: string;
  to: string;
}

export interface GraphDefinition {
  nodes: GraphNode[];
  edges: GraphEdge[];
}

export interface GraphIssue {
  detail: string;
  nodeId?: string;
  field?: string;
  port?: string;
}
