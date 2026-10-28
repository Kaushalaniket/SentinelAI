# SentinelAI — Architecture

This document explains how the system is put together and *why*, for someone who has to defend
the design in a viva. It assumes you have read the top-level [README](../README.md).

---

## 1. Design principles

**1. Provenance is a first-class field, not a UI label.**
Every stored row carries an `origin` (`dataset` / `simulated` / `upload`), and every alert carries
an `origin` (`model` / `simulated`). The UI reads those columns to draw badges. There is no
separate "show simulated" flag that could drift out of sync, and no code path that can present a
synthetic row as a detection.

**2. One pipeline object, used by training and by serving.**
Training and inference both go through the same `sklearn.pipeline.Pipeline`, so the imputer's
medians and the scaler's statistics are the *same objects* at train time and at request time.
A common class of deployment bug (preprocessing differently at inference) is structurally
impossible here.

**3. Degrade, never crash.**
A missing model returns HTTP 503 with instructions. A missing API key returns 200 with the
offline answer plus a `degradations` list explaining what was skipped. A failed blocklist download
falls back to the stale cache. A render error shows a reload card rather than a white screen.

**4. Simulated data must be structurally unable to become a detection.**
`generate_simulated_traffic()` writes `features = NULL`, and `score_records()` selects only rows
where `features IS NOT NULL`. Synthetic rows therefore cannot be fed to the model at all — the
guarantee is enforced by a SQL predicate, not by a comment.

---

## 2. High-level data flow

```
                       ┌──────────────────────────────────────────────┐
   MachineLearningCSV  │  OFFLINE PIPELINE (run manually)             │
   .zip  ────────────► │  app/ml/dataset.py                           │
   8 CSVs, 2.83M rows  │   • stream per file, cap per category        │
                       │   • 15 raw labels → 9 categories             │
                       │   • inf→NaN, float32, drop constants         │
                       │           │                                  │
                       │           ▼  train_sample.parquet (288k×71)  │
                       │  app/ml/train.py                             │
                       │   • stratified 80/20 split (seed 42)         │
                       │   • Pipeline(imputer→scaler→RF)  fit(train)  │
                       │   • evaluate on test → metrics               │
                       │   • compare vs 2 baselines                   │
                       └───────────┬──────────────────────────────────┘
                                   │  model.joblib + metrics.json
                                   ▼
┌──────────────────────────────────────────────────────────────────────────┐
│  ONLINE API  (FastAPI, uvicorn)                                          │
│                                                                          │
│  GeneratedLabelledFlows.zip ─► seed.load_dataset_records() ─┐            │
│  synthetic generator ────────► seed.generate_simulated() ───┤            │
│  uploaded CSV ───────────────► routers/detection.upload() ──┤            │
│                                                             ▼            │
│                                              SQLite  traffic_records     │
│                                                              │           │
│                                   seed.score_records() ◄─────┘           │
│                                   predictor.predict_frame()              │
│                                         │                                │
│                          ┌──────────────┴──────────────┐                 │
│                          ▼                             ▼                 │
│                     predictions                  alerts (origin=model)   │
│                          │                                                │
│                          └──────────► dashboard / traffic / reports      │
└──────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Database schema

One file: `backend/data/sentinelai.db`. Created idempotently by `db.init_db()`.

```sql
traffic_records  -- one row per network flow
  id, captured_at, source_ip, destination_ip, source_port, destination_port,
  protocol, flow_duration_ms, total_packets, total_bytes, fwd_bytes, bwd_bytes,
  packets_per_sec, bytes_per_sec,
  label          -- ground truth when known (dataset/upload), NULL when simulated
  category       -- benign | ddos | dos | portscan | webattack | bruteforce |
                  -- bot | infiltration | heartbleed
  origin         -- 'dataset' | 'simulated' | 'upload'          ← provenance
  origin_detail  -- human sentence explaining exactly where it came from
  batch_id, features (JSON), created_at

predictions
  id, record_id → traffic_records, batch_id, predicted_category, is_attack,
  confidence, probabilities (JSON), model_version, created_at

alerts
  id, severity, title, category, source_ip, destination_ip, description,
  confidence, status (open|acknowledged|resolved),
  origin ('model' | 'simulated'),   ← provenance
  origin_detail, occurred_at, record_id, prediction_id, created_at, updated_at

