import { useState } from 'react';
import { api } from '../api/client';

export default function TestReportView({ projectId, draftId }) {
  const [report, setReport] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  async function load(download = false) {
    setBusy(true);
    setError('');
    try {
      const data = await api.getDraftReport(projectId, draftId, download);
      setReport(data);
      if (download) {
        const url = URL.createObjectURL(new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' }));
        const link = document.createElement('a');
        link.href = url;
        link.download = `testpilot-project-${projectId}-draft-${draftId}.json`;
        document.body.appendChild(link);
        link.click();
        link.remove();
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }
    } catch (err) { setError(err.message); }
    finally { setBusy(false); }
  }

  return <section className="border-t border-slate-700 pt-3 space-y-3 text-sm" aria-label="Test report">
    <div className="flex flex-wrap gap-3">
      <button className="btn-secondary text-xs" disabled={busy} onClick={() => load()}>View test report</button>
      <button className="btn-secondary text-xs" disabled={busy} onClick={() => load(true)}>Download JSON report</button>
    </div>
    {error && <p role="alert" className="text-red-300">{error}</p>}
    {report && <>
      <p className="font-semibold">{report.summary.classification === 'NONE' ? 'All executed cases passed' : report.summary.classification}</p>
      <p>{report.summary.passed} passed · {report.summary.failed} failed · {report.summary.errors} errors · {report.summary.skipped} skipped</p>
      <p className="text-slate-300">{report.summary.nextStep}</p>
      <p className="text-xs text-slate-400 break-all">Attempt: {report.executionStartedAt} · Snapshot: {report.snapshotId}</p>
      <div className="rounded border border-slate-700 p-3 space-y-1">
        <h4 className="font-semibold">Selected-source line coverage</h4>
        <p className="break-all text-xs">{report.coverage.sourcePath}</p>
        <p>{report.coverage.linePercent == null ? report.coverage.status.replaceAll('_', ' ') : `${report.coverage.linePercent}% (${report.coverage.coveredLines}/${report.coverage.totalLines} executable lines)`}</p>
        <p className="text-xs text-slate-400">{report.coverage.tool || 'No measurement'} · {report.coverage.note}</p>
        {report.coverage.missingLines.length > 0 && <details><summary>Uncovered lines ({report.coverage.missingLines.length})</summary><p className="break-words text-xs">{report.coverage.missingLines.join(', ')}</p></details>}
      </div>
      {report.failures.map((failure, index) => <div key={index} className="border-l-2 border-red-400 pl-3 space-y-1">
        <p className="font-semibold">{failure.category} · {failure.testName}</p>
        <p>{failure.guidance}</p>
        <pre className="code-block whitespace-pre-wrap">{failure.evidence}</pre>
      </div>)}
      <h4 className="font-semibold">Source suggestions and test quality</h4>
      {report.findings.length === 0 && <p className="text-slate-400">No findings from the current evidence and checks. This does not establish correctness.</p>}
      {report.findings.map(finding => <div key={finding.id} className="rounded border border-slate-800 p-3 space-y-1">
        <p className="font-semibold">{finding.title}</p>
        <p className="text-xs text-amber-300">{finding.category} · {finding.severity} · {finding.basis}</p>
        <p>{finding.guidance}</p>
        {finding.location && <><p className="text-xs break-all">{finding.location.path}{finding.location.line ? `:${finding.location.line}` : ''}</p>
          {finding.location.snippet && <pre className="code-block whitespace-pre-wrap">{finding.location.snippet}</pre>}</>}
      </div>)}
      <details className="text-xs text-slate-400"><summary>Evidence and limitations</summary>
        <p className="my-2 break-all">Source SHA-256: {report.source.sha256} · Method: {report.analysisMethod}</p>
        <ul className="list-disc pl-5 space-y-1">{report.limitations.map(note => <li key={note}>{note}</li>)}</ul>
      </details>
    </>}
  </section>;
}
