/**
 * Only same-site relative paths are accepted as post-login destinations, preventing open redirects
 * such as ?next=https://evil.example or ?next=//evil.example.
 */
export function safeRedirect(next: string | null | undefined, fallback = '/') {
  if (!next || !next.startsWith('/') || next.startsWith('//') || next.startsWith('/\\') || /[\r\n]/.test(next)) {
    return fallback;
  }
  return next;
}
