import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { api } from '../api/client';
import { AlertTriangle, CheckCircle, XCircle, ShieldAlert, GitCompare, ArrowLeft } from 'lucide-react';

export default function FailureDetails() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [analysis, setAnalysis] = useState(null);
  const [fix, setFix] = useState(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);

  useEffect(() => {
    loadFailureData();
  }, [id]);

  const loadFailureData = async () => {
    try {
      // 1. Analyze failure if not yet analyzed, or get analysis
      let faData;
      try {
        faData = await api.getFailureAnalysis(id);
      } catch (err) {
        faData = await api.analyzeFailure(id);
      }
      setAnalysis(faData);

      // 2. Get fix suggestion
      if (faData) {
        const fixData = await api.getFixSuggestion(faData.id);
        setFix(fixData);
      }
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  };

  const handleAcceptFix = async () => {
    if (!fix) return;
    setActionLoading(true);
    try {
      const updated = await api.acceptFixSuggestion(fix.id);
      setFix(updated);
    } catch (err) {
      alert(err.message);
    } finally {
      setActionLoading(false);
    }
  };

  const handleRejectFix = async () => {
    if (!fix) return;
    setActionLoading(true);
    try {
      const updated = await api.rejectFixSuggestion(fix.id);
      setFix(updated);
    } catch (err) {
      alert(err.message);
    } finally {
      setActionLoading(false);
    }
  };

  if (loading) return <div className="text-center py-12 text-slate-400">Analyzing test failure with AI...</div>;

  return (
    <div className="max-w-6xl mx-auto p-6 space-y-6">
      <button onClick={() => navigate(-1)} className="btn-secondary text-xs">
        <ArrowLeft className="w-4 h-4" /> Back to Test Run
      </button>

      {/* Failure Analysis Summary Card */}
      <div className="card space-y-4 border-red-900/50 bg-red-950/10">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <ShieldAlert className="w-8 h-8 text-red-400" />
            <div>
              <h1 className="text-xl font-bold text-white">AI Failure Analysis</h1>
              <p className="text-xs text-slate-400">TestResult ID #{id}</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-xs font-mono px-2 py-1 bg-red-950 text-red-300 border border-red-800 rounded">
              Severity: {analysis?.severity || 'HIGH'}
            </span>
            <span className="text-xs font-mono px-2 py-1 bg-blue-950 text-blue-300 border border-blue-800 rounded">
              Confidence: {((analysis?.confidence || 0.9) * 100).toFixed(0)}%
            </span>
          </div>
        </div>

        <div className="space-y-2 pt-2 border-t border-slate-800">
          <div className="text-sm font-semibold text-slate-300">
            <span className="text-slate-500 uppercase text-xs font-bold block mb-1">Root Cause:</span>
            {analysis?.rootCause}
          </div>
          <div className="text-sm text-slate-400">
            <span className="text-slate-500 uppercase text-xs font-bold block mb-1">Affected Method:</span>
            <code className="text-blue-400 font-mono">{analysis?.affectedMethod}</code>
          </div>
          <div className="text-sm text-slate-300 bg-slate-900 p-3 border border-slate-800 rounded">
            <span className="text-slate-500 uppercase text-xs font-bold block mb-1">Diagnostic Explanation:</span>
            {analysis?.explanation}
          </div>
        </div>
      </div>

      {/* Human-in-the-Loop Fix Suggestion & Diff View */}
      {fix && (
        <div className="card space-y-4">
          <div className="flex items-center justify-between border-b border-slate-800 pb-3">
            <h2 className="text-lg font-bold flex items-center gap-2">
              <GitCompare className="w-5 h-5 text-emerald-400" /> AI Remediation Code Suggestion
            </h2>
            <div className="flex items-center gap-3">
              <span className={`px-3 py-1 text-xs font-mono rounded font-semibold border ${
                fix.status === 'ACCEPTED' ? 'bg-emerald-950 border-emerald-800 text-emerald-400' :
                fix.status === 'REJECTED' ? 'bg-red-950 border-red-800 text-red-400' :
                'bg-amber-950 border-amber-800 text-amber-400'
              }`}>
                Review Status: {fix.status}
              </span>
            </div>
          </div>

          <p className="text-sm text-slate-300">{fix.explanation}</p>

          {/* Diff View Grid */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <div>
              <span className="text-xs font-bold uppercase text-red-400 mb-1 block">Original Source Code</span>
              <pre className="code-block border-red-900/50 bg-red-950/10 text-red-300">{fix.originalCode}</pre>
            </div>
            <div>
              <span className="text-xs font-bold uppercase text-emerald-400 mb-1 block">Suggested Fix Code</span>
              <pre className="code-block border-emerald-900/50 bg-emerald-950/10 text-emerald-300">{fix.suggestedCode}</pre>
            </div>
          </div>

          {/* Human Review Action Buttons */}
          {fix.status === 'PENDING' && (
            <div className="pt-4 border-t border-slate-800 flex items-center justify-end gap-3">
              <button
                onClick={handleRejectFix}
                disabled={actionLoading}
                className="btn-danger flex items-center gap-2"
              >
                <XCircle className="w-4 h-4" /> Reject Fix
              </button>
              <button
                onClick={handleAcceptFix}
                disabled={actionLoading}
                className="btn-success flex items-center gap-2"
              >
                <CheckCircle className="w-4 h-4" /> Accept & Apply Fix
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
