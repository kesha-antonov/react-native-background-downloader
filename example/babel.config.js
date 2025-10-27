const path = require('path')

const libFolder = path.resolve(__dirname, '../src')

module.exports = function (api) {
  api.cache(true)

  return {
    presets: ['module:@react-native/babel-preset', '@babel/preset-typescript'],
    overrides: [
      {
        exclude: /\/node_modules\//,
        plugins: [
          [
            'module-resolver',
            {
              extensions: ['.tsx', '.ts', '.js', '.json'],
              alias: {
                '@kesha-antonov/react-native-background-downloader': libFolder,
              },
            },
          ],
        ],
      },
    ],
    plugins: [
      [
        'babel-plugin-react-compiler',
        {
          target: '19',
        },
      ],
      '@babel/plugin-proposal-export-namespace-from',
    ],
  }
}
