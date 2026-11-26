import {
  collection,
  doc,
  getDocs,
  getFirestore,
  Timestamp,
  type Firestore,
  writeBatch,
} from 'firebase/firestore/lite';

import { currentUser, firebaseApp } from './firebase';
import { parseHashtags } from './hashtags';
import { buildNoteDocuments } from './note-documents';

let lastWrite = Promise.resolve();
let db: Firestore | undefined;

function firestore(): Firestore {
  return (db ??= getFirestore(firebaseApp()));
}

export function addNote(text: string): Promise<void> {
  // Keep captures in this worker from resolving the same new label in parallel.
  const write = lastWrite.then(() => writeNote(text));
  lastWrite = write.catch(() => {});
  return write;
}

async function writeNote(text: string): Promise<void> {
  const user = await currentUser();
  if (!user) {
    throw new Error('メモの追加にはログインが必要です。');
  }

  const db = firestore();
  const labels = collection(db, 'users', user.uid, 'labels');
  const notes = collection(db, 'users', user.uid, 'notes');
  const tags = parseHashtags(text);
  const existingLabels = tags.length
    ? (await getDocs(labels)).docs.map((snapshot) => ({
        id: snapshot.id,
        name: snapshot.get('name') as string,
        order: snapshot.get('order') as number,
      }))
    : [];

  const documents = buildNoteDocuments(
    text,
    tags,
    existingLabels,
    Timestamp.now(),
    () => doc(labels).id,
  );
  const batch = writeBatch(db);
  for (const { id, data } of documents.newLabels) {
    batch.set(doc(labels, id), data);
  }
  batch.set(doc(notes, doc(notes).id), documents.note);
  await batch.commit();
}
