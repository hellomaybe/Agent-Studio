import type { Capability, GraphDefinition, GraphEdge, GraphNode, GraphPosition } from './types';

export function portLabel(port: string) {
  return ({ next: '下一步', yes: '是', no: '否' } as Record<string, string>)[port] ?? port;
}

export function reachable(start: string, edges: GraphEdge[]) {
  const seen = new Set<string>();
  const queue = [start];
  for (let i = 0; i < queue.length; i++) {
    const current = queue[i]!;
    if (seen.has(current)) continue;
    seen.add(current);
    for (const edge of edges) {
      if (edge.from === current) queue.push(edge.to);
    }
  }
  return seen;
}

export function initialPositions(graph: GraphDefinition): Map<string, GraphPosition> {
  const incoming = new Map(graph.nodes.map((n) => [n.id, 0]));
  const outgoing = new Map(graph.nodes.map((n) => [n.id, [] as string[]]));
  for (const edge of graph.edges) {
    if (!incoming.has(edge.from) || !incoming.has(edge.to)) continue;
    incoming.set(edge.to, incoming.get(edge.to)! + 1);
    outgoing.get(edge.from)!.push(edge.to);
  }
  const queue = graph.nodes.filter((n) => incoming.get(n.id) === 0).map((n) => n.id);
  const ranks = new Map<string, number>();
  for (let i = 0; i < queue.length; i++) {
    const id = queue[i]!;
    const rank = ranks.get(id) ?? 0;
    ranks.set(id, rank);
    for (const target of outgoing.get(id)!) {
      ranks.set(target, Math.max(ranks.get(target) ?? 0, rank + 1));
      incoming.set(target, incoming.get(target)! - 1);
      if (!incoming.get(target)) queue.push(target);
    }
  }
  // Forks receive separate lanes; joins return to the middle of their incoming lanes.
  const positions = new Map<string, GraphPosition>();
  const used = new Map<number, number[]>();
  const nodeById = new Map(graph.nodes.map((node) => [node.id, node]));
  for (const id of [
    ...queue,
    ...graph.nodes.filter((n) => !queue.includes(n.id)).map((n) => n.id),
  ]) {
    const row = ranks.get(id) ?? 0;
    const node = nodeById.get(id)!;
    const arrivals = graph.edges.filter((edge) => edge.to === id && positions.has(edge.from));
    const projections = arrivals.map((edge) => {
      const siblings = outgoing.get(edge.from)!;
      return positions.get(edge.from)!.x + (siblings.indexOf(id) - (siblings.length - 1) / 2) * 225;
    });
    let x = projections.length
      ? Math.round(projections.reduce((sum, value) => sum + value, 0) / projections.length)
      : 0;
    const occupied = used.get(row) ?? [];
    while (occupied.some((other) => Math.abs(other - x) < 205)) x += 220;
    const position = node.position ?? { x, y: row * 86 };
    occupied.push(position.x);
    used.set(row, occupied);
    positions.set(id, position);
  }
  return positions;
}

export function bindingChoices(
  nodeId: string,
  inputType: string,
  graph: GraphDefinition,
  capabilities: Capability[],
) {
  // A value can be bound only if its producing node ran on every path to the consumer.
  // A node on one side of a branch is not available after a join.
  const memo = new Map<string, Set<string>>();
  const visiting = new Set<string>();
  function guaranteedBefore(id: string): Set<string> {
    const cached = memo.get(id);
    if (cached) return cached;
    if (visiting.has(id)) return new Set();
    visiting.add(id);
    const parents = graph.edges.filter((e) => e.to === id).map((e) => e.from);
    let common: Set<string> | undefined;
    for (const parent of parents) {
      const path = new Set([parent, ...guaranteedBefore(parent)]);
      if (!common) common = path;
      else for (const prior of common) if (!path.has(prior)) common.delete(prior);
    }
    const result = common ?? new Set<string>();
    result.delete(id);
    visiting.delete(id);
    memo.set(id, result);
    return result;
  }
  const upstream = guaranteedBefore(nodeId);
  return graph.nodes
    .filter((n) => upstream.has(n.id))
    .flatMap((n) => {
      const capability = capabilities.find((c) => c.type === n.type);
      return Object.entries(capability?.outputs ?? {})
        .filter(([, type]) => type === inputType)
        .map(([key]) => ({
          value: `${n.id}.${key}`,
          label: `${capability?.label ?? n.type} · ${n.id}.${key}`,
        }));
    });
}

function matchingOutput(inputKey: string, inputType: string, outputs: Record<string, string>) {
  const matching = Object.keys(outputs).filter((key) => outputs[key] === inputType);
  if (matching.includes(inputKey)) return inputKey;
  return matching.length === 1 ? matching[0] : undefined;
}

/** Insert into one control-flow edge. Rewire a field only when the transformation
 * consumes its original value and declares one unambiguous compatible output. */
export function insertOnEdge(
  graph: GraphDefinition,
  edge: GraphEdge,
  node: GraphNode,
  capabilities: Capability[],
): GraphDefinition {
  const cap = capabilities.find((c) => c.type === node.type)!;
  const source = graph.nodes.find((n) => n.id === edge.from)!;
  const originalOutputs = capabilities.find((c) => c.type === source.type)?.outputs ?? {};
  const bindings = { ...node.bindings };
  for (const [key, type] of Object.entries(cap.inputs)) {
    const output = matchingOutput(key, type, originalOutputs);
    if (output) bindings[key] = `${source.id}.${output}`;
  }
  const consumed = new Set(Object.values(bindings));
  const singleIncoming = graph.edges.filter((e) => e.to === edge.to).length === 1;
  return {
    ...graph,
    nodes: [
      ...graph.nodes.map((target) => {
        if (target.id !== edge.to || !singleIncoming) return target;
        const targetInputs = capabilities.find((c) => c.type === target.type)?.inputs ?? {};
        const next = { ...target.bindings };
        for (const [key, path] of Object.entries(next)) {
          if (!consumed.has(path) || !targetInputs[key]) continue;
          const output = matchingOutput(key, targetInputs[key], cap.outputs);
          if (output) next[key] = `${node.id}.${output}`;
        }
        return { ...target, bindings: next };
      }),
      { ...node, bindings },
    ],
    edges: [
      ...graph.edges.filter((e) => e.from !== edge.from || e.port !== edge.port),
      { from: edge.from, port: edge.port, to: node.id },
      { from: node.id, port: cap.ports[0]!, to: edge.to },
    ],
  };
}
