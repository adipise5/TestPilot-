import { useCallback, useEffect, useState } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import { api } from '../api/client';
import { FileCode, Play, Cpu, Plus, Clock } from 'lucide-react';

export default function ProjectDetails() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [project, setProject] = useState(null);
  const [files, setFiles] = useState([]);
  const [testRuns, setTestRuns] = useState([]);
  const [analysis, setAnalysis] = useState(null);
  const [loading, setLoading] = useState(true);
  const [showFileModal, setShowFileModal] = useState(false);
  const [fileName, setFileName] = useState('');
  const [filePath, setFilePath] = useState('');
  const [content, setContent] = useState('');
  const [actionLoading, setActionLoading] = useState(false);

  const loadProjectData = useCallback(async () => {
    try {
      const [projData, fileData, runData] = await Promise.all([
        api.getProject(id),
        api.getCodeFiles(id),
        api.getProjectTestRuns(id),
      ]);
      setProject(projData);
      setFiles(fileData);
      setTestRuns(runData);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, [id]);

  useEffect(() => {
    loadProjectData();
  }, [loadProjectData]);

  const handleAddFile = async (e) => {
    e.preventDefault();
    try {
      await api.addCodeFile(id, { fileName, filePath, content });
      setFileName('');
      setFilePath('');
      setContent('');
      setShowFileModal(false);
      loadProjectData();
    } catch (err) {
      alert(err.message);
    }
  };

  const handleAnalyze = async () => {
    setActionLoading(true);
    try {
      const res = await api.analyzeProject(id);
      setAnalysis(res);
    } catch (err) {
      alert(err.message);
    } finally {
      setActionLoading(false);
    }
  };

  const handleStartAutoRun = async () => {
    setActionLoading(true);
    try {
      const res = await api.startAutomatedTestRun(id);
      navigate(`/test-runs/${res.id}`);
    } catch (err) {
      alert(err.message);
      setActionLoading(false);
    }
  };

  if (loading) return <div className="text-center py-12 text-slate-400">Loading project...</div>;

  return (
    <div className="max-w-6xl mx-auto p-6 space-y-6">
      {/* Header */}
      <div className="card space-y-3">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-bold">{project?.name}</h1>
          <div className="flex gap-2">
            <button onClick={handleAnalyze} disabled={actionLoading || files.length === 0} className="btn-secondary">
              <Cpu className="w-4 h-4 text-purple-400" /> AI Code Analysis
            </button>
            <button onClick={handleStartAutoRun} disabled={actionLoading || files.length === 0} className="btn-primary">
              <Play className="w-4 h-4" /> Run AI Test Orchestrator
            </button>
          </div>
        </div>
        <p className="text-sm text-slate-400">{project?.description || 'No description'}</p>
      </div>

      {/* Code Analysis Result */}
      {analysis && (
        <div className="card border-purple-900/50 bg-purple-950/20 space-y-3">
          <h2 className="text-lg font-bold text-purple-400 flex items-center gap-2">
            <Cpu className="w-5 h-5" /> Code Analysis Diagnosis
          </h2>
          <p className="text-sm text-slate-300">{analysis.summary}</p>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4 text-xs">
            <div>
              <span className="font-bold text-slate-400 uppercase block mb-1">Detected Edge Cases:</span>
              <ul className="list-disc list-inside text-slate-300 space-y-1">
                {analysis.edgeCases.map((ec, idx) => <li key={idx}>{ec}</li>)}
              </ul>
            </div>
            <div>
              <span className="font-bold text-slate-400 uppercase block mb-1">Testing Recommendations:</span>
              <ul className="list-disc list-inside text-slate-300 space-y-1">
                {analysis.testingRecommendations.map((tr, idx) => <li key={idx}>{tr}</li>)}
              </ul>
            </div>
          </div>
        </div>
      )}

      {/* Code Files List */}
      <div className="card space-y-4">
        <div className="flex items-center justify-between border-b border-slate-800 pb-3">
          <h2 className="text-lg font-bold flex items-center gap-2">
            <FileCode className="w-5 h-5 text-blue-400" /> Source Files ({files.length})
          </h2>
          <button onClick={() => setShowFileModal(true)} className="btn-secondary text-xs">
            <Plus className="w-3.5 h-3.5" /> Upload File
          </button>
        </div>

        {files.length === 0 ? (
          <div className="text-center py-8 text-slate-500 text-sm">
            No source code files uploaded yet. Add a Java class file to enable test generation.
          </div>
        ) : (
          <div className="space-y-4">
            {files.map((file) => (
              <div key={file.id} className="p-4 bg-slate-900 border border-slate-800 rounded-lg space-y-2">
                <div className="flex items-center justify-between">
                  <span className="font-mono text-sm font-semibold text-blue-400">{file.filePath}</span>
                  <span className="text-xs text-slate-500 font-mono">{file.fileName}</span>
                </div>
                <pre className="code-block">{file.content}</pre>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* File Upload Modal */}
      {showFileModal && (
        <div className="fixed inset-0 bg-black/70 flex items-center justify-center p-4 z-50">
          <div className="card max-w-2xl w-full space-y-4">
            <h2 className="text-xl font-bold">Upload Java Source File</h2>
            <form onSubmit={handleAddFile} className="space-y-4">
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <label className="block text-xs font-semibold uppercase text-slate-400 mb-1">File Name</label>
                  <input
                    type="text"
                    required
                    value={fileName}
                    onChange={(e) => setFileName(e.target.value)}
                    className="input-field"
                    placeholder="Calculator.java"
                  />
                </div>
                <div>
                  <label className="block text-xs font-semibold uppercase text-slate-400 mb-1">Relative File Path</label>
                  <input
                    type="text"
                    required
                    value={filePath}
                    onChange={(e) => setFilePath(e.target.value)}
                    className="input-field"
                    placeholder="src/main/java/com/example/Calculator.java"
                  />
                </div>
              </div>
              <div>
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1">Java Source Code</label>
                <textarea
                  required
                  value={content}
                  onChange={(e) => setContent(e.target.value)}
                  className="input-field font-mono text-xs h-48"
                  placeholder="package com.example;&#10;&#10;public class Calculator {&#10;    public int add(int a, int b) { return a + b; }&#10;}"
                />
              </div>
              <div className="flex justify-end gap-3">
                <button type="button" onClick={() => setShowFileModal(false)} className="btn-secondary">Cancel</button>
                <button type="submit" className="btn-primary">Save File</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Test Runs History */}
      <div className="card space-y-4">
        <h2 className="text-lg font-bold border-b border-slate-800 pb-3 flex items-center gap-2">
          <Clock className="w-5 h-5 text-amber-400" /> Test Run Executions ({testRuns.length})
        </h2>
        {testRuns.length === 0 ? (
          <div className="text-center py-6 text-slate-500 text-sm">No test runs executed yet.</div>
        ) : (
          <div className="space-y-2">
            {testRuns.map((run) => (
              <div key={run.id} className="p-3 bg-slate-900 border border-slate-800 rounded flex items-center justify-between">
                <div className="flex items-center gap-3">
                  <span className={`px-2 py-0.5 text-xs font-mono rounded border ${
                    run.status === 'COMPLETED' ? 'bg-emerald-950 border-emerald-800 text-emerald-400' :
                    run.status === 'FAILED' ? 'bg-red-950 border-red-800 text-red-400' :
                    'bg-amber-950 border-amber-800 text-amber-400'
                  }`}>
                    {run.status}
                  </span>
                  <span className="text-sm font-semibold">TestRun #{run.id}</span>
                </div>
                <Link to={`/test-runs/${run.id}`} className="btn-secondary text-xs">
                  View Results →
                </Link>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
