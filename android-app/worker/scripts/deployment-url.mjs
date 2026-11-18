// Wrangler's documented NDJSON output; never scrape console output or secret files.
export function deploymentUrl(output) {
  const records = output
    .split(/\r?\n/)
    .filter((line) => line.trim())
    .map((line) => JSON.parse(line));
  const deployment = records.findLast(
    (record) => record.type === "deploy" && record.version === 1,
  );
  const urls = (deployment?.targets ?? [])
    .filter((target) => typeof target === "string")
    .flatMap((target) => {
      try {
        const url = new URL(target);
        if (
          url.protocol === "https:" &&
          !url.username &&
          !url.password &&
          !url.search &&
          !url.hash &&
          url.pathname === "/"
        )
          return [url.origin];
      } catch {}
      return [];
    });
  const url =
    urls.find((value) => new URL(value).hostname.endsWith(".workers.dev")) ??
    urls[0];
  if (!url)
    throw Error(
      "No public HTTPS origin in deployment output. gateway.properties was not changed.",
    );
  return url;
}
