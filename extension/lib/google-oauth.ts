interface GoogleOAuthRequest {
  clientId: string;
  redirectUri: string;
  nonce: string;
  state: string;
}

export function buildGoogleOAuthUrl(request: GoogleOAuthRequest): string {
  const url = new URL('https://accounts.google.com/o/oauth2/v2/auth');
  url.search = new URLSearchParams({
    client_id: request.clientId,
    redirect_uri: request.redirectUri,
    response_type: 'id_token',
    scope: 'openid email profile',
    nonce: request.nonce,
    state: request.state,
  }).toString();
  return url.href;
}

export function readGoogleIdToken(
  redirectUrl: string,
  request: GoogleOAuthRequest,
): string {
  const url = new URL(redirectUrl);
  const params = new URLSearchParams(url.hash.slice(1));
  const expected = new URL(request.redirectUri);
  if (url.origin !== expected.origin || url.pathname !== expected.pathname) {
    throw new Error('Unexpected Google OAuth redirect URL.');
  }
  if (params.get('state') !== request.state) {
    throw new Error('Google OAuth state did not match.');
  }
  if (params.has('error')) {
    throw new Error('Google authorization failed.');
  }

  const token = params.get('id_token');
  const payload = token?.split('.')[1];
  if (!token || !payload) {
    throw new Error('Google OAuth response did not contain an ID token.');
  }

  // Firebase validates the token. Here we match its nonce to this request.
  const base64 = payload.replaceAll('-', '+').replaceAll('_', '/');
  const bytes = Uint8Array.from(atob(base64), (character) =>
    character.charCodeAt(0),
  );
  const claims = JSON.parse(new TextDecoder().decode(bytes)) as {
    nonce?: unknown;
  } | null;
  if (claims?.nonce !== request.nonce) {
    throw new Error('Google ID token nonce did not match.');
  }
  return token;
}
