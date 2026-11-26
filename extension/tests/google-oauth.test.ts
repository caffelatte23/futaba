import { describe, expect, it } from 'vitest';

import { buildGoogleOAuthUrl, readGoogleIdToken } from '../lib/google-oauth';

const request = {
  clientId: 'futaba-test.apps.googleusercontent.com',
  redirectUri: 'https://futaba-test.chromiumapp.org/',
  nonce: 'request-nonce',
  state: 'request-state',
};

const encodePayload = (nonce: string) =>
  btoa(JSON.stringify({ nonce })).replace(/=+$/u, '');
const idToken = `eyJhbGciOiJSUzI1NiJ9.${encodePayload(request.nonce)}.signature`;

describe('Google OAuth', () => {
  it('builds an OIDC authorization request with the Chrome redirect URL', () => {
    const url = new URL(buildGoogleOAuthUrl(request));

    expect(url.origin + url.pathname).toBe(
      'https://accounts.google.com/o/oauth2/v2/auth',
    );
    expect(Object.fromEntries(url.searchParams)).toEqual({
      client_id: request.clientId,
      redirect_uri: request.redirectUri,
      response_type: 'id_token',
      scope: 'openid email profile',
      nonce: request.nonce,
      state: request.state,
    });
  });

  it('reads the ID token from a matching redirect fragment', () => {
    const redirect = `${request.redirectUri}#state=${request.state}&id_token=${idToken}`;

    expect(readGoogleIdToken(redirect, request)).toBe(idToken);
  });

  it('rejects a response for a different redirect endpoint', () => {
    const redirect = `${request.redirectUri}other#state=${request.state}&id_token=${idToken}`;

    expect(() => readGoogleIdToken(redirect, request)).toThrow('redirect URL');
  });

  it('rejects a response with a mismatched state', () => {
    const redirect = `${request.redirectUri}#state=other&id_token=${idToken}`;

    expect(() => readGoogleIdToken(redirect, request)).toThrow('state');
  });

  it('rejects a response with a mismatched nonce', () => {
    const staleToken = `eyJhbGciOiJSUzI1NiJ9.${encodePayload('earlier-request')}.signature`;
    const redirect = `${request.redirectUri}#state=${request.state}&id_token=${staleToken}`;

    expect(() => readGoogleIdToken(redirect, request)).toThrow('nonce');
  });

  it('rejects a redirect carrying an OAuth error or no fragment token', () => {
    const denied = `${request.redirectUri}#state=${request.state}&error=access_denied`;
    const inQuery = `${request.redirectUri}?id_token=${idToken}#state=${request.state}`;

    expect(() => readGoogleIdToken(denied, request)).toThrow(
      'authorization failed',
    );
    expect(() => readGoogleIdToken(inQuery, request)).toThrow('ID token');
  });
});
