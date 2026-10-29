// Agent Playground (Fase 5): saved agents and their runs. Single user, no login.

export const TOOLS = ['search', 'changes', 'entities'] as const;
// The classifier's taxonomy (core content.Categories).
export const CATEGORIES = ['marketplace', 'forum', 'blog', 'service', 'institutional', 'other'] as const;

export interface Agent {
  id?: number;
  name: string;
  instructions: string;
  topic: string;
  categories: string[];
  windowDays: number;
  tools: string[];
  costProfile: 'interactive' | 'batch';
  maxTokensPerRun: number;
  daily: boolean;
}

// id is what the report cites (S1, C1, E1...); url is the page, or the entity's API for entities.
export interface AgentRunSource {
  id: string;
  tool: string;
  url: string;
  text: string;
}

export interface AgentRun {
  id: number;
  agentId: number;
  trigger: 'manual' | 'daily';
  status: 'running' | 'succeeded' | 'failed';
  failureReason: 'budget' | 'timeout' | 'error' | null;
  report: string | null;
  sources: AgentRunSource[];
  unavailableTools: string[];
  tokensUsed: number;
  startedAt: string;
  finishedAt: string | null;
  durationMillis: number | null;
}

// A 400 or 409 from the API: one message per rejected field.
export class FieldErrors extends Error {
  readonly errors: Record<string, string>;

  constructor(errors: Record<string, string>) {
    super(Object.values(errors).join('; '));
    this.errors = errors;
  }
}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: init?.body ? { 'Content-Type': 'application/json' } : undefined,
  });
  if (res.status === 400 || res.status === 409) {
    const body = await res.json().catch(() => null);
    if (body?.errors) throw new FieldErrors(body.errors);
  }
  if (!res.ok) {
    throw new Error(`${init?.method ?? 'GET'} ${path} failed: ${res.status}`);
  }
  return res.status === 204 ? (undefined as T) : res.json();
}

// Ids come back from the API; only a whole number goes into a path.
function agentPath(id: number): string {
  const n = Number(id);
  if (!Number.isSafeInteger(n)) throw new Error(`invalid agent id: ${id}`);
  return `/api/agents/${n}`;
}

export function listAgents(): Promise<Agent[]> {
  return call('/api/agents');
}

export function saveAgent(agent: Agent): Promise<Agent> {
  return agent.id === undefined
    ? call('/api/agents', { method: 'POST', body: JSON.stringify(agent) })
    : call(agentPath(agent.id), { method: 'PUT', body: JSON.stringify(agent) });
}

export function deleteAgent(id: number): Promise<void> {
  return call(agentPath(id), { method: 'DELETE' });
}

// Synchronous: resolves when the run ends, up to the cost profile's deadline (20 s or 60 s).
export function runAgent(id: number): Promise<AgentRun> {
  return call(`${agentPath(id)}/runs`, { method: 'POST' });
}

// Newest first.
export function listRuns(id: number): Promise<AgentRun[]> {
  return call(`${agentPath(id)}/runs`);
}
