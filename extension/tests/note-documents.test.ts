import { Timestamp } from 'firebase/firestore/lite';
import { expect, it } from 'vitest';

import { parseHashtags } from '../lib/hashtags';
import { buildNoteDocuments } from '../lib/note-documents';

const now = new Timestamp(1_700_000_000, 123_000_000);

it('reuses labels by name and builds new documents in hashtag order', () => {
  const text = ' #Work ＃Private #WORK #次 \n';
  const documents = buildNoteDocuments(
    text,
    parseHashtags(text),
    [
      { id: 'work', name: 'work', order: 7 },
      { id: 'later', name: 'Later', order: 2 },
    ],
    now,
    (name) => `new-${name}`,
  );

  expect(documents).toEqual({
    note: {
      text,
      labelIds: ['work', 'new-Private', 'new-次'],
      pinned: false,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
    },
    newLabels: [
      { id: 'new-Private', data: { name: 'Private', color: '', order: 8 } },
      { id: 'new-次', data: { name: '次', color: '', order: 9 } },
    ],
  });
});

it('starts label order at zero when the user has no labels', () => {
  const { note, newLabels } = buildNoteDocuments(
    '#First',
    ['First'],
    [],
    now,
    () => 'id',
  );

  expect(note.labelIds).toEqual(['id']);
  expect(newLabels).toEqual([
    { id: 'id', data: { name: 'First', color: '', order: 0 } },
  ]);
});

it('builds a note without labels for text without hashtags', () => {
  const { note, newLabels } = buildNoteDocuments(
    'https://example.com/#anchor',
    [],
    [],
    now,
    () => {
      throw new Error('No label ID should be allocated.');
    },
  );

  expect(note.labelIds).toEqual([]);
  expect(newLabels).toEqual([]);
});
