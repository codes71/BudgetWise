/** Only fixed classifications and HTTP status codes may cross into operational logs. */
export function errorDiagnostics(error: unknown): { kind: string; status?: number } {
  const record = error !== null && typeof error === 'object'
    ? error as { name?: unknown; status?: unknown }
    : {};
  const status = typeof record.status === 'number' && Number.isInteger(record.status)
    && record.status >= 400 && record.status <= 599 ? record.status : undefined;
  const kinds: Record<string, string> = {
    AbortError: 'cancelled', APIUserAbortError: 'cancelled',
    TimeoutError: 'timeout', APIConnectionTimeoutError: 'timeout', DeadlineError: 'timeout',
    APIConnectionError: 'connection', RefusalError: 'refusal', ZodError: 'validation', SyntaxError: 'validation',
  };
  const kind = typeof record.name === 'string' && Object.hasOwn(kinds, record.name)
    ? kinds[record.name] : status ? 'http' : 'unexpected';
  return { kind, ...(status === undefined ? {} : { status }) };
}

export function errorSummary(error: unknown): string {
  const { kind, status } = errorDiagnostics(error);
  return status === undefined ? kind : `${kind} ${status}`;
}
