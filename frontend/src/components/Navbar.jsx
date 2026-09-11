import { Link, useNavigate } from 'react-router-dom';
import { getUser, removeAuthToken } from '../api/client';
import { Terminal, FolderGit2, ShieldCheck, LogOut, BookOpen } from 'lucide-react';

export default function Navbar() {
  const navigate = useNavigate();
  const user = getUser();

  const handleLogout = () => {
    removeAuthToken();
    navigate('/login');
  };

  return (
    <nav className="bg-slate-900 border-b border-slate-800 px-6 py-4 flex items-center justify-between">
      <div className="flex items-center gap-8">
        <Link to="/" className="flex items-center gap-2 text-xl font-bold text-blue-400 no-underline">
          <Terminal className="w-6 h-6 text-blue-500" />
          <span>TESTPILOT</span>
        </Link>

        {user && (
          <div className="flex items-center gap-6 text-sm font-medium text-slate-300">
            <Link to="/" className="hover:text-blue-400 transition-colors flex items-center gap-1.5">
              Dashboard
            </Link>
            <Link to="/projects" className="hover:text-blue-400 transition-colors flex items-center gap-1.5">
              <FolderGit2 className="w-4 h-4" /> Projects
            </Link>
            {user.role === 'ADMIN' && (
              <Link to="/admin/knowledge" className="hover:text-blue-400 transition-colors flex items-center gap-1.5 text-purple-400">
                <BookOpen className="w-4 h-4" /> Knowledge Base
              </Link>
            )}
          </div>
        )}
      </div>

      {user ? (
        <div className="flex items-center gap-4">
          <div className="text-right">
            <div className="text-sm font-semibold text-white">{user.name}</div>
            <div className="text-xs text-blue-400 font-mono flex items-center gap-1">
              <ShieldCheck className="w-3 h-3" /> {user.role}
            </div>
          </div>
          <button onClick={handleLogout} className="btn-secondary text-sm">
            <LogOut className="w-4 h-4" /> Logout
          </button>
        </div>
      ) : (
        <div className="flex items-center gap-3">
          <Link to="/login" className="btn-secondary text-sm">Login</Link>
          <Link to="/register" className="btn-primary text-sm">Register</Link>
        </div>
      )}
    </nav>
  );
}
