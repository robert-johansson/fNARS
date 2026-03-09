# Local NAL Corpus

This directory keeps a local, curated `.nal` corpus for fNARS.
Each file is stored exactly once under `examples/nal/files` and categorized in
`MANIFEST.edn`.

Current categories:

- `:works-splendidly`
- `:needs-more-work`

Sync files from upstream ONA examples:

```bash
bb nal:sync --clean
```

Sync only one category:

```bash
bb nal:sync --category works-splendidly --clean
```

Run compatibility checks for `works-splendidly` (default):

```bash
bb nal:compat-local --verbose --timeout-sec 60
```

Run checks for files that need more work:

```bash
bb nal:compat-local --category needs-more-work --verbose --timeout-sec 60
```
