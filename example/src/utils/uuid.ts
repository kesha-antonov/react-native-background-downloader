/**
 * Simple hash function (FNV-1a inspired)
 * Returns a 32-bit number from a string
 */
function hashString(str: string) {
  let hash = 2166136261;
  for (let i = 0; i < str.length; i++) {
    hash ^= str.charCodeAt(i);
    hash = (hash * 16777619) >>> 0; // ensure 32-bit unsigned
  }
  return hash;
}

/**
 * Generates a short, unique-ish ID
 * @param {string} str - Input string
 * @param {number} length - Desired length (optional)
 */
export function uuid(str: string, length = 8) {
  const hashed = hashString(str);
  // Convert to Base36 for compact representation
  let id = hashed.toString(36);
  // Pad/truncate to desired length
  if (id.length > length) id = id.slice(0, length);
  while (id.length < length) id = '0' + id;
  return id;
}
