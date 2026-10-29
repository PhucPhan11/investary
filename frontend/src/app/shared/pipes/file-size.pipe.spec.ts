import { FileSizePipe } from './file-size.pipe';

describe('FileSizePipe', () => {
  const pipe = new FileSizePipe();

  it.each([
    [0, '0 B'],
    [512, '512 B'],
    [1536, '1.5 KB'],
    [10 * 1024, '10 KB'],
    [5.25 * 1024 * 1024, '5.3 MB'],
    [3 * 1024 ** 3, '3.0 GB'],
  ])('formats %d bytes as %s', (bytes, expected) => {
    expect(pipe.transform(bytes)).toBe(expected);
  });

  it('renders a dash for missing values', () => {
    expect(pipe.transform(null)).toBe('—');
    expect(pipe.transform(-1)).toBe('—');
  });
});
