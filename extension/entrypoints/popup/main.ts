import { browser } from 'wxt/browser';

import { currentUser, FirebaseConfigError } from '../../lib/firebase';
import { pendingCapture } from '../../lib/pending-capture';
import './style.css';

const signInButton = document.querySelector<HTMLButtonElement>('#sign-in');
const captureForm = document.querySelector<HTMLFormElement>('#capture');
const noteInput = document.querySelector<HTMLTextAreaElement>('#note');
const saveButton = document.querySelector<HTMLButtonElement>('#save');
const status = document.querySelector<HTMLParagraphElement>('#status');
if (!signInButton || !captureForm || !noteInput || !saveButton || !status) {
  throw new Error('Futaba popup controls are missing.');
}

let pending: string | null = null;

const showCapture = async (): Promise<void> => {
  signInButton.hidden = true;
  status.textContent = '';
  try {
    pending = await pendingCapture.getValue();
    noteInput.value = pending ?? '';
  } catch (error) {
    console.error('Futaba could not load the pending capture.', error);
    status.textContent = '保留中のメモを読み込めませんでした。';
  }
  captureForm.hidden = false;
  saveButton.disabled = !noteInput.value.trim();
  noteInput.focus();
};

signInButton.addEventListener('click', async () => {
  signInButton.disabled = true;
  status.textContent = 'Googleでログインしています…';
  try {
    const signedIn: unknown =
      await browser.runtime.sendMessage('futaba:sign-in');
    if (signedIn !== true) {
      throw new Error('Google sign-in did not complete.');
    }
    await showCapture();
  } catch (error) {
    console.error('Futaba Google sign-in failed.', error);
    status.textContent = 'ログインできませんでした。もう一度お試しください。';
    signInButton.disabled = false;
  }
});

noteInput.addEventListener('input', () => {
  saveButton.disabled = !noteInput.value.trim();
  status.textContent = '';
});

noteInput.addEventListener('keydown', (event) => {
  if (event.ctrlKey && event.key === 'Enter' && !event.isComposing) {
    event.preventDefault();
    if (!saveButton.disabled) {
      captureForm.requestSubmit();
    }
  }
});

captureForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  const text = noteInput.value;
  if (noteInput.readOnly || !text.trim()) {
    return;
  }

  noteInput.readOnly = true;
  saveButton.disabled = true;
  status.textContent = '追加しています…';
  try {
    const saved: unknown = await browser.runtime.sendMessage({
      type: 'futaba:save-note',
      text,
      pending,
    });
    if (saved !== true) {
      throw new Error('Futaba note save did not complete.');
    }
    noteInput.value = '';
    pending = null;
    status.textContent = '追加しました';
  } catch (error) {
    console.error('Futaba could not save the note.', error);
    status.textContent = '追加できませんでした。もう一度お試しください。';
  } finally {
    noteInput.readOnly = false;
    saveButton.disabled = !noteInput.value.trim();
    noteInput.focus();
  }
});

try {
  if (await currentUser()) {
    await showCapture();
  } else {
    signInButton.disabled = false;
    status.textContent = '';
  }
} catch (error) {
  console.error('Futaba could not check the sign-in state.', error);
  status.textContent =
    error instanceof FirebaseConfigError
      ? error.message
      : 'ログイン状態を確認できません。拡張機能の設定をご確認ください。';
}
