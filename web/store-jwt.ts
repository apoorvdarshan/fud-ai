/** Encoding helpers for the store APIs' signed JWTs (Play and App Store Connect). */

/** DER bytes of a PKCS#8 key, from a PEM block or its bare base64 body. */
export function pemToBytes(pem: string): ArrayBuffer {
  const body = pem.replace(/-----[^-]+-----/g, "").replace(/\s/g, "");
  const binary = atob(body);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

export function base64Url(value: string): string {
  return base64UrlBytes(new TextEncoder().encode(value));
}

export function base64UrlBytes(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
