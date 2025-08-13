
type LogLevel = 'info' | 'warn' | 'error' | 'debug';

interface LogEntry {
  timestamp: string;
  level: LogLevel;
  message: string;
  context?: Record<string, any>;
  error?: {
    name: string;
    message: string;
    stack?: string;
  };
}

function formatLog(level: LogLevel, message: string, context?: Record<string, any>, error?: Error): string {
  const logEntry: LogEntry = {
    timestamp: new Date().toISOString(),
    level,
    message,
  };

  if (context) {
    logEntry.context = context;
  }

  if (error) {
    logEntry.error = {
      name: error.name,
      message: error.message,
      stack: error.stack,
    };
  }

  return JSON.stringify(logEntry);
}

export const logger = {
  info: (message: string, context?: Record<string, any>) => {
    console.log(formatLog('info', message, context));
  },
  warn: (message: string, context?: Record<string, any>) => {
    console.warn(formatLog('warn', message, context));
  },
  error: (message: string, error?: Error, context?: Record<string, any>) => {
    console.error(formatLog('error', message, context, error));
  },
  debug: (message: string, context?: Record<string, any>) => {
    if (process.env.NODE_ENV !== 'production') { // Only log debug in non-production environments
      console.log(formatLog('debug', message, context));
    }
  },
};