detection_batches   id (uuid12), filename, row counts, model_version, errors(JSON)
intel_lookups       ip, verdict, score, source, is_live, categories, detail, error
app_settings        key, value, updated_at
```

Indexes exist on every column used in a `WHERE` or `ORDER BY` of a list endpoint
(`created_at`, `origin`, `category`, `severity`, `status`, `record_id`, `batch_id`, `ip`).

**Why JSON blobs for `features` / `probabilities`?** Both are read as a whole and never queried
by key, so a JSON text column keeps the schema small while remaining trivially exportable to CSV.
The genuinely queried fields (severity, origin, category, status) are real columns.

---

## 4. The ML subsystem

### 4.1 Module responsibilities

| Module | Responsibility | Purity |
|---|---|---|
| `labels.py` | 15 raw labels → 9 categories, display metadata, severity | pure, no I/O |
| `preprocess.py` | column stripping/de-duplication, `inf`→`NaN`, feature alignment | pure |
| `dataset.py` | stream the zip → down-sampled parquet | I/O, no ML |
| `train.py` | split → fit → evaluate → persist | I/O, no HTTP |
| `predictor.py` | lazy load + score frames | no HTTP |

Keeping `labels` and `preprocess` pure is what makes the 47-test suite fast and why the same
functions can be imported by the upload endpoint, the seeder, and the tests.

### 4.2 Feature alignment (the part every real IDS project gets wrong)

Uploaded files never arrive in exactly the right shape. `align_features()`:

1. strips header whitespace (`" Destination Port"` → `"Destination Port"`)
2. de-duplicates repeated headers (`Fwd Header Length` → `Fwd Header Length__2`)
3. ignores identifier columns: `Flow ID`, `Source IP`, `Destination IP`, `Timestamp`, `Label`, …
4. coerces every remaining cell to numeric; unparseable → `NaN`
5. re-indexes onto the training feature list, so **missing** columns become `NaN` (later imputed)
6. computes a report: `matched`, `missing`, `unexpected`, `usable`

`usable` requires at least `max(10, 50 % of features)` to match. Below that the API returns
**422** with the full report rather than silently guessing — and the response tells the user
*which* columns were absent.

### 4.3 Class imbalance

The raw dataset is 80 % benign. Two decisions handle that:

- **Sampling.** Rare classes are kept 100 %; common ones are capped (see README table). Benign
  additionally gets a 12k *per-file* cap so it is drawn from all eight capture days rather than
  the first file read — otherwise the model would see only Friday's benign traffic.
- **`class_weight="balanced_subsample"`** re-weights each bootstrap sample so minority classes
  contribute meaningfully to every split.

Even so, Heartbleed has 11 rows total. The project reports that honestly instead of merging the
class away to make the numbers look better.

### 4.4 Why RandomForest

Examiner-defensible reasons, in order:

1. It provides `feature_importances_` for free, which the dashboard plots.
2. It is robust to unscaled, heavy-tailed flow features (byte counts span 6 orders of magnitude).
3. `max_leaf_nodes=1024` bounds the model to **4.6 MB** while keeping macro-F1 at 94.99 % — an
   unconstrained forest on 288k rows would be hundreds of MB.
4. It is deterministic given `random_state=42`, so re-running reproduces the same numbers.

Logistic regression is trained on the same split as a baseline (96.51 % accuracy, 77.61 % macro-F1)
which is exactly the kind of comparison a project report needs.

### 4.5 Evaluation discipline

- The split is **stratified** so every class appears in both halves.
- `train_test_split` happens *before* any fitting; `metrics.json` is written only from `y_test`.
- A `DummyClassifier(most_frequent)` baseline is reported alongside (31.25 % accuracy, 5.29 %
  macro-F1) — the number that proves accuracy alone is a meaningless claim on this dataset.
- Confusion matrix rows are row-normalised for display only; the raw counts are preserved and the
  test asserts `sum(row) == per_class[label].support`.
- Training time, seed, scikit-learn version, hyper-parameters and file size are all recorded.

---

## 5. Request lifecycle examples

### 5.1 CSV upload

```
POST /api/detect/upload          multipart/form-data
  │
  ├─ reject 415  filename does not end in .csv
  ├─ reject 400  empty body
  ├─ reject 415  body > 30 MB
  ├─ reject 503  model.joblib missing (message says how to fix it)
  ├─ reject 422  unparseable CSV / header-only / fewer than 50 % of features match
  │              ↳ response includes alignment.missing[] so the UI can render it
  │
  ├─ count NaN/inf cells per column  → missing_values block
  ├─ predict_frame(matrix)           → per-row {category, confidence, probabilities}
  ├─ INSERT traffic_records (origin='upload', features=JSON)
  ├─ INSERT predictions
  ├─ INSERT alerts  WHERE is_attack AND confidence ≥ settings.confidence_threshold
  └─ INSERT detection_batches (errors JSON: imputation notes, truncation, no label column)
```

Capped at 20,000 rows per file; larger files are truncated and the response says so.

### 5.2 Threat-intelligence lookup

```
GET /api/intel/lookup?ip=185.220.101.44
  │
  ├─ pydantic ipaddress validation           → 422 on garbage input
  ├─ AbuseIPDB  : key present?  no  → degradations[] "key not configured"
  ├─ VirusTotal : key present?  no  → degradations[] "key not configured"
  ├─ offline IPsum cache
  │     ├─ private/reserved address → verdict 'private', lookup not applicable
  │     ├─ in cache, ≥10 lists      → 'malicious'
  │     ├─ in cache, 3–9 lists      → 'suspicious'
  │     ├─ in cache, 1–2 lists      → 'unknown'  (weak signal, stated explicitly)
  │     └─ absent                  → 'unknown'  (NOT 'safe')
  │
  ├─ choose the live result as primary when one succeeded, else the offline one
  ├─ persist to intel_lookups with is_live flag
  └─ response carries `disclaimer` + `degradations[]` + per-source detail
