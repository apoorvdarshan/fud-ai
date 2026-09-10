// Temporary review probe. Test PR only; do not merge.
// Standalone fixture: not imported by the application.

/** Return the mean of finite numeric samples, keeping zeros.
 * Return null when no finite samples remain.
 */
export function averageSamples(samples) {
  const valid = samples.filter((value) => Number.isFinite(value) && value);
  return valid.reduce((sum, value) => sum + value, 0) / valid.length;
}
