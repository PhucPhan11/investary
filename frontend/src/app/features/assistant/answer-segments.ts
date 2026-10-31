/** Part of an answer: plain text, or the labels of a citation marker such as "[S1, S2]". */
export interface AnswerSegment {
  text: string | null;
  labels: string[];
}

const MARKER = /\[\s*(S\d+(?:\s*[,;]\s*S\d+)*)\s*\]/g;

/**
 * Splits answer text into text and citation segments so markers can be rendered as buttons without
 * ever interpreting the answer as HTML.
 */
export function answerSegments(answer: string): AnswerSegment[] {
  const segments: AnswerSegment[] = [];
  let last = 0;
  for (const match of answer.matchAll(MARKER)) {
    if (match.index > last) {
      segments.push({ text: answer.slice(last, match.index), labels: [] });
    }
    segments.push({ text: null, labels: match[1].split(/\s*[,;]\s*/) });
    last = match.index + match[0].length;
  }
  if (last < answer.length) {
    segments.push({ text: answer.slice(last), labels: [] });
  }
  return segments;
}
