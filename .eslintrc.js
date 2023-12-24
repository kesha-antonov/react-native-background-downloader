module.exports = {
  env: {
    es2020: true,
    jest: true,
  },
  parser: '@babel/eslint-parser',
  extends: [
    'standard',
    'eslint:recommended',
    'plugin:react/recommended',
    'plugin:react-hooks/recommended',
  ],
  parserOptions: {
    ecmaFeatures: {
      jsx: true,
    },
    ecmaVersion: 11,
    sourceType: 'module',
  },
  plugins: [
    'react',
    'react-hooks',
    'jest',
    '@typescript-eslint',
  ],
  settings: {
    react: {
      version: 'detect',
    },
  },
  rules: {
    indent: [
      'error',
      2, {
        SwitchCase: 1,
        ignoredNodes: [
          'TemplateLiteral',
        ],
      },
    ],
    'template-curly-spacing': 'off',
    'linebreak-style': [
      'error',
      'unix',
    ],
    quotes: [
      'error',
      'single',
    ],
    semi: [
      'error',
      'never',
    ],
    'comma-dangle': [
      'error',
      {
        arrays: 'always-multiline',
        objects: 'always-multiline',
        imports: 'always-multiline',
        exports: 'never',
        functions: 'never',
      },
    ],
    'no-func-assign': 'off',
    'no-class-assign': 'off',
    'no-useless-escape': 'off',
    curly: [2, 'multi', 'consistent'],
    'react/display-name': 'off',
    'react-hooks/exhaustive-deps': ['warn', {
    }],
  },
  overrides: [{
    files: ['**/*.ts', '**/*.tsx'],
    parser: '@typescript-eslint/parser',
    extends: ['plugin:@typescript-eslint/recommended'],
  }],
  globals: {
    describe: 'readonly',
    test: 'readonly',
    jest: 'readonly',
    expect: 'readonly',
    fetch: 'readonly',
    navigator: 'readonly',
    __DEV__: 'readonly',
    XMLHttpRequest: 'readonly',
    FormData: 'readonly',
    React$Element: 'readonly',
    requestAnimationFrame: 'readonly',
  },
}
