export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

/** JSON request to the Dreamhaven API. */
export async function api<T>(path: string, init: { method?: string; body?: string } = {}): Promise<T> {
  const headers: Record<string, string> = {};
  if (init.body !== undefined) headers['Content-Type'] = 'application/json';

  const response = await fetch(path, {
    method: init.method ?? 'GET',
    headers,
    body: init.body,
    credentials: 'same-origin',
    cache: 'no-store',
  });
  const payload = await response.json().catch(() => ({})) as { message?: string };

  if (!response.ok) {
    throw new ApiError(payload.message || 'The request could not be completed.', response.status);
  }
  return payload as T;
}
