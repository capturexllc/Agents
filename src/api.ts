import { authRequired, currentIdToken, handleUnauthorized } from './auth';

export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/** JSON request to the VamsDest API, with the bearer token attached when sign-in is enabled. */
export async function api<T>(path: string, init: { method?: string; body?: string } = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';
  const token = currentIdToken();
  if (token) headers.Authorization = `Bearer ${token}`;

  const response = await fetch(path, {
    method: init.method ?? 'GET',
    headers,
    body: init.body,
    credentials: 'same-origin',
    cache: 'no-store',
  });
  const payload = await response.json().catch(() => ({})) as { message?: string };

  if (response.status === 401 && authRequired) {
    handleUnauthorized();
    throw new ApiError('Your session expired. Signing you in again…', 401);
  }
  if (!response.ok) {
    throw new ApiError(payload.message || 'The request could not be completed.', response.status);
  }
  return payload as T;
}