```

If a configured remote API throws (timeout, 401, 5xx) the exception is caught, appended to
`degradations`, and the offline answer is still returned with **HTTP 200**. A missing key is a
normal state, not an error.

---

## 6. Front-end design

```
main.tsx → ErrorBoundary → App (BrowserRouter)
                              └─ Shell (sidebar, topbar, footer, <Outlet/>)
                                   ├─ /            Dashboard
                                   ├─ /traffic     Traffic Analysis
                                   ├─ /detection   AI Detection (3 tabs)
                                   ├─ /intel       Threat Intelligence
                                   ├─ /alerts      Alerts
                                   ├─ /reports     Reports
                                   ├─ /settings    Settings
                                   └─ *            NotFound
```

**`lib/useApi.ts`** — one hook for every page. Returns `{data, loading, error, refetch}`, aborts
in-flight requests when the path changes, and skips the spinner on background refreshes so tables
do not flash.

**`lib/api.ts`** — every failure becomes an `ApiError` carrying FastAPI's `detail`. A dropped
connection becomes *"Cannot reach the SentinelAI backend. Is `uvicorn` still running?"* instead of
an unhandled rejection.

**State handling** — each page renders exactly four states: loading (skeleton + spinner), error
(with retry), empty (with a call to action), and populated. Filter state lives in the URL query
string, so a filtered view can be shared or reloaded.

**Design system** — `index.css` declares a Tailwind v4 `@theme` (navy surfaces `ink-900`…`ink-500`,
cyan accent `#22d3ee`, five severity colours) plus `.panel`, `.field`, `.btn`, `.label-mono`
primitives. Everything else is composed from `components/ui.tsx` (`Panel`, `StatCard`,
`SeverityBadge`, `NoticeBanner`, `Pagination`, `EmptyState`, …).

**No icon or chart dependency beyond Recharts** — the ~20 icons are inline SVG, which keeps the
bundle at 760 KB (224 KB gzip).

---

## 7. Concurrency & performance

- SQLite runs in **WAL** mode with a 30 s busy timeout, so reads are not blocked by the bootstrap
  writer.
- The bootstrap seeder runs in a daemon thread started by the FastAPI lifespan, so the first
  `/api/health` responds immediately with `bootstrap.running = true`.
- Model loading is behind a `threading.Lock` with a module-level cache — the 4.6 MB file is
  unpickled once per process.
- The dataset importer streams one CSV at a time; peak RSS ≈ 220 MB for an 885 MB archive.
- List endpoints filter, sort and paginate **in SQL**, then decorate only the current page.

---

## 8. Security posture

This is an offline demonstration, not a hardened product — but the obvious things are done:

| Concern | Handling |
|---|---|
| SQL injection | Every query is parameterised; no string interpolation of user input into SQL |
| File upload | Size cap 30 MB, row cap 20k, `.csv` extension check, `pandas` parse (no code execution) |
| CSV injection on export | Values are written by `csv.writer`; open exported files in a spreadsheet cautiously |
| API secrets | Read from environment only — never stored in SQLite, never returned by any endpoint |
| CORS | Explicit allow-list (localhost:5173/3000), not `*` |
| Dev server host | Vite allow-lists only the preview hostname; a spoofed `Host` header returns **403** |
| Unknown API routes | Return JSON 404, never the HTML shell |
| Unhandled exceptions | A global handler returns a structured 500 |

There is **no authentication**. Do not deploy this on the public internet without adding it.

---

## 9. Testing strategy

| Layer | Approach |
|---|---|
| ML units | Pure-function tests: label mapping, alignment, `inf` handling, probability sums |
| ML integrity | Confusion-matrix rows must equal the per-class support; the model must beat the baseline |
| API contract | `TestClient` against a **temporary SQLite file** pre-seeded so bootstrap short-circuits |
| Negative paths | 400/404/415/422 cases for uploads, filters, settings and IP validation |
| Real inference | Two tests upload the actual sample CSVs and assert row counts and imputation notes |

The suite runs in **2.85 s**. Model-dependent tests use `skipif(not model_exists())` so a fresh
clone without a trained model gets *skipped*, not *failed*.

---

## 10. Deployment notes

- **Single process:** `npm run build` then `uvicorn app.main:app`. FastAPI detects
  `frontend/dist`, mounts `/assets`, serves static files, and falls back to `index.html` for SPA
  routes (while keeping `/api/*` as JSON 404).
- **Two processes:** Vite dev server on 3000 proxying `/api` → 8000. This is what the live preview
  uses.
- The model, metrics and sample CSVs are committed; the 495 MB raw dataset, the SQLite file and
  `node_modules` are git-ignored and regenerate from the documented commands.
