import { errorDiagnostics } from './error-diagnostics';

type LogLevel = 'info' | 'warn' | 'error' | 'debug';

interface LogEntry {
  timestamp: string;
  level: LogLevel;
  message: string;
  context?: Record<string, number | boolean | string>;
  error?: ReturnType<typeof errorDiagnostics>;
}

const numericMetrics = new Set(['ms', 'imageBytes', 'rawEntries', 'keptEntries', 'inputTokens', 'outputTokens']);
const enumMetrics: Record<string, ReadonlySet<string>> = {
  flow: new Set(['scan', 'voice']),
  kind: new Set(['receipt', 'payment', 'history', 'unknown']),
  keptKind: new Set(['receipt', 'payment', 'history', 'unknown']),
  reason: new Set(['not_money', 'unclear', 'multiple', 'unsupported', 'foreign_currency']),
  mediaType: new Set(['image/jpeg', 'image/png', 'image/webp']),
  stopReason: new Set(['end_turn', 'max_tokens', 'stop_sequence', 'tool_use', 'pause_turn', 'refusal']),
  code: new Set(['image_too_large', 'invalid_image', 'validation_failed']),
  status: new Set(['completed', 'incomplete', 'failed', 'cancelled', 'queued', 'in_progress']),
};

// Context is an allowlist, not a blacklist. Error messages, stacks, nested objects and provider-returned
// strings can contain receipts, passwords or recognized speech, even when truncated.
function diagnosticContext(context: Record<string, unknown>): NonNullable<LogEntry['context']> {
  const safe: NonNullable<LogEntry['context']> = {};
  for (const [key, value] of Object.entries(context)) {
    if (numericMetrics.has(key) && typeof value === 'number' && Number.isFinite(value) && value >= 0) safe[key] = value;
    else if (key === 'timedOut' && typeof value === 'boolean') safe[key] = value;
    else if (key === 'status' && typeof value === 'number' && Number.isInteger(value) && value >= 100 && value <= 599) safe[key] = value;
    else if (typeof value === 'string' && Object.hasOwn(enumMetrics, key) && enumMetrics[key].has(value)) safe[key] = value;
  }
  return safe;
}

function formatLog(level: LogLevel, message: string, context?: Record<string, unknown>, error?: unknown): string {
  const logEntry: LogEntry = {
    timestamp: new Date().toISOString(),
    level,
    message,
  };

  if (context) {
    logEntry.context = diagnosticContext(context);
  }

  if (error) {
    logEntry.error = errorDiagnostics(error);
  }

  return JSON.stringify(logEntry);
}

export const logger = {
  info: (message: string, context?: Record<string, unknown>) => {
    console.log(formatLog('info', message, context));
  },
  warn: (message: string, context?: Record<string, unknown>) => {
    console.warn(formatLog('warn', message, context));
  },
  error: (message: string, error?: unknown, context?: Record<string, unknown>) => {
    console.error(formatLog('error', message, context, error));
  },
  debug: (message: string, context?: Record<string, unknown>) => {
    if (process.env.NODE_ENV !== 'production') { // Only log debug in non-production environments
      console.log(formatLog('debug', message, context));
    }
  },
};
