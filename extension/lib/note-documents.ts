import { hashtagKey } from './hashtags';

import type { Timestamp } from 'firebase/firestore/lite';

interface ExistingLabel {
  id: string;
  name: string;
  order: number;
}

interface NewLabel {
  id: string;
  data: { name: string; color: string; order: number };
}

export function buildNoteDocuments(
  text: string,
  tags: readonly string[],
  existingLabels: readonly ExistingLabel[],
  now: Timestamp,
  allocateLabelId: (name: string) => string,
) {
  const newLabels: NewLabel[] = [];
  let nextOrder = existingLabels.length
    ? existingLabels.reduce(
        (max, label) => Math.max(max, label.order),
        -Infinity,
      ) + 1
    : 0;

  const labelIds = tags.map((name) => {
    const key = hashtagKey(name);
    const existing = existingLabels.find(
      (label) => hashtagKey(label.name) === key,
    );
    if (existing) {
      return existing.id;
    }

    const id = allocateLabelId(name);
    newLabels.push({ id, data: { name, color: '', order: nextOrder++ } });
    return id;
  });

  return {
    note: {
      text,
      labelIds,
      pinned: false,
      createdAt: now,
      updatedAt: now,
      deletedAt: null,
    },
    newLabels,
  };
}
