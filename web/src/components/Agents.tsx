import { useEffect, useState, type ReactNode } from 'react';
import {
  CATEGORIES,
  TOOLS,
  FieldErrors,
  deleteAgent,
  listAgents,
  listRuns,
  runAgent,
  saveAgent,
  type Agent,
  type AgentRun,
} from '../api/agents';

const NEW_AGENT: Agent = {
  name: '',
  instructions: '',
  topic: '',
  categories: [],
  windowDays: 7,
  tools: [...TOOLS],
  costProfile: 'batch',
  maxTokensPerRun: 8000,
  daily: false,
};

function toggle(list: string[], value: string): string[] {
  return list.includes(value) ? list.filter((v) => v !== value) : [...list, value];
}

// Reports and sources carry text from third-party .onion pages: everything is rendered as
// plain text (React escapes it), never as HTML. The playground UI is English.
export function Agents() {
  const [agents, setAgents] = useState<Agent[]>([]);
  const [draft, setDraft] = useState<Agent>(NEW_AGENT);
  const [runs, setRuns] = useState<AgentRun[]>([]);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<'saving' | 'running' | null>(null);

  const fail = (e: unknown) => {
    if (e instanceof FieldErrors) setFieldErrors(e.errors);
    else setError(e instanceof Error ? e.message : String(e));
  };

  const select = (agent: Agent) => {
    setDraft(agent);
    setFieldErrors({});
    setError(null);
    setRuns([]);
    if (agent.id !== undefined) listRuns(agent.id).then(setRuns, fail);
  };

  useEffect(() => {
    listAgents().then(setAgents, fail);
  }, []);

  const save = async () => {
    setBusy('saving');
    setFieldErrors({});
    setError(null);
    try {
      const saved = await saveAgent(draft);
      setAgents(await listAgents());
      setDraft(saved);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  };

  const remove = async () => {
    if (draft.id === undefined || !window.confirm(`Delete "${draft.name}" and its reports?`)) return;
    try {
      await deleteAgent(draft.id);
      setAgents(await listAgents());
      select(NEW_AGENT);
    } catch (e) {
      fail(e);
    }
  };

  const run = async () => {
    if (draft.id === undefined) return;
    setBusy('running');
    setError(null);
    try {
      const done = await runAgent(draft.id);
      setRuns((prev) => [done, ...prev]);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(null);
    }
  };

  const field = (name: keyof Agent, label: string, input: ReactNode) => (
    <label className="agent-field">
      <span>{label}</span>
      {input}
      {fieldErrors[name] && <span className="agent-error">{fieldErrors[name]}</span>}
    </label>
  );

  return (
    <div className="chat">
      <nav className="app-tabs agent-list" aria-label="Agents">
        {agents.map((a) => (
          <button
            key={a.id}
            type="button"
            className={draft.id === a.id ? 'active' : undefined}
            onClick={() => select(a)}
          >
            {a.name}
          </button>
        ))}
        <button
          type="button"
          className={draft.id === undefined ? 'active' : undefined}
          onClick={() => select(NEW_AGENT)}
        >
          + new agent
        </button>
      </nav>

      <form
        className="agent-form"
        onSubmit={(e) => {
          e.preventDefault();
          save();
        }}
      >
        {field(
          'name',
          'name',
          <input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />,
        )}
        {field(
          'topic',
          'topic (search query)',
          <input value={draft.topic} onChange={(e) => setDraft({ ...draft, topic: e.target.value })} />,
        )}
        {field(
          'instructions',
          'instructions',
          <textarea
            rows={4}
            value={draft.instructions}
            onChange={(e) => setDraft({ ...draft, instructions: e.target.value })}
          />,
        )}
        {field(
          'tools',
          'tools',
          <span className="agent-options">
            {TOOLS.map((t) => (
              <label key={t}>
                <input
                  type="checkbox"
                  checked={draft.tools.includes(t)}
                  onChange={() => setDraft({ ...draft, tools: toggle(draft.tools, t) })}
                />
                {t}
              </label>
            ))}
          </span>,
        )}
        {field(
          'categories',
          'change categories (none = all)',
          <span className="agent-options">
            {CATEGORIES.map((c) => (
              <label key={c}>
                <input
                  type="checkbox"
                  checked={draft.categories.includes(c)}
                  onChange={() => setDraft({ ...draft, categories: toggle(draft.categories, c) })}
                />
                {c}
              </label>
            ))}
          </span>,
        )}
        {field(
          'windowDays',
          'change window (days)',
          <input
            type="number"
            min={1}
            max={30}
            value={draft.windowDays}
            onChange={(e) => setDraft({ ...draft, windowDays: e.target.valueAsNumber })}
          />,
        )}
        {field(
          'costProfile',
          'cost profile',
          <select
            value={draft.costProfile}
            onChange={(e) => setDraft({ ...draft, costProfile: e.target.value as Agent['costProfile'] })}
          >
            <option value="interactive">interactive (20 s)</option>
            <option value="batch">batch (60 s)</option>
          </select>,
        )}
        {field(
          'maxTokensPerRun',
          'max tokens per run',
          <input
            type="number"
            min={1}
            value={draft.maxTokensPerRun}
            onChange={(e) => setDraft({ ...draft, maxTokensPerRun: e.target.valueAsNumber })}
          />,
        )}
        {field(
          'daily',
          'run daily',
          <input
            type="checkbox"
            checked={draft.daily}
            onChange={(e) => setDraft({ ...draft, daily: e.target.checked })}
          />,
        )}

        <div className="agent-actions">
          <button type="submit" disabled={busy !== null}>
            {busy === 'saving' ? 'saving…' : 'save'}
          </button>
          {draft.id !== undefined && (
            <>
              <button type="button" onClick={run} disabled={busy !== null}>
                {busy === 'running' ? 'running…' : 'run'}
              </button>
              <button type="button" onClick={remove} disabled={busy !== null}>
                delete
              </button>
            </>
          )}
        </div>
      </form>

      {error && <p className="empty-state">$ {error}</p>}

      {draft.id !== undefined && (
        <div className="results">
          <p className="results-count">
            <span className="results-count-number">{runs.length}</span> report{runs.length === 1 ? '' : 's'}
          </p>
          <div className="results-list">
            {runs.map((r) => (
              <div className="result-row" key={r.id}>
                <div className="result-line">
                  <span className="result-version">{r.status}</span>
                  <span className="result-branch">
                    {new Date(r.startedAt).toLocaleString()} · {r.trigger} · {r.tokensUsed} tokens
                    {r.durationMillis !== null && ` · ${(r.durationMillis / 1000).toFixed(1)} s`}
                    {r.failureReason && ` · ${r.failureReason}`}
                    {r.unavailableTools.length > 0 && ` · unavailable: ${r.unavailableTools.join(', ')}`}
                  </span>
                </div>
                {r.report && <p className="chat-answer">{r.report}</p>}
                {r.sources.map((s) => (
                  <div key={s.id}>
                    <div className="result-line">
                      <span className="result-version">{s.id}</span>
                      <a href={s.url} target="_blank" rel="noreferrer">
                        {s.url}
                      </a>
                    </div>
                    <p className="result-snippet">{s.text}</p>
                  </div>
                ))}
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
