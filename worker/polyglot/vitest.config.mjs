export default {
  root: '/work/repo',
  cacheDir: '/work/vite-cache',
  test: {
    environment: 'node',
    include: ['**/__testpilot__/*.test.{js,ts}'],
    pool: 'forks',
    maxWorkers: 1,
    fileParallelism: false,
    testTimeout: 10000,
    hookTimeout: 10000,
  },
};
