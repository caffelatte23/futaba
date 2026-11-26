import { expect, it } from 'vitest';

import {
  appendPendingCapture,
  buildCaptureText,
  remainingPendingCapture,
} from '../lib/capture-text';

it('starts a pending draft with the first capture', () => {
  expect(appendPendingCapture(null, 'first')).toBe('first');
  expect(appendPendingCapture('', 'first')).toBe('first');
});

it('appends captures in order with a blank line between them', () => {
  const pending = appendPendingCapture('first\nsecond line', 'second');
  expect(appendPendingCapture(pending, 'third')).toBe(
    'first\nsecond line\n\nsecond\n\nthird',
  );
});

it('clears only the saved part of a pending draft', () => {
  const saved = 'first\n\nsecond';
  expect(remainingPendingCapture(saved, saved)).toBeNull();
  expect(remainingPendingCapture(`${saved}\n\nthird`, saved)).toBe('third');
  expect(remainingPendingCapture(`${saved} more`, saved)).toBe(`${saved} more`);
});

it.each([
  [
    'selected text',
    'https://example.com/',
    'selected text\nhttps://example.com/',
  ],
  [undefined, 'https://example.com/', 'https://example.com/'],
  ['  ', 'https://example.com/', 'https://example.com/'],
  ['selected text', undefined, 'selected text'],
  [undefined, undefined, ''],
])('formats selection %s and URL %s', (selection, url, expected) => {
  expect(buildCaptureText(selection, url)).toBe(expected);
});
