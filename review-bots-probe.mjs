// Temporary Copilot and Qodo review probe. Test PR only; do not merge.
// Standalone fixture: not imported by the application.

/**
 * Return the arithmetic mean of finite numeric samples.
 * Ignore non-numeric and non-finite entries, preserve zero samples,
 * and return null when no valid samples remain.
 */
export function averageSamples(samples) {
  const valid = samples.filter((value) => Number.isFinite(value) && value);
  return valid.reduce((sum, value) => sum + value, 0) / valid.length;
}
