# Policy and rule documents

Files in this folder are the travel policy and guardrail corpus that the agents search with `search_policies`.

- **PDF** (`*.pdf`): each page is indexed as one section, and long pages are split into chunks.
- **Excel** (`*.xlsx`): each data row becomes one chunk, written as `Header: value; Header: value`. Fill in
  `policy-data-template.xlsx` (one rule per row) and save it with a new name.

Upload with `scripts/upload-policies.sh <stack-name>`. The upload triggers the ingest function, which replaces that
file's earlier chunks. Chunks are searchable after the policy cache window (`RAG_CACHE_SECONDS`, default 300 s).

`travel-policy-sample.pdf` and `policy-data-template.xlsx` are examples. Replace them with your own policies.
