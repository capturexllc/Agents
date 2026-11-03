/**
 * Sign-in with Amazon Cognito Hosted UI (authorization code flow with PKCE). Public client, no secret in the
 * browser. The ID token is kept in sessionStorage and sent as a bearer token to the API.
 *
 * Local development runs with VITE_AUTH_REQUIRED unset: no sign-in, no token, and the local Spring API accepts
 * anonymous requests.
 */

export const authRequired = import.meta.env.VITE_AUTH_REQUIRED === 'true';

const cognitoDomain: string = import.meta.env.VITE_COGNITO_DOMAIN ?? '';
const clientId: string = import.meta.env.VITE_COGNITO_CLIENT_ID ?? '';
const SESSION_KEY = 'vamsdest.session';
const PKCE_KEY = 'vamsdest.pkce';
const REFRESH_SKEW_MS = 60_000;

type Session = { idToken: string; expiresAt: number };

let inflight: Promise<boolean> | null = null;

function redirectUri() {
  return `${window.location.origin}/`;
}

function base64Url(buffer: ArrayBuffer) {
  let binary = '';
  for (const byte of new Uint8Array(buffer)) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomToken(bytes: number) {
  return base64Url(crypto.getRandomValues(new Uint8Array(bytes)).buffer);
}

function readSession(): Session | null {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    return raw ? (JSON.parse(raw) as Session) : null;
  } catch {
    return null;
  }
}

function writeSession(session: Session) {
  try {
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
  } catch {
    // Private browsing can block storage; the user will simply be asked to sign in again on reload.
  }
}

function clearSession() {
  try {
    sessionStorage.removeItem(SESSION_KEY);
  } catch {
    // Nothing to clear when storage is unavailable.
  }
}

async function redirectToLogin() {
  const verifier = randomToken(48);
  const state = randomToken(18);
  const challenge = base64Url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  sessionStorage.setItem(PKCE_KEY, JSON.stringify({ verifier, state }));
  const params = new URLSearchParams({
    client_id: clientId,
    response_type: 'code',
    scope: 'openid email profile',
    redirect_uri: redirectUri(),
    code_challenge: challenge,
    code_challenge_method: 'S256',
    state,
  });
  window.location.assign(`https://${cognitoDomain}/oauth2/authorize?${params}`);
}

/** Exchanges the authorization code on return from Hosted UI. Returns false when there is no code. */
async function exchangeCode(): Promise<boolean> {
  const url = new URL(window.location.href);
  const code = url.searchParams.get('code');
  if (!code) return false;
  const returnedState = url.searchParams.get('state');
  window.history.replaceState({}, '', url.pathname);

  const saved = JSON.parse(sessionStorage.getItem(PKCE_KEY) ?? 'null') as { verifier: string; state: string } | null;
  sessionStorage.removeItem(PKCE_KEY);
  if (!saved || saved.state !== returnedState) {
    throw new Error('Sign-in could not be verified. Please try again.');
  }

  const response = await fetch(`https://${cognitoDomain}/oauth2/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: clientId,
      code,
      redirect_uri: redirectUri(),
      code_verifier: saved.verifier,
    }),
  });
  if (!response.ok) {
    throw new Error('Sign-in failed. Please try again.');
  }
  const tokens = (await response.json()) as { id_token: string; expires_in: number };
  writeSession({ idToken: tokens.id_token, expiresAt: Date.now() + tokens.expires_in * 1000 });
  return true;
}

/**
 * Makes sure the user has a valid session, completing a pending sign-in or redirecting to Hosted UI. Memoized so
 * React's double-invoked effects in development do not exchange the same code twice.
 */
export function ensureSignedIn(): Promise<boolean> {
  if (!authRequired) return Promise.resolve(true);
  if (!inflight) {
    inflight = (async () => {
      await exchangeCode();
      const session = readSession();
      if (session && session.expiresAt - REFRESH_SKEW_MS > Date.now()) return true;
      await redirectToLogin();
      return false;
    })().finally(() => {
      inflight = null;
    });
  }
  return inflight;
}

export function currentIdToken(): string | null {
  if (!authRequired) return null;
  const session = readSession();
  return session && session.expiresAt - REFRESH_SKEW_MS > Date.now() ? session.idToken : null;
}

/** Clears the local session and redirects through Hosted UI sign-out. */
export function signOut() {
  clearSession();
  if (!authRequired) return;
  const params = new URLSearchParams({ client_id: clientId, logout_uri: redirectUri() });
  window.location.assign(`https://${cognitoDomain}/logout?${params}`);
}

/** Drops an expired or rejected session and starts sign-in again. */
export function handleUnauthorized() {
  clearSession();
  void ensureSignedIn();
}
