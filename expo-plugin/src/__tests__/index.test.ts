import withRNBackgroundDownloader from '../index';

describe('RNBackgroundDownloader Expo Plugin', () => {
  test('should export a function', () => {
    expect(typeof withRNBackgroundDownloader).toBe('function');
  });

  test('should return a configuration function', () => {
    const result = withRNBackgroundDownloader({} as any);
    expect(typeof result).toBe('object');
  });
});