export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly location?: { nodeId?: string; field?: string; port?: string },
    public readonly measurement?: {
      model?: string;
      inputTokens?: number;
      outputTokens?: number;
      durationMs?: number;
    },
  ) {
    super(message);
  }
}

/** All studio endpoints share the same envelope, including the legacy meeting workspace. */
export async function request<T>(path: string, method = 'GET', data?: unknown): Promise<T> {
  const response = await fetch(path, {
    method,
    headers: data === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: data === undefined ? undefined : JSON.stringify(data),
    cache: 'no-store',
  });
  const body = await response.json().catch(() => null);
  if (
    !response.ok ||
    !body ||
    (body.code !== undefined && body.code !== 200) ||
    body.data === undefined
  ) {
    throw new ApiError(
      response.status,
      body?.message || body?.msg || body?.detail || '无法读取服务响应，请检查后端并重试。',
      { nodeId: body?.nodeId, field: body?.field, port: body?.port },
      body?.model || body?.durationMs != null
        ? {
            model: body.model,
            inputTokens: body.inputTokens,
            outputTokens: body.outputTokens,
            durationMs: body.durationMs,
          }
        : undefined,
    );
  }
  return body.data as T;
}

export function errorText(error: unknown): string {
  return error instanceof Error ? error.message : '操作未完成，请重试。';
}
