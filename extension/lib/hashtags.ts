const hashtagPattern = /(?<![\p{L}\p{N}/&])[#＃]([\p{L}\p{N}_-]+)/gu;

export function hashtagKey(name: string): string {
  return Array.from(name, (letter) => {
    const uppercase = letter.toUpperCase();
    // Match Android's per-character casing, including dotted I and final sigma.
    const lowercase = (
      Array.from(uppercase).length === 1 ? uppercase : letter
    ).toLowerCase();
    return lowercase === 'i\u0307' ? 'i' : lowercase;
  }).join('');
}

export function parseHashtags(text: string): string[] {
  const tags = new Map<string, string>();
  for (const [, tag] of text.matchAll(hashtagPattern)) {
    if (tag !== undefined) {
      const key = hashtagKey(tag);
      if (!tags.has(key)) {
        tags.set(key, tag);
      }
    }
  }
  return [...tags.values()];
}
