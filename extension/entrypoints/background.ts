import { signIn } from '../lib/firebase';

export default defineBackground(() => {
  let activeSignIn: ReturnType<typeof signIn> | undefined;

  browser.runtime.onMessage.addListener((message, _sender, sendResponse) => {
    if (message !== 'futaba:sign-in') {
      return;
    }

    // The popup can close when Chrome opens the Google sign-in window.
    activeSignIn ??= signIn().finally(() => {
      activeSignIn = undefined;
    });
    void activeSignIn.then(
      () => sendResponse(true),
      () => sendResponse(false),
    );
    return true;
  });
});
