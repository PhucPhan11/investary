import { answerSegments } from './answer-segments';

describe('answerSegments', () => {
  it('splits text and citation markers', () => {
    expect(answerSegments('Theo TCBS, SCS Chờ mua [S1]. SSI khuyến nghị Bán [S2, S3].')).toEqual([
      { text: 'Theo TCBS, SCS Chờ mua ', labels: [] },
      { text: null, labels: ['S1'] },
      { text: '. SSI khuyến nghị Bán ', labels: [] },
      { text: null, labels: ['S2', 'S3'] },
      { text: '.', labels: [] },
    ]);
  });

  it('keeps markup-looking text as plain text', () => {
    expect(answerSegments('<img src=x onerror=alert(1)> [x]')).toEqual([{ text: '<img src=x onerror=alert(1)> [x]', labels: [] }]);
  });
});
