import { useState } from 'react';
import { api } from '../api/client';

export default function TestPlanning({ projectId }) {
  const [plan, setPlan] = useState(null);
  const [drafts, setDrafts] = useState([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [filter, setFilter] = useState('');
  const [level, setLevel] = useState('UNIT');
  const [executions, setExecutions] = useState({});

  async function load() {
    setBusy(true);
    setError('');
    try {
      const [nextPlan, nextDrafts] = await Promise.all([api.getTestPlan(projectId), api.getTestDrafts(projectId)]);
      setPlan(nextPlan);
      setDrafts(nextDrafts);
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }

  async function generate(item) {
    setBusy(true);
    setError('');
    try {
      const draft = await api.generateTestDraft(projectId, plan.snapshotId, item.id);
      setDrafts(current => [draft, ...current].slice(0, 100));
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }

  async function execution(draft, start) {
    setBusy(true);
    setError('');
    try {
      const result = start ? await api.executeTestDraft(projectId, draft.id) : await api.getDraftExecution(projectId, draft.id);
      setExecutions(current => ({ ...current, [draft.id]: result }));
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }

  const items = plan?.items.filter(item => item.level === level
    && `${item.sourcePath} ${item.language} ${item.framework}`.toLowerCase().includes(filter.toLowerCase())) || [];

  return <section className="card space-y-4">
    <div className="flex justify-between items-center gap-3">
      <h2 className="text-lg font-bold">Multi-language test planning and generation</h2>
      <button className="btn-primary text-xs" disabled={busy} onClick={load}>{busy ? 'Working…' : 'Load / refresh plan'}</button>
    </div>
    <p className="text-sm text-slate-400">Java · Python · JavaScript/TypeScript. Generate a draft, review it, then explicitly execute it in an offline container. Dependencies must already be in the worker image. No host fallback or system testing.</p>
    {error && <p role="alert" className="text-sm text-red-300">{error}</p>}
    {plan && <>
      <p className="text-xs text-slate-400 break-all">Snapshot: {plan.snapshotId} · Provider: {plan.provider}</p>
      {plan.provider === 'mock' && <p className="text-sm text-amber-300">Mock mode produces intentionally skipped scaffolds—not meaningful tests. Configure a real provider for behavioral test generation.</p>}
      <div className="flex gap-3">
        <input aria-label="Filter planned sources" className="input-field" placeholder="Filter path, language or framework" value={filter} onChange={e => setFilter(e.target.value)} />
        <select aria-label="Test level" className="input-field" value={level} onChange={e => setLevel(e.target.value)}>
          <option value="UNIT">Unit</option><option value="MODULE">Module</option><option value="INTEGRATION">Integration</option>
        </select>
      </div>
      <div className="max-h-96 overflow-auto space-y-2">
        {items.length === 0 && <p className="text-sm text-slate-400">No matching source targets.</p>}
        {items.map(item => <div key={item.id} className="border border-slate-800 rounded p-3 space-y-2">
          <div className="flex justify-between gap-3"><code className="text-sm break-all">{item.sourcePath}</code>
            <button className="btn-secondary text-xs" disabled={busy || !item.applicable} onClick={() => generate(item)}>Generate draft</button></div>
          <p className="text-xs text-blue-300">{item.language} · {item.framework} · {item.level}</p>
          <p className="text-xs text-slate-400">{item.objective}</p>
          <p className="text-xs text-slate-500 break-all">Output: {item.outputPath}</p>
        </div>)}
      </div>
      {plan.unsupported.length > 0 && <details className="text-xs text-amber-300"><summary>Sources without an adapter ({plan.unsupported.length})</summary>
        {plan.unsupported.map(source => <p key={source.path} className="mt-1">{source.path}: {source.reason}</p>)}</details>}
      <h3 className="font-semibold">Saved drafts (latest 100)</h3>
      <p className="text-xs text-slate-400">Generation checks alone do not prove syntax, imports, correctness or safety. Execution is separate and can take up to 75 seconds. Mock scaffolds and stale drafts cannot run.</p>
      {drafts.map(draft => <details key={draft.id} className="border border-slate-800 rounded p-3">
        <summary className="text-sm cursor-pointer break-all">{draft.result.plan.outputPath} · {draft.result.status}{draft.snapshotId !== plan.snapshotId ? ' · OLD SNAPSHOT' : ''}</summary>
        <p className="text-xs text-slate-400 my-2">{draft.result.generated.explanation}</p>
        <p className="text-xs text-slate-400 my-2">Generation checks: {draft.result.checks.join(', ')}</p>
        <pre className="code-block">{draft.result.generated.fullTestCode}</pre>
        <div className="flex gap-3 my-3">
          <button className="btn-primary text-xs" disabled={busy || draft.result.status !== 'STRUCTURALLY_VALIDATED' || draft.snapshotId !== plan.snapshotId}
            onClick={() => execution(draft, true)}>Execute / retry in container</button>
          <button className="btn-secondary text-xs" disabled={busy} onClick={() => execution(draft, false)}>Load last execution</button>
        </div>
        {executions[draft.id] && <div className="space-y-2 text-xs">
          <p className="font-semibold">{executions[draft.id].result?.outcome || executions[draft.id].status} · container-only · network disabled</p>
          {executions[draft.id].result && <>
            <p>Exit code: {executions[draft.id].result.exitCode ?? 'not available'} · Reported cases: {executions[draft.id].result.tests.length}</p>
            <pre className="code-block">{executions[draft.id].result.output}</pre>
            {executions[draft.id].result.tests.map((test, index) => <div key={`${test.name}:${index}`} className="border-t border-slate-800 py-2">
              <p>{test.status} · {test.name} · {test.seconds}s</p><pre className="whitespace-pre-wrap">{test.message}</pre>
            </div>)}
          </>}
        </div>}
      </details>)}
    </>}
  </section>;
}
