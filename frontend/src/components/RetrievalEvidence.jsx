export default function RetrievalEvidence({ context }) {
  if (!context) return null;
  return <details className="text-xs text-slate-400 space-y-2">
    <summary className="cursor-pointer">Related context and language standards ({context.snippets.length} excerpts)</summary>
    <p className="break-all">{context.indexVersion} · Snapshot: {context.snapshotId}</p>
    <p>{context.candidates} candidates · {context.usedCharacters} source characters supplied · {context.standardsVersion}</p>
    {context.snippets.length === 0 && <p>No related symbols matched within the retrieval limits.</p>}
    {context.snippets.map((snippet, index) => <div key={index} className="border border-slate-800 rounded p-2 space-y-1">
      <p className="font-mono break-all">{snippet.path}:{snippet.startLine}–{snippet.endLine} · {snippet.symbol}</p>
      <p>{snippet.reason}</p><pre className="code-block whitespace-pre-wrap">{snippet.content}</pre>
      <p className="break-all">Source SHA-256: {snippet.sourceHash}</p>
    </div>)}
    {context.standards.map(rule => <div key={rule.id} className="space-y-1">
      <p><a className="text-blue-300 underline" href={rule.sourceUrl} target="_blank" rel="noreferrer">{rule.id}</a> · {rule.language} · {rule.category}</p>
      <p>{rule.guidance}</p>
    </div>)}
    <ul className="list-disc pl-5">{context.limitations.map(note => <li key={note}>{note}</li>)}</ul>
  </details>;
}
