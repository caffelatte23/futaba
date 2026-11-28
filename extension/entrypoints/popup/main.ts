import { browser } from 'wxt/browser';

import { appendPendingCapture } from '../../lib/capture-text';
import { currentUser, FirebaseConfigError } from '../../lib/firebase';
import { pendingCapture } from '../../lib/pending-capture';
import { signedInFlag } from '../../lib/signed-in-flag';
import './style.css';

const signedOut = document.querySelector<HTMLElement>('#signed-out');
const signInButton = document.querySelector<HTMLButtonElement>('#sign-in');
const captureForm = document.querySelector<HTMLFormElement>('#capture');
const noteInput = document.querySelector<HTMLTextAreaElement>('#note');
const saveButton = document.querySelector<HTMLButtonElement>('#save');
const status = document.querySelector<HTMLParagraphElement>('#status');
if (
  !signedOut ||
  !signInButton ||
  !captureForm ||
  !noteInput ||
  !saveButton ||
  !status
) {
  throw new Error('Futaba popup controls are missing.');
}

let pending: string | null = null;
let readyToSave = false;

const updateSaveButton = (): void => {
  saveButton.disabled =
    !readyToSave || noteInput.readOnly || !noteInput.value.trim();
};

const showCapture = (): void => {
  signedOut.hidden = true;
  captureForm.hidden = false;
  noteInput.focus();
};

const loadPendingCapture = async (): Promise<void> => {
  try {
    pending = await pendingCapture.getValue();
    if (pending) {
      const typed = noteInput.value;
      const selectionStart = noteInput.selectionStart;
      const selectionEnd = noteInput.selectionEnd;
      const selectionDirection = noteInput.selectionDirection;
      const combined = typed ? appendPendingCapture(pending, typed) : pending;
      noteInput.value = combined;
      if (typed) {
        const prefixLength = combined.length - typed.length;
        noteInput.setSelectionRange(
          prefixLength + selectionStart,
          prefixLength + selectionEnd,
          selectionDirection,
        );
      }
    }
  } catch (error) {
    console.error('Futaba could not load the pending capture.', error);
    status.textContent = '保留中のメモを読み込めませんでした。';
  }
  readyToSave = true;
  updateSaveButton();
};

const rememberSignIn = async (signedIn: boolean): Promise<void> => {
  try {
    await signedInFlag.setValue(signedIn);
  } catch (error) {
    console.error('Futaba could not store the sign-in flag.', error);
  }
};

const deferUnconfirmedCapture = async (): Promise<void> => {
  const text = noteInput.value;
  if (!text.trim()) {
    return;
  }
  try {
    const saved: unknown = await browser.runtime.sendMessage({
      type: 'futaba:defer-capture',
      text,
    });
    if (saved !== true) {
      throw new Error('Futaba could not defer the capture.');
    }
    noteInput.value = '';
  } catch (error) {
    console.error('Futaba could not store the pending capture.', error);
    status.textContent =
      '入力中のメモを保留できませんでした。この画面からログインしてください。';
  }
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
    status.textContent = '';
    showCapture();
    await loadPendingCapture();
  } catch (error) {
    console.error('Futaba Google sign-in failed.', error);
    status.textContent = 'ログインできませんでした。もう一度お試しください。';
    signInButton.disabled = false;
  }
});

noteInput.addEventListener('input', () => {
  updateSaveButton();
  if (readyToSave) {
    status.textContent = '';
  }
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
  if (saveButton.disabled || !text.trim()) {
    return;
  }

  noteInput.readOnly = true;
  updateSaveButton();
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
    updateSaveButton();
    noteInput.focus();
  }
});

let previouslySignedIn = false;
try {
  previouslySignedIn = await signedInFlag.getValue();
} catch (error) {
  console.error('Futaba could not read the sign-in flag.', error);
}
if (previouslySignedIn) {
  showCapture();
} else {
  signedOut.hidden = false;
}

try {
  if (await currentUser()) {
    if (!previouslySignedIn) {
      showCapture();
    }
    await loadPendingCapture();
    if (!previouslySignedIn) {
      await rememberSignIn(true);
    }
  } else {
    if (previouslySignedIn) {
      noteInput.readOnly = true;
      await deferUnconfirmedCapture();
      await rememberSignIn(false);
      captureForm.hidden = true;
      signedOut.hidden = false;
      noteInput.readOnly = false;
    }
    signInButton.disabled = false;
    if (previouslySignedIn) {
      signInButton.focus();
    }
  }
} catch (error) {
  console.error('Futaba could not check the sign-in state.', error);
  status.textContent =
    error instanceof FirebaseConfigError
      ? error.message
      : 'ログイン状態を確認できません。拡張機能の設定をご確認ください。';
}
