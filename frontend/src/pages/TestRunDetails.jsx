import { useCallback, useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { api } from '../api/client';
import { CheckCircle2, XCircle, Code, ArrowRight, GitBranch, ShieldCheck, Database, Ban } from 'lucide-react';

export default function TestRunDetails() {
  const { id } = useParams();
  const [testRun, setTestRun] = useState(null);
  const [workflow, setWorkflow] = useState(null);
  const [ragTraces, setRagTraces] = useState([]);
  const [loading, setLoading] = useState(true);
  const [decisionPending, setDecisionPending] = useState(false);

  const loadTestRun = useCallback(async () => {
    try {
      const data = await api.getTestRun(id);
      setTestRun(data);
      try {
        setWorkflow(await api.getWorkflowTrace(id));
      } catch {
        setWorkflow(null);
      }
      try {
        setRagTraces(await api.getRagTraces(id));
      } catch {
        setRagTraces([]);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    loadTestRun();
  }, [loadTestRun]);

  // Polling loop for active test runs
  useEffect(() => {
    if (!testRun) return;

    if (!['COMPLETED', 'FAILED', 'REJECTED'].includes(testRun.status)) {
      const timer = setTimeout(() => {
        loadTestRun();
      }, 1000);
      return () => clearTimeout(timer);
    }
  }, [testRun, loadTestRun]);

  const decideWorkflow = async (approved) => {
    setDecisionPending(true);
    try {
      const updated = await api.decideWorkflow(
        id,
        approved,
        approved ? 'Approved from TestPilot review screen' : 'Rejected from TestPilot review screen',
      );
      setWorkflow(updated);
      await loadTestRun();
    } catch (err) {
      console.error(err);
    } finally {
      setDecisionPending(false);
    }
  };

  const cancelExecution = async () => {
    setDecisionPending(true);
    try {
      setTestRun(await api.cancelExecution(id));
    } catch (err) {
      console.error(err);
    } finally {
      setDecisionPending(false);
    }
  };

  if (loading) return <div className="text-center py-12 text-slate-400">Loading test run details...</div>;
  if (!testRun) return <div className="text-center py-12 text-red-400">TestRun not found.</div>;

  return (
    <div className="max-w-6xl mx-auto p-6 space-y-6">
      {/* Header */}
      <div className="card space-y-4">
        <div className="flex items-center justify-between">
          <div>
            <span className="text-xs text-slate-500 uppercase tracking-widest font-semibold block">TestRun #{testRun.id}</span>
            <h1 className="text-2xl font-bold flex items-center gap-3 mt-1">
              Project #{testRun.projectId} Test Execution
            </h1>
          </div>
          <span className={`px-3 py-1 text-sm font-mono rounded-full border font-semibold ${
            testRun.status === 'COMPLETED' ? 'bg-emerald-950 border-emerald-800 text-emerald-400' :
            ['FAILED', 'REJECTED'].includes(testRun.status) ? 'bg-red-950 border-red-800 text-red-400' :
            'bg-amber-950 border-amber-800 text-amber-400 animate-pulse'
          }`}>
            {testRun.status}
          </span>
        </div>

        {/* Progress Timeline */}
        <div className="grid grid-cols-5 gap-2 pt-2 text-xs font-mono text-center">
          <div className={`p-2 rounded border ${testRun.status !== 'PENDING' ? 'bg-blue-950 border-blue-800 text-blue-300' : 'bg-slate-900 border-slate-800 text-slate-600'}`}>
            1. PENDING
          </div>
          <div className={`p-2 rounded border ${!['PENDING', 'INTAKE'].includes(testRun.status) ? 'bg-blue-950 border-blue-800 text-blue-300' : 'bg-slate-900 border-slate-800 text-slate-600'}`}>
            2. MAP & PLAN
          </div>
          <div className={`p-2 rounded border ${['GENERATING_TESTS', 'GENERATING_UNIT_TESTS', 'GENERATING_MODULE_TESTS', 'GENERATING_INTEGRATION_TESTS', 'REVIEWING_TESTS', 'AWAITING_APPROVAL', 'RUNNING_TESTS', 'ANALYZING_FAILURES', 'REPORTING', 'COMPLETED'].includes(testRun.status) ? 'bg-blue-950 border-blue-800 text-blue-300' : 'bg-slate-900 border-slate-800 text-slate-600'}`}>
            3. SPECIALISTS
          </div>
          <div className={`p-2 rounded border ${['RUNNING_TESTS', 'ANALYZING_FAILURES', 'REPORTING', 'COMPLETED'].includes(testRun.status) ? 'bg-blue-950 border-blue-800 text-blue-300' : 'bg-slate-900 border-slate-800 text-slate-600'}`}>
            4. EXECUTE
          </div>
          <div className={`p-2 rounded border ${testRun.status === 'COMPLETED' ? 'bg-emerald-950 border-emerald-800 text-emerald-400' : 'bg-slate-900 border-slate-800 text-slate-600'}`}>
            5. DONE
          </div>
        </div>
      </div>

      {workflow && (
        <div className="card space-y-4">
          <div className="flex items-center justify-between gap-4">
            <h2 className="text-lg font-bold flex items-center gap-2">
              <GitBranch className="w-5 h-5 text-violet-400" /> LangGraph workflow
            </h2>
            <span className="px-2 py-1 rounded border border-slate-700 font-mono text-xs">
              {workflow.graphVersion}
            </span>
          </div>
          <div className="grid md:grid-cols-2 gap-3 text-xs font-mono text-slate-400">
            <div>Thread: <span className="text-slate-200">{workflow.threadId}</span></div>
            <div>Revision: <span className="text-slate-200 break-all">{workflow.commitSha || 'pending intake'}</span></div>
          </div>

          {workflow.status === 'WAITING_FOR_APPROVAL' && (
            <div className="p-4 rounded-lg border border-amber-800 bg-amber-950/40 space-y-3">
              <div className="flex gap-2 text-amber-300 font-semibold">
                <ShieldCheck className="w-5 h-5 shrink-0" /> Human approval required
              </div>
              <p className="text-sm text-amber-100">{workflow.approvalPrompt}</p>
              <p className="text-xs text-amber-300/80">
                Approval only resumes this checkpointed revision. Rejecting ends the workflow without execution.
              </p>
              <div className="flex gap-3">
                <button
                  className="btn-primary"
                  disabled={decisionPending}
                  onClick={() => decideWorkflow(true)}
                >
                  Approve and resume
                </button>
                <button
                  className="btn-danger"
                  disabled={decisionPending}
                  onClick={() => decideWorkflow(false)}
                >
                  Reject plan
                </button>
              </div>
            </div>
          )}

          <div className="space-y-2">
            <h3 className="text-sm font-semibold text-slate-300">Node trace</h3>
            {workflow.steps.length === 0 ? (
              <p className="text-sm text-slate-500">Waiting for the first graph node.</p>
            ) : workflow.steps.map((step) => (
              <div key={step.id} className="flex items-center justify-between gap-4 p-3 bg-slate-900 rounded border border-slate-800">
                <div>
                  <div className="font-mono text-sm text-slate-200">{step.node}</div>
                  <div className="text-xs text-slate-500">attempts: {step.attempts} · input: {step.inputHash.slice(0, 12)}</div>
                </div>
                <span className={`text-xs font-mono ${step.status === 'COMPLETED' ? 'text-emerald-400' : step.status === 'FAILED' ? 'text-red-400' : 'text-amber-400'}`}>
                  {step.status}
                </span>
              </div>
            ))}
          </div>

          {workflow.report && (
            <details>
              <summary className="cursor-pointer text-sm text-slate-400">Show evidence report</summary>
              <pre className="code-block mt-3 max-h-80 overflow-auto">{JSON.stringify(workflow.report, null, 2)}</pre>
            </details>
          )}
          {workflow.failureReason && <p className="text-sm text-red-400">{workflow.failureReason}</p>}
        </div>
      )}

      {testRun.executionOutcome && (
        <div className="card space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-lg font-bold">Execution outcome</h2>
            <span className="px-2 py-1 rounded border border-slate-700 font-mono text-sm">
              {testRun.executionOutcome}
              {testRun.processExitCode !== null && ` • exit ${testRun.processExitCode}`}
            </span>
          </div>
          {testRun.executionOutput && (
            <details>
              <summary className="cursor-pointer text-sm text-slate-400">Show bounded Maven output</summary>
              <pre className="code-block mt-3 max-h-80 overflow-auto">{testRun.executionOutput}</pre>
            </details>
          )}
        </div>
      )}

      {testRun.executionJob && (
        <div className="card space-y-4">
          <div className="flex items-center justify-between gap-4">
            <h2 className="text-lg font-bold flex items-center gap-2">
              <ShieldCheck className="w-5 h-5 text-cyan-400" /> Isolated execution job
            </h2>
            <span className="px-2 py-1 rounded border border-slate-700 font-mono text-xs">
              {testRun.executionJob.status}
            </span>
          </div>
          <div className="grid md:grid-cols-3 gap-3 text-sm">
            <div className="p-3 bg-slate-900 rounded border border-slate-800">
              <div className="text-xs text-slate-500">Backend</div>
              <div className="font-mono text-slate-200">{testRun.executionJob.isolationBackend || 'awaiting worker'}</div>
            </div>
            <div className="p-3 bg-slate-900 rounded border border-slate-800">
              <div className="text-xs text-slate-500">Attempts</div>
              <div className="font-mono text-slate-200">{testRun.executionJob.attempts}/{testRun.executionJob.maxAttempts}</div>
            </div>
            <div className="p-3 bg-slate-900 rounded border border-slate-800">
              <div className="text-xs text-slate-500">Last heartbeat</div>
              <div className="font-mono text-slate-200">{testRun.executionJob.heartbeatAt || 'not leased'}</div>
            </div>
          </div>
          <div className="grid md:grid-cols-2 gap-3 text-sm">
            <div>Line coverage: <span className="font-mono text-emerald-400">{testRun.executionJob.lineCoveragePercent ?? testRun.executionJob.coverageStatus}</span></div>
            <div>Mutation score: <span className="font-mono text-violet-400">{testRun.executionJob.mutationScorePercent ?? testRun.executionJob.mutationStatus}</span></div>
          </div>
          {['QUEUED', 'LEASED', 'RUNNING', 'RETRY_WAIT'].includes(testRun.executionJob.status) && (
            <button className="btn-danger flex items-center gap-2" disabled={decisionPending} onClick={cancelExecution}>
              <Ban className="w-4 h-4" /> Cancel execution
            </button>
          )}
        </div>
      )}

      {ragTraces.length > 0 && (
        <div className="card space-y-4">
          <h2 className="text-lg font-bold flex items-center gap-2">
            <Database className="w-5 h-5 text-fuchsia-400" /> RAG grounding evidence
          </h2>
          {ragTraces.map((trace) => (
            <details key={trace.id} className="p-3 bg-slate-900 rounded border border-slate-800">
              <summary className="cursor-pointer text-sm text-slate-300">
                Trace #{trace.id} · {trace.packedTokens} tokens · {trace.citations.length} citations
              </summary>
              <div className="mt-3 space-y-3">
                <div className="text-xs font-mono text-slate-500 break-all">query hash: {trace.queryHash}</div>
                <div className="text-sm text-slate-300">Query: {trace.queryText}</div>
                {trace.citations.map((citation) => (
                  <div key={citation.chunkKey} className="text-xs border-l-2 border-fuchsia-800 pl-3">
                    <div className="text-fuchsia-300">{citation.source}{citation.symbol ? ` · ${citation.symbol}` : ''}</div>
                    <div className="text-slate-500">lines {citation.startLine}-{citation.endLine} · score {citation.score.toFixed(3)}</div>
                    <div className="font-mono text-slate-600 break-all">{citation.uri}</div>
                  </div>
                ))}
                <details>
                  <summary className="cursor-pointer text-xs text-slate-500">Exact packed context</summary>
                  <pre className="code-block mt-2 max-h-80 overflow-auto">{trace.packedContext}</pre>
                </details>
              </div>
            </details>
          ))}
        </div>
      )}

      {/* Generated JUnit Test Code */}
      {testRun.generatedTests && testRun.generatedTests.length > 0 && (
        <div className="card space-y-3">
          <h2 className="text-lg font-bold flex items-center gap-2 border-b border-slate-800 pb-3">
            <Code className="w-5 h-5 text-blue-400" /> AI Generated JUnit 5 Test Code
          </h2>
          {testRun.generatedTests.map((gt) => (
            <div key={gt.id} className="space-y-2">
              <div className="flex items-center justify-between text-xs text-slate-400 font-mono">
                <span>{gt.testLevel || 'UNIT'} · Class: {gt.testClass}</span>
                <span>Source: {gt.sourceFile}</span>
              </div>
              {gt.ragTraceId && <div className="text-xs font-mono text-fuchsia-400">Grounded by RAG trace #{gt.ragTraceId}</div>}
              <pre className="code-block">{gt.testCode}</pre>
            </div>
          ))}
        </div>
      )}

      {/* Test Case Execution Results */}
      <div className="card space-y-4">
        <h2 className="text-lg font-bold border-b border-slate-800 pb-3 flex items-center justify-between">
          <span>Surefire Test Results ({testRun.testResults?.length || 0})</span>
        </h2>

        {(!testRun.testResults || testRun.testResults.length === 0) ? (
          <div className="text-center py-8 text-slate-500 text-sm">
            {testRun.status === 'COMPLETED' ? 'No tests executed.' : 'Test execution in progress...'}
          </div>
        ) : (
          <div className="space-y-3">
            {testRun.testResults.map((res) => (
              <div key={res.id} className="p-4 bg-slate-900 border border-slate-800 rounded-lg space-y-3">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-3 font-mono text-sm font-semibold">
                    {res.status === 'PASSED' ? (
                      <CheckCircle2 className="w-5 h-5 text-emerald-400" />
                    ) : (
                      <XCircle className="w-5 h-5 text-red-400" />
                    )}
                    <span className={res.status === 'PASSED' ? 'text-emerald-400' : 'text-red-400'}>
                      {res.testName}
                    </span>
                  </div>
                  <div className="flex items-center gap-3">
                    <span className="text-xs text-slate-500 font-mono">{res.executionTime}s</span>
                    {res.status === 'FAILED' && (
                      <Link to={`/failures/${res.id}`} className="btn-danger text-xs py-1 px-2.5 flex items-center gap-1">
                        View AI Fix <ArrowRight className="w-3.5 h-3.5" />
                      </Link>
                    )}
                  </div>
                </div>

                {res.errorMessage && (
                  <div className="p-3 bg-red-950/40 border border-red-900/50 rounded text-xs text-red-300 font-mono">
                    {res.errorMessage}
                  </div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
