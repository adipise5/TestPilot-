import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { api, getUser } from '../api/client';
import { FolderGit2, CheckCircle2, AlertTriangle, Cpu, Plus } from 'lucide-react';

export default function Dashboard() {
  const user = getUser();
  const [projects, setProjects] = useState([]);
  const [loading, setLoading] = useState(true);

  const loadDashboardData = useCallback(async () => {
    try {
      const data = await api.getProjects();
      setProjects(data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDashboardData();
  }, [loadDashboardData]);

  return (
    <div className="max-w-6xl mx-auto p-6 space-y-8">
      {/* Header Banner */}
      <div className="flex items-center justify-between card bg-gradient-to-r from-slate-900 to-slate-800">
        <div>
          <h1 className="text-3xl font-bold text-white">Welcome back, {user?.name}!</h1>
          <p className="text-slate-400 mt-1">
            TestPilot AI Platform • Active Profile: <span className="text-blue-400 font-mono">{user?.role}</span>
          </p>
        </div>
        <Link to="/projects" className="btn-primary">
          <Plus className="w-4 h-4" /> New Project
        </Link>
      </div>

      {/* Metrics Row */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="card flex items-center gap-4">
          <div className="p-3 bg-blue-950 border border-blue-800 rounded-lg text-blue-400">
            <FolderGit2 className="w-6 h-6" />
          </div>
          <div>
            <div className="text-2xl font-bold">{projects.length}</div>
            <div className="text-xs text-slate-400 font-medium">Total Projects</div>
          </div>
        </div>

        <div className="card flex items-center gap-4">
          <div className="p-3 bg-purple-950 border border-purple-800 rounded-lg text-purple-400">
            <Cpu className="w-6 h-6" />
          </div>
          <div>
            <div className="text-2xl font-bold">100%</div>
            <div className="text-xs text-slate-400 font-medium">AI Agent Availability</div>
          </div>
        </div>

        <div className="card flex items-center gap-4">
          <div className="p-3 bg-emerald-950 border border-emerald-800 rounded-lg text-emerald-400">
            <CheckCircle2 className="w-6 h-6" />
          </div>
          <div>
            <div className="text-2xl font-bold">Passed</div>
            <div className="text-xs text-slate-400 font-medium">Surefire Test Runner</div>
          </div>
        </div>

        <div className="card flex items-center gap-4">
          <div className="p-3 bg-amber-950 border border-amber-800 rounded-lg text-amber-400">
            <AlertTriangle className="w-6 h-6" />
          </div>
          <div>
            <div className="text-2xl font-bold">Active</div>
            <div className="text-xs text-slate-400 font-medium">RAG Vector Search</div>
          </div>
        </div>
      </div>

      {/* Projects List */}
      <div className="card space-y-4">
        <div className="flex items-center justify-between border-b border-slate-800 pb-4">
          <h2 className="text-xl font-bold flex items-center gap-2">
            <FolderGit2 className="w-5 h-5 text-blue-400" />
            Your Projects
          </h2>
          <span className="text-xs text-slate-400 font-mono">{projects.length} Registered</span>
        </div>

        {loading ? (
          <div className="text-center py-8 text-slate-400">Loading projects...</div>
        ) : projects.length === 0 ? (
          <div className="text-center py-12 text-slate-400 space-y-3">
            <p>No projects found. Create your first Java project to start automated AI testing!</p>
            <Link to="/projects" className="btn-primary">Create Project</Link>
          </div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            {projects.map((proj) => (
              <div key={proj.id} className="p-4 bg-slate-900 border border-slate-800 rounded-lg space-y-3 hover:border-slate-700 transition-colors">
                <div className="flex items-center justify-between">
                  <h3 className="font-bold text-lg text-white">{proj.name}</h3>
                  <span className="text-xs px-2 py-0.5 bg-blue-950 text-blue-400 border border-blue-800 rounded font-mono">
                    ID #{proj.id}
                  </span>
                </div>
                <p className="text-sm text-slate-400 line-clamp-2">{proj.description || 'No description provided.'}</p>
                <div className="pt-2 flex items-center justify-between border-t border-slate-800 text-xs text-slate-500">
                  <span>Owner ID: #{proj.ownerId}</span>
                  <Link to={`/projects/${proj.id}`} className="btn-secondary text-xs">
                    View Project & Run Tests →
                  </Link>
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
