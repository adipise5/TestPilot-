module.exports = {
  rootDir: '/work/repo',
  testEnvironment: 'node',
  testMatch: ['**/__testpilot__/*.test.js', '**/__testpilot__/*.test.ts'],
  cacheDirectory: '/work/jest-cache',
  testTimeout: 10000,
  transform: {
    '^.+\\.[jt]sx?$': ['/opt/testpilot-js/node_modules/babel-jest', {
      babelrc: false, configFile: false,
      presets: [
        ['/opt/testpilot-js/node_modules/@babel/preset-env', { targets: { node: '22' } }],
        '/opt/testpilot-js/node_modules/@babel/preset-typescript',
      ],
    }],
  },
};
