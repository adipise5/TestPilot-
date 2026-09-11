import { useCallback, useEffect, useState } from 'react';
import { api } from '../api/client';
import { BookOpen, Plus, Trash2, Search, Sparkles } from 'lucide-react';

export default function AdminKnowledge() {
  const [docs, setDocs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [title, setTitle] = useState('');
  const [source, setSource] = useState('');
  const [content, setContent] = useState('');
  const [query, setQuery] = useState('');
  const [searchResults, setSearchResults] = useState([]);
  const [searching, setSearching] = useState(false);

  const loadDocs = useCallback(async () => {
    try {
      const data = await api.getKnowledgeDocs();
      setDocs(data);
    } catch (err) {
      console.error(err);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadDocs();
  }, [loadDocs]);

  const handleCreateDoc = async (e) => {
    e.preventDefault();
    try {
      await api.createKnowledgeDoc({ title, source, content });
      setTitle('');
      setSource('');
      setContent('');
      setShowModal(false);
      loadDocs();
    } catch (err) {
      alert(err.message);
    }
  };

  const handleDeleteDoc = async (id) => {
    if (!window.confirm('Are you sure you want to delete this knowledge document?')) return;
    try {
      await api.deleteKnowledgeDoc(id);
      loadDocs();
    } catch (err) {
      alert(err.message);
    }
  };

  const handleQueryVectorSearch = async (e) => {
    e.preventDefault();
    if (!query) return;
    setSearching(true);
    try {
      const results = await api.queryKnowledge({ query, topK: 3 });
      setSearchResults(results);
    } catch (err) {
      alert(err.message);
    } finally {
      setSearching(false);
    }
  };

  return (
    <div className="max-w-6xl mx-auto p-6 space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold flex items-center gap-2">
            <BookOpen className="w-6 h-6 text-purple-400" /> RAG Knowledge Base Management
          </h1>
          <p className="text-sm text-slate-400">Admin management of testing guidelines & vector search indexing</p>
        </div>
        <button onClick={() => setShowModal(true)} className="btn-primary">
          <Plus className="w-4 h-4" /> Ingest Knowledge Doc
        </button>
      </div>

      {/* Ingestion Modal */}
      {showModal && (
        <div className="fixed inset-0 bg-black/70 flex items-center justify-center p-4 z-50">
          <div className="card max-w-xl w-full space-y-4">
            <h2 className="text-xl font-bold">Ingest Testing Knowledge Document</h2>
            <form onSubmit={handleCreateDoc} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1">Title</label>
                <input
                  type="text"
                  required
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                  className="input-field"
                  placeholder="JUnit 5 Exception Testing Best Practices"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1">Source / Citation</label>
                <input
                  type="text"
                  required
                  value={source}
                  onChange={(e) => setSource(e.target.value)}
                  className="input-field"
                  placeholder="JUnit 5 User Guide v5.10"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold uppercase text-slate-400 mb-1">Document Content</label>
                <textarea
                  required
                  value={content}
                  onChange={(e) => setContent(e.target.value)}
                  className="input-field h-40"
                  placeholder="When testing methods that throw exceptions, use assertThrows(ExpectedException.class, () -> executionCall())..."
                />
              </div>
              <div className="flex justify-end gap-3">
                <button type="button" onClick={() => setShowModal(false)} className="btn-secondary">Cancel</button>
                <button type="submit" className="btn-primary">Chunk & Embed Document</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Vector Similarity Search Tester */}
      <div className="card border-purple-900/50 bg-purple-950/10 space-y-4">
        <h2 className="text-lg font-bold text-purple-400 flex items-center gap-2">
          <Sparkles className="w-5 h-5" /> Vector Similarity Query Tester
        </h2>
        <form onSubmit={handleQueryVectorSearch} className="flex gap-3">
          <input
            type="text"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            className="input-field"
            placeholder="Type a testing query (e.g. 'How to test exception handling in JUnit 5?')"
          />
          <button type="submit" disabled={searching} className="btn-primary shrink-0">
            <Search className="w-4 h-4" /> {searching ? 'Searching...' : 'Vector Search'}
          </button>
        </form>

        {searchResults.length > 0 && (
          <div className="space-y-2 pt-2">
            <span className="text-xs font-bold uppercase text-slate-400">Top-K Matching Chunks:</span>
            {searchResults.map((res, idx) => (
              <div key={idx} className="p-3 bg-slate-900 border border-slate-800 rounded text-xs space-y-1">
                <div className="flex justify-between text-slate-500 font-mono">
                  <span>Chunk ID #{res.chunkId}</span>
                  <span className="text-purple-400">Similarity Score: {(res.similarityScore * 100).toFixed(1)}%</span>
                </div>
                <p className="text-slate-300">{res.content}</p>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Knowledge Base Documents List */}
      <div className="card space-y-4">
        <h2 className="text-lg font-bold border-b border-slate-800 pb-3">
          Ingested Knowledge Documents ({docs.length})
        </h2>
        {loading ? (
          <div className="text-center py-8 text-slate-500">Loading documents...</div>
        ) : docs.length === 0 ? (
          <div className="text-center py-8 text-slate-500 text-sm">No knowledge documents ingested yet.</div>
        ) : (
          <div className="space-y-3">
            {docs.map((doc) => (
              <div key={doc.id} className="p-4 bg-slate-900 border border-slate-800 rounded-lg space-y-2">
                <div className="flex items-center justify-between">
                  <h3 className="font-bold text-white text-base">{doc.title}</h3>
                  <button onClick={() => handleDeleteDoc(doc.id)} className="text-slate-500 hover:text-red-400 p-1">
                    <Trash2 className="w-4 h-4" />
                  </button>
                </div>
                <div className="text-xs text-purple-400 font-mono">Source: {doc.source}</div>
                <p className="text-xs text-slate-400 line-clamp-3">{doc.content}</p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
