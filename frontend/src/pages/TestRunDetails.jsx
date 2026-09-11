import { useCallback, useEffect, useState } from 'react';
import { useParams, Link } from 'react-router-dom';
import { api } from '../api/client';
import { CheckCircle2, XCircle, Code, ArrowRight } from 'lucide-react';

export default function TestRunDetails() {
  const { id } = useParams();
  const [testRun, setTestRun] = useState(null);
  const [loading, setLoading] = useState(true);

  const loadTestRun = useCallback(async () => {
    try {
      const data = await api.getTestRun(id);
      setTestRun(data);
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

    if (testRun.status !== 'COMPLETED' && testRun.status !== 'FAILED') {
      const timer = setTimeout(() => {
        loadTestRun();
      }, 1000);
      return () => clearTimeout(timer);
    }
  }, [testRun, loadTestRun]);

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
            testRun.status === 'FAILED' ? 'bg-red-950 border-red-800 text-red-400' :
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
          <div className={`p-2 rounded border ${['ANALYZING', 'GENERATING_TESTS', 'RUNNING_TESTS', 'ANALYZING_FAILURES', 'COMPLETED'].includes(testRun.status) ? 'bg-blue-950 border-blue-800 text-blue-300' : 'bg-slate-900 border-slate-800 text-slate-600'}`}>
            2. ANALYZING
          </div>
          <div className={`p-2 rounded border ${['GENERATING_TESTS', 'RUNNING_TESTS', 'ANALYZING_FAILURES', 'COMPLETED'].includes(testRun.status) ? 'bg-blue-950 border-blue-800 text-blue-300' : 'bg-slate-900 border-slate-800 text-slate-600'}`}>
            3. AI GEN
          </div>
          <div className={`p-2 rounded border ${['RUNNING_TESTS', 'ANALYZING_FAILURES', 'COMPLETED'].includes(testRun.status) ? 'bg-blue-950 border-blue-800 text-blue-300' : 'bg-slate-900 border-slate-800 text-slate-600'}`}>
            4. MVN TEST
          </div>
          <div className={`p-2 rounded border ${testRun.status === 'COMPLETED' ? 'bg-emerald-950 border-emerald-800 text-emerald-400' : 'bg-slate-900 border-slate-800 text-slate-600'}`}>
            5. DONE
          </div>
        </div>
      </div>

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

      {/* Generated JUnit Test Code */}
      {testRun.generatedTests && testRun.generatedTests.length > 0 && (
        <div className="card space-y-3">
          <h2 className="text-lg font-bold flex items-center gap-2 border-b border-slate-800 pb-3">
            <Code className="w-5 h-5 text-blue-400" /> AI Generated JUnit 5 Test Code
          </h2>
          {testRun.generatedTests.map((gt) => (
            <div key={gt.id} className="space-y-2">
              <div className="flex items-center justify-between text-xs text-slate-400 font-mono">
                <span>Class: {gt.testClass}</span>
                <span>Source: {gt.sourceFile}</span>
              </div>
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
