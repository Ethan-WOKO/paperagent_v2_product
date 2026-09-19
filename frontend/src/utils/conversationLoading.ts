/** Start primary history immediately; a slow auxiliary request must not delay it. */
export async function loadConversationParts(
  primary: () => Promise<unknown>,
  auxiliary: Array<() => Promise<unknown>>,
): Promise<void> {
  const results = await Promise.allSettled([primary, ...auxiliary].map((load) => Promise.resolve().then(load)));
  const failed = results.find((result) => result.status === 'rejected');
  if (failed?.status === 'rejected') throw failed.reason;
}
