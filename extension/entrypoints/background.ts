import {
  appendPendingCapture,
  buildCaptureText,
  remainingPendingCapture,
} from '../lib/capture-text';
import { currentUser, signIn } from '../lib/firebase';
import { addNote } from '../lib/notes';
import { pendingCapture } from '../lib/pending-capture';

const captureMenuId = 'futaba:add-note';
let pendingCaptureUpdate = Promise.resolve();

function updatePendingCapture(
  update: (existing: string | null) => string | null,
): Promise<void> {
  const result = pendingCaptureUpdate.then(async () => {
    const existing = await pendingCapture.getValue();
    const next = update(existing);
    if (next === existing) {
      return;
    }
    if (next === null) {
      await pendingCapture.removeValue();
    } else {
      await pendingCapture.setValue(next);
    }
  });
  // Leave the queue usable after a storage error. The caller handles the error.
  pendingCaptureUpdate = result.catch(() => {});
  return result;
}

async function captureFromMenu(text: string): Promise<void> {
  try {
    if (await currentUser()) {
      await addNote(text);
      return;
    }
  } catch (error) {
    console.error('Futaba context menu capture failed.', error);
  }

  await updatePendingCapture((existing) =>
    appendPendingCapture(existing, text),
  );
  try {
    await browser.action.openPopup?.();
  } catch (error) {
    console.error('Futaba could not open the popup.', error);
  }
}

async function saveFromPopup(text: string, pending: unknown): Promise<void> {
  await addNote(text);
  if (typeof pending === 'string') {
    try {
      await updatePendingCapture((existing) =>
        remainingPendingCapture(existing, pending),
      );
    } catch (error) {
      console.error('Futaba could not clear the saved capture.', error);
    }
  }
}

export default defineBackground(() => {
  let activeSignIn: ReturnType<typeof signIn> | undefined;

  browser.runtime.onInstalled.addListener(() => {
    browser.contextMenus.removeAll(() => {
      browser.contextMenus.create(
        {
          id: captureMenuId,
          title: 'Futabaに追加',
          contexts: ['selection', 'page'],
        },
        () => {
          if (browser.runtime.lastError) {
            console.error(
              'Futaba could not register the context menu.',
              browser.runtime.lastError,
            );
          }
        },
      );
    });
  });

  browser.contextMenus.onClicked.addListener(
    ({ menuItemId, selectionText, pageUrl }, tab) => {
      if (menuItemId !== captureMenuId) {
        return;
      }
      const text = buildCaptureText(selectionText, pageUrl || tab?.url);
      if (!text) {
        console.warn('Futaba capture requires selected text or a page URL.');
        return;
      }
      void captureFromMenu(text).catch((error: unknown) => {
        console.error('Futaba could not store the pending capture.', error);
      });
    },
  );

  browser.runtime.onMessage.addListener(
    (message: unknown, _sender, sendResponse) => {
      if (
        typeof message === 'object' &&
        message !== null &&
        'type' in message &&
        message.type === 'futaba:save-note'
      ) {
        if (
          !('text' in message) ||
          typeof message.text !== 'string' ||
          !message.text.trim()
        ) {
          sendResponse(false);
          return;
        }
        void saveFromPopup(
          message.text,
          'pending' in message ? message.pending : null,
        ).then(
          () => sendResponse(true),
          (error: unknown) => {
            console.error('Futaba could not save the note.', error);
            sendResponse(false);
          },
        );
        return true;
      }
      if (message !== 'futaba:sign-in') {
        return;
      }

      // The popup can close when Chrome opens the Google sign-in window.
      activeSignIn ??= signIn().finally(() => {
        activeSignIn = undefined;
      });
      void activeSignIn.then(
        () => sendResponse(true),
        (error: unknown) => {
          console.error('Futaba Google sign-in failed.', error);
          sendResponse(false);
        },
      );
      return true;
    },
  );
});
