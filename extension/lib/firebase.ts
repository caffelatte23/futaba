import { initializeApp, type FirebaseApp } from 'firebase/app';
import {
  getAuth,
  GoogleAuthProvider,
  signInWithCredential,
  type Auth,
  type User,
} from 'firebase/auth/web-extension';
import { browser } from 'wxt/browser';

import { buildGoogleOAuthUrl, readGoogleIdToken } from './google-oauth';

const config = {
  apiKey: import.meta.env.WXT_FIREBASE_API_KEY,
  authDomain: import.meta.env.WXT_FIREBASE_AUTH_DOMAIN,
  projectId: import.meta.env.WXT_FIREBASE_PROJECT_ID,
  appId: import.meta.env.WXT_FIREBASE_APP_ID,
};
const clientId = import.meta.env.WXT_GOOGLE_OAUTH_CLIENT_ID;

let app: FirebaseApp | undefined;
let auth: Auth | undefined;

export class FirebaseConfigError extends Error {}

export function firebaseApp(): FirebaseApp {
  if (
    !config.apiKey ||
    !config.authDomain ||
    !config.projectId ||
    !config.appId
  ) {
    throw new FirebaseConfigError('Firebase Web の設定が必要です。');
  }
  return (app ??= initializeApp(config));
}

function configuredAuth() {
  const app = firebaseApp();
  if (!clientId) {
    throw new FirebaseConfigError(
      'Google OAuth クライアント ID の設定が必要です。',
    );
  }
  return { auth: (auth ??= getAuth(app)), clientId };
}

export async function signIn(): Promise<User> {
  const { auth, clientId } = configuredAuth();
  const request = {
    clientId,
    redirectUri: browser.identity.getRedirectURL(),
    nonce: crypto.randomUUID(),
    state: crypto.randomUUID(),
  };
  const redirectUrl = await browser.identity.launchWebAuthFlow({
    url: buildGoogleOAuthUrl(request),
    interactive: true,
  });
  if (!redirectUrl) {
    throw new Error('Google OAuth did not return a redirect URL.');
  }

  const idToken = readGoogleIdToken(redirectUrl, request);
  const { user } = await signInWithCredential(
    auth,
    GoogleAuthProvider.credential(idToken),
  );
  return user;
}

export async function currentUser(): Promise<User | null> {
  const { auth } = configuredAuth();
  await auth.authStateReady();
  return auth.currentUser;
}
