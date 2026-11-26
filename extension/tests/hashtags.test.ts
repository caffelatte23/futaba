import { expect, it } from 'vitest';

import cases from '../../spec/hashtags.json';
import { parseHashtags } from '../lib/hashtags';

it.each(cases)('parses $text', ({ text, tags }) => {
  expect(parseHashtags(text)).toEqual(tags);
});

it('deduplicates Unicode case variants while keeping the first spelling', () => {
  expect(parseHashtags('#ΟΣ #ος #οσ #İ #i #ı #ß #ss #ẞ')).toEqual([
    'ΟΣ',
    'İ',
    'ß',
    'ss',
  ]);
});
