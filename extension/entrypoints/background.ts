import { currentUser, signIn } from '../lib/firebase';
import { addNote } from '../lib/notes';
import { pendingCapture } from '../lib/pending-capture';

const captureMenuId = 'futaba:add-note';

async function captureFromMenu(text: string): Promise<void> {
  try {
    if (await currentUser()) {
      await addNote(text);
      return;
    }
  } catch (error) {
    console.error('Futaba context menu capture failed.', error);
  }

  await pendingCapture.setValue(text);
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
      if ((await pendingCapture.getValue()) === pending) {
        await pendingCapture.removeValue();
      }
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
    ({ menuItemId, selectionText, pageUrl }) => {
      if (menuItemId === captureMenuId && pageUrl) {
        void captureFromMenu(`${selectionText ?? ''}\n${pageUrl}`).catch(
          (error: unknown) => {
            console.error('Futaba could not store the pending capture.', error);
          },
        );
      }
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
