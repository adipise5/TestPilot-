const BASE_URL = '/api';

export const getAuthToken = () => localStorage.getItem('token');
export const setAuthToken = (token) => localStorage.setItem('token', token);
export const removeAuthToken = () => {
  localStorage.removeItem('token');
  localStorage.removeItem('user');
};
export const getUser = () => {
  const user = localStorage.getItem('user');
  return user ? JSON.parse(user) : null;
};
export const setUser = (user) => localStorage.setItem('user', JSON.stringify(user));

async function apiRequest(endpoint, method = 'GET', body = null) {
  const headers = {
    'Content-Type': 'application/json',
  };

  const token = getAuthToken();
  if (token) {
    headers['Authorization'] = `Bearer ${token}`;
  }

  const config = {
    method,
    headers,
  };

  if (body) {
    config.body = JSON.stringify(body);
  }

  const response = await fetch(`${BASE_URL}${endpoint}`, config);

  if (response.status === 401) {
    removeAuthToken();
    window.location.href = '/login';
    throw new Error('Unauthorized');
  }

  if (response.status === 204) {
    return null;
  }

  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.message || data.error || 'API Request failed');
  }
  return data;
}

export const api = {
  // Auth
  register: (data) => apiRequest('/auth/register', 'POST', data),
  login: (data) => apiRequest('/auth/login', 'POST', data),

  // Projects
  getProjects: () => apiRequest('/projects'),
  getProject: (id) => apiRequest(`/projects/${id}`),
  createProject: (data) => apiRequest('/projects', 'POST', data),
  deleteProject: (id) => apiRequest(`/projects/${id}`, 'DELETE'),

  // Code Files
  getCodeFiles: (projectId) => apiRequest(`/projects/${projectId}/files`),
  addCodeFile: (projectId, data) => apiRequest(`/projects/${projectId}/files`, 'POST', data),

  // Immutable repository intake
  startGithubInstall: () => apiRequest('/integrations/github/install/start', 'POST'),
  getConnectedRepository: (projectId) => apiRequest(`/projects/${projectId}/repository`),
  connectRepository: (projectId, data) => apiRequest(`/projects/${projectId}/repository`, 'POST', data),
  refreshRepository: (repositoryId, revision) => apiRequest(
    `/repositories/${repositoryId}/ingestions`, 'POST', { revision }),
  getRepositoryCatalog: (repositoryId) => apiRequest(`/repositories/${repositoryId}/catalog`),
  disconnectRepository: (repositoryId) => apiRequest(`/repositories/${repositoryId}`, 'DELETE'),

  // AI & Testing
  analyzeProject: (projectId) => apiRequest(`/projects/${projectId}/analyze`, 'POST'),
  createTestRun: (projectId) => apiRequest(`/projects/${projectId}/test-runs`, 'POST'),
  startAutomatedTestRun: (projectId) => apiRequest(`/projects/${projectId}/test-runs/auto`, 'POST'),
  getTestRun: (id) => apiRequest(`/test-runs/${id}`),
  getProjectTestRuns: (projectId) => apiRequest(`/projects/${projectId}/test-runs`),
  executeTestRun: (id) => apiRequest(`/test-runs/${id}/execute`, 'POST'),
  generateTestsForRun: (id) => apiRequest(`/test-runs/${id}/generate-tests`, 'POST'),

  // Failures & Fixes
  analyzeFailure: (testResultId) => apiRequest(`/failures/${testResultId}/analyze`, 'POST'),
  getFailureAnalysis: (testResultId) => apiRequest(`/failures/${testResultId}`),
  getFixSuggestion: (failureAnalysisId) => apiRequest(`/failures/analysis/${failureAnalysisId}/fix`),
  acceptFixSuggestion: (id) => apiRequest(`/fix-suggestions/${id}/accept`, 'POST'),
  rejectFixSuggestion: (id) => apiRequest(`/fix-suggestions/${id}/reject`, 'POST'),

  // Admin Knowledge Base
  getKnowledgeDocs: () => apiRequest('/knowledge'),
  createKnowledgeDoc: (data) => apiRequest('/knowledge', 'POST', data),
  deleteKnowledgeDoc: (id) => apiRequest(`/knowledge/${id}`, 'DELETE'),
  queryKnowledge: (data) => apiRequest('/knowledge/query', 'POST', data),
};
