export const activeNames = (names: string[]): string[] =>
  names.filter((name) => name.length > 0).map((name) => name.trim())
