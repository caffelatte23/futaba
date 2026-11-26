import { browser } from 'wxt/browser';

import { currentUser } from '../../lib/firebase';
import './style.css';

const signInButton = document.querySelector<HTMLButtonElement>('#sign-in');
const status = document.querySelector<HTMLParagraphElement>('#status');
if (!signInButton || !status) {
  throw new Error('Futaba sign-in controls are missing.');
}

function showSignedIn(
  button: HTMLButtonElement,
  message: HTMLParagraphElement,
): void {
  button.hidden = true;
  message.textContent = 'ログイン済み';
}

signInButton.addEventListener('click', async () => {
  signInButton.disabled = true;
  status.textContent = 'Googleでログインしています…';
  try {
    const signedIn: unknown =
      await browser.runtime.sendMessage('futaba:sign-in');
    if (signedIn !== true) {
      throw new Error('Google sign-in did not complete.');
    }
    showSignedIn(signInButton, status);
  } catch {
    status.textContent = 'ログインできませんでした。もう一度お試しください。';
    signInButton.disabled = false;
  }
});

try {
  if (await currentUser()) {
    showSignedIn(signInButton, status);
  } else {
    signInButton.disabled = false;
    status.textContent = '';
  }
} catch {
  status.textContent =
    'ログイン状態を確認できません。拡張機能の設定をご確認ください。';
}
