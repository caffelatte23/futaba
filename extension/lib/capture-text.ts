const captureSeparator = '\n\n';

export function buildCaptureText(selection?: string, url?: string): string {
  return [selection, url].filter((part) => part?.trim()).join('\n');
}

export function appendPendingCapture(
  existing: string | null,
  text: string,
): string {
  return existing ? `${existing}${captureSeparator}${text}` : text;
}

export function remainingPendingCapture(
  existing: string | null,
  saved: string,
): string | null {
  if (existing === saved) {
    return null;
  }
  const savedPrefix = saved + captureSeparator;
  if (existing?.startsWith(savedPrefix)) {
    return existing.slice(savedPrefix.length);
  }
  return existing;
}
