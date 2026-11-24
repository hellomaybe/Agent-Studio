import { expect, test } from '@playwright/test';
import {
  bindingChoices,
  initialPositions,
  insertOnEdge,
  reachable,
} from '../src/workbench/editor-graph';
import type { Capability, GraphDefinition, GraphNode } from '../src/workbench/types';

// A capability outside the two shipped templates should work through metadata alone.
const text: Capability = {
  type: 'text-source',
  label: 'Text',
  config: {},
  inputs: {},
  outputs: { text: 'TEXT', count: 'NUMBER' },
  ports: ['next'],
};
const custom: Capability = {
  type: 'normalize-text',
  label: 'Trim',
  config: { trim: true },
  inputs: { value: 'TEXT' },
  outputs: { normalized: 'TEXT' },
  ports: ['next'],
};
const sink: Capability = {
  type: 'sink',
  label: 'Output',
  config: {},
  inputs: { message: 'TEXT' },
  outputs: {},
  ports: [],
};
const palette = [text, custom, sink];
const node = (id: string, type: string, bindings: Record<string, string> = {}): GraphNode => ({
  id,
  type,
  bindings,
  config: {},
});
const task = { from: 'start', port: 'next', to: 'end' };
const graph: GraphDefinition = {
  nodes: [node('start', text.type), node('end', sink.type, { message: 'start.text' })],
  edges: [task],
};

test('inserting a new capability rewires compatible data and control ports', () => {
  const updated = insertOnEdge(graph, task, node('clean', custom.type), palette);
  expect(updated.edges).toEqual([
    { from: 'start', port: 'next', to: 'clean' },
    { from: 'clean', port: 'next', to: 'end' },
  ]);
  expect(updated.nodes.find((n) => n.id === 'clean')?.bindings).toEqual({ value: 'start.text' });
  expect(updated.nodes.find((n) => n.id === 'end')?.bindings).toEqual({
    message: 'clean.normalized',
  });
  expect(graph.nodes[1]?.bindings).toEqual({ message: 'start.text' });
  expect(bindingChoices('end', 'TEXT', updated, palette).map((c) => c.value)).toEqual([
    'start.text',
    'clean.normalized',
  ]);
});

test('bindings never offer unrelated nodes or differently typed fields', () => {
  const detached: GraphDefinition = {
    ...graph,
    nodes: [...graph.nodes, node('detached', text.type)],
  };
  expect(bindingChoices('end', 'NUMBER', detached, palette).map((c) => c.value)).toEqual([
    'start.count',
  ]);
  expect(bindingChoices('start', 'TEXT', detached, palette)).toEqual([]);
  expect(reachable('start', graph.edges).has('end')).toBe(true);
  expect(reachable('end', graph.edges).has('start')).toBe(false);
});

test('layout separates branch lanes and respects manually saved coordinates', () => {
  const branches: GraphDefinition = {
    nodes: [node('gate', 'switch'), node('a', 'work'), node('b', 'work'), node('end', 'end')],
    edges: [
      { from: 'gate', port: 'yes', to: 'a' },
      { from: 'gate', port: 'no', to: 'b' },
      { from: 'a', port: 'next', to: 'end' },
      { from: 'b', port: 'next', to: 'end' },
    ],
  };
  const positions = initialPositions(branches);
  expect(positions.get('a')!.x).not.toBe(positions.get('b')!.x);
  expect(positions.get('end')!.y).toBeGreaterThan(positions.get('a')!.y);
  const moved = {
    ...branches,
    nodes: branches.nodes.map((n) => (n.id === 'a' ? { ...n, position: { x: -42, y: 125 } } : n)),
  };
  expect(initialPositions(moved).get('a')).toEqual({ x: -42, y: 125 });
});

test('a join may bind only a value present on every path', () => {
  const gate: Capability = { ...text, type: 'gate', ports: ['yes', 'no'] };
  const direct = { from: 'branch', port: 'no', to: 'join' };
  const original: GraphDefinition = {
    nodes: [
      node('start', text.type),
      node('branch', gate.type),
      node('filtered', custom.type),
      node('join', sink.type, { message: 'branch.text' }),
    ],
    edges: [
      { from: 'start', port: 'next', to: 'branch' },
      { from: 'branch', port: 'yes', to: 'filtered' },
      { from: 'filtered', port: 'next', to: 'join' },
      direct,
    ],
  };
  const metadata = [...palette, gate];
  const joined = insertOnEdge(original, direct, node('alternate', custom.type), metadata);
  expect(joined.nodes.find((n) => n.id === 'join')?.bindings.message).toBe('branch.text');
  expect(bindingChoices('join', 'TEXT', joined, metadata).map((o) => o.value)).toEqual([
    'start.text',
    'branch.text',
  ]);
});
