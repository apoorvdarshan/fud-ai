/**
 * Leaf actions in the Home + food menu (excluding water and fasting).
 * Mirrors `FoodLogMethod.swift` / `FoodLogMethod.kt`.
 */

export const foodLogMethods = [
  'camera',
  'photos',
  'barcode',
  'text',
  'voice',
  'manual',
  'siri_phrases',
  'recent',
  'frequent',
  'favorites',
  'copy_from_day',
] as const;

export type FoodLogMethod = (typeof foodLogMethods)[number];

/** Methods the Expo + menu can actually open. Siri stays native-only. */
export const addMenuFoodLogMethods: readonly FoodLogMethod[] = foodLogMethods.filter((method) => method !== 'siri_phrases');

export function isFoodLogMethod(value: string): value is FoodLogMethod {
  return (foodLogMethods as readonly string[]).includes(value);
}

export function foodLogMethodTitle(method: FoodLogMethod): string {
  switch (method) {
    case 'camera':
      return 'Camera';
    case 'photos':
      return 'Photos';
    case 'barcode':
      return 'Barcode';
    case 'text':
      return 'Text Input';
    case 'voice':
      return 'Voice';
    case 'manual':
      return 'Manual Entry';
    case 'siri_phrases':
      return 'Siri Phrases';
    case 'recent':
      return 'Recent';
    case 'frequent':
      return 'Frequent';
    case 'favorites':
      return 'Favorites';
    case 'copy_from_day':
      return 'Copy from Day';
  }
}

export function foodLogMethodSystemImage(method: FoodLogMethod): string {
  switch (method) {
    case 'camera':
      return 'camera.fill';
    case 'photos':
      return 'photo.on.rectangle';
    case 'barcode':
      return 'barcode.viewfinder';
    case 'text':
      return 'character.cursor.ibeam';
    case 'voice':
      return 'mic.fill';
    case 'manual':
      return 'square.and.pencil';
    case 'siri_phrases':
      return 'waveform.circle.fill';
    case 'recent':
      return 'clock.fill';
    case 'frequent':
      return 'repeat';
    case 'favorites':
      return 'heart.fill';
    case 'copy_from_day':
      return 'calendar';
  }
}
