import { useState } from 'react';
import { api } from '../api/client';

export default function CodeReviewer({ projectId }) {
  const [plan, setPlan] = useState(null);
  const [history, setHistory] = useState([]);
  const [review, setReview] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [kind, setKind] = useState('ALL');
  const [category, setCategory] = useState('ALL');
  const [severity, setSeverity] = useState('ALL');

  async function load() {
    setBusy(true); setError('');
    try {
      setHistory(await api.getCodeReviews(projectId));
      setPlan(await api.getCodeReviewPlan(projectId));
    } catch (err) { setPlan(null); setError(err.message); }
    finally { setBusy(false); }
  }
  async function start(offset = 0) {
    setBusy(true); setError('');
    try {
      const result = await api.startCodeReview(projectId, plan.snapshotId, offset);
      setReview(result);
      setHistory(await api.getCodeReviews(projectId));
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }
  async function show(id, download = false) {
    setBusy(true); setError('');
    try {
      const result = await api.getCodeReview(projectId, id, download);
      setReview(result);
      if (download) {
        const url = URL.createObjectURL(new Blob([JSON.stringify(result, null, 2)], { type: 'application/json' }));
        const link = document.createElement('a');
        link.href = url; link.download = `testpilot-review-${id}.json`;
        document.body.appendChild(link); link.click(); link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }
  const report = review?.report;
  const findings = report?.findings.filter(f => (kind === 'ALL' || f.kind === kind)
    && (category === 'ALL' || f.category === category) && (severity === 'ALL' || f.severity === severity)) || [];
  const reviewed = report?.files.filter(f => f.status.startsWith('REVIEWED')).length || 0;

  return <section className="card space-y-4" aria-label="AI Code Reviewer">
    <div className="flex flex-wrap justify-between gap-3 items-center">
      <h2 className="text-lg font-bold">AI Code Reviewer</h2>
      <button className="btn-secondary text-xs" disabled={busy} onClick={load}>Load scope and history</button>
    </div>
    <p className="text-sm text-slate-400">Review repository code for good practices and improvements in security, performance, correctness, maintainability, and testability. Findings include checked source citations and implementation guidance. Review does not modify or execute your code.</p>
    {error && <p role="alert" className="text-red-300 text-sm">{error}</p>}
    {busy && <p role="status" className="text-blue-300 text-sm">Working… AI review may take up to six minutes. Keep this page open; saved history can be reloaded.</p>}
    {plan && <div className="space-y-2 text-sm">
      <p>{plan.files.length} catalog files · {plan.files.filter(f => f.status === 'PENDING').length} eligible · {plan.totalBatches} batches · Provider: {plan.provider}</p>
      <p className="text-xs text-slate-400 break-all">Snapshot: {plan.snapshotId} · Revision: {plan.commitSha}</p>
      {plan.provider === 'mock' && <p className="text-amber-300">Mock mode cannot perform an AI review. Configure a real provider to obtain findings; a mock request records an unavailable result.</p>}
      <button className="btn-primary text-xs" disabled={busy || plan.totalBatches === 0} onClick={() => start()}>Start code review</button>
      <details className="text-xs text-slate-400"><summary>Scope and exclusions</summary>
        <div className="max-h-64 overflow-auto space-y-1 mt-2">{plan.files.map(f => <p key={f.path} className="break-all">{f.path} · {f.language} · {f.status} · {f.reason}</p>)}</div>
      </details>
    </div>}
    {history.length > 0 && <details open><summary className="text-sm">Saved reviews (latest 50)</summary>
      <div className="max-h-48 overflow-auto space-y-2 mt-2">{history.map(item => <div key={item.id} className="flex flex-wrap gap-3 items-center text-xs">
        <button className="btn-secondary text-xs" disabled={busy} onClick={() => show(item.id)}>Review #{item.id}</button>
        <span>{item.status} · {item.startedAt}</span>
      </div>)}</div>
    </details>}
    {report && <div className="border-t border-slate-700 pt-4 space-y-3">
      <div className="flex flex-wrap gap-3 justify-between items-center">
        <h3 className="font-semibold">Review #{review.id} · {review.status}</h3>
        <button className="btn-secondary text-xs" disabled={busy} onClick={() => show(review.id, true)}>Download review JSON</button>
      </div>
      <p className="text-sm">{reviewed}/{report.files.length} catalog files acknowledged as reviewed · {report.findings.filter(f => f.kind === 'GOOD_PRACTICE').length} good practices · {report.findings.filter(f => f.kind === 'IMPROVEMENT').length} improvements</p>
      <p className="text-xs text-slate-400 break-all">{report.provider} · {report.model} · Snapshot: {report.snapshotId}</p>
      {plan && plan.snapshotId !== report.snapshotId && <p className="text-amber-300 text-sm">This saved review belongs to an older snapshot.</p>}
      <p className="text-xs text-amber-300">{report.rejectedFindings} findings rejected by validation · {report.failedBatches} failed batches. Verified snippets do not prove that the AI conclusion is correct.</p>
      {report.nextBatchOffset != null && <button className="btn-primary text-xs" disabled={busy || !plan || plan.snapshotId !== report.snapshotId}
        onClick={() => start(report.nextBatchOffset)}>Review next batches ({report.nextBatchOffset + 1}–{Math.min(report.nextBatchOffset + (plan?.batchesPerReview || 8), report.totalBatches)})</button>}
      <div className="flex flex-wrap gap-2">
        <select aria-label="Finding kind" className="input-field" value={kind} onChange={e => setKind(e.target.value)}>
          <option value="ALL">All findings</option><option value="IMPROVEMENT">Improvements</option><option value="GOOD_PRACTICE">Good practices</option>
        </select>
        <select aria-label="Finding category" className="input-field" value={category} onChange={e => setCategory(e.target.value)}>
          {['ALL', 'SECURITY', 'PERFORMANCE', 'CORRECTNESS', 'MAINTAINABILITY', 'TESTABILITY'].map(value => <option key={value} value={value}>{value === 'ALL' ? 'All categories' : value}</option>)}
        </select>
        <select aria-label="Finding severity" className="input-field" value={severity} onChange={e => setSeverity(e.target.value)}>
          {['ALL', 'CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'].map(value => <option key={value} value={value}>{value === 'ALL' ? 'All severities' : value}</option>)}
        </select>
      </div>
      {findings.length === 0 && <p className="text-sm text-slate-400">No validated findings match this view. Check review status and file scope; this is not a clean bill of health.</p>}
      {findings.map((finding, index) => <article key={index} className="border border-slate-700 rounded p-3 space-y-2 text-sm">
        <p className={finding.kind === 'GOOD_PRACTICE' ? 'text-emerald-300 text-xs' : 'text-amber-300 text-xs'}>{finding.kind} · {finding.category} · {finding.severity}</p>
        <h4 className="font-semibold">{finding.title}</h4>
        <p>{finding.explanation}</p><p className="text-slate-300">{finding.guidance}</p>
        {finding.evidence.map((e, i) => <div key={i}><p className="font-mono text-xs break-all">{e.path}:{e.startLine}–{e.endLine}</p><pre className="code-block whitespace-pre-wrap">{e.snippet}</pre></div>)}
      </article>)}
      <details className="text-xs"><summary>Reviewed, deferred, failed and excluded files</summary>
        <div className="max-h-64 overflow-auto space-y-2 mt-2">{report.files.map(f => <p key={f.path} className="break-all">{f.path} · {f.status} · {f.reason}</p>)}</div>
      </details>
      <details className="text-xs text-slate-400"><summary>Review limitations</summary><ul className="list-disc pl-5 space-y-1 mt-2">{report.limitations.map(note => <li key={note}>{note}</li>)}</ul></details>
    </div>}
  </section>;
}
