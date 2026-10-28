/**
 * TypeScript shapes for the SentinelAI API.
 *
 * These are hand-written mirrors of the FastAPI response payloads. They are
 * deliberately loose (optional fields) so a backend that reports an error or
 * an empty state still type-checks.
 */

export type Severity = 'critical' | 'high' | 'medium' | 'low' | 'info'
export type AlertStatus = 'open' | 'acknowledged' | 'resolved'
export type Origin = 'dataset' | 'simulated' | 'upload'
export type AlertOrigin = 'model' | 'simulated'
export type Verdict = 'malicious' | 'suspicious' | 'unknown' | 'private'

export interface DataSourceEntry {
  origin: Origin
  name: string
  count: number
  is_simulated: boolean
  description: string
}

export interface DashboardSummary {
  generated_at: string
  data_sources: {
    live_capture: boolean
    headline: string
    explanation: string
    sources: DataSourceEntry[]
  }
  kpis: {
    traffic_records: number
    scored_records: number
    threats_detected: number
    alerts_open: number
    alerts_acknowledged: number
    alerts_resolved: number
    alerts_total: number
    alerts_model_generated: number
    alerts_simulated: number
    critical_alerts: number
  }
  severity_counts: Record<string, number>
  status_counts: Record<string, number>
  truth_distribution: Record<string, { count: number; label: string; severity: Severity }>
  predicted_distribution: Record<string, { count: number; label: string; severity: Severity }>
  hourly_traffic: number[]
  top_target_ports: { port: number; n: number; attacks: number }[]
  protocol_counts: { protocol: string; n: number }[]
  recent_alerts: Alert[]
  model: { available: boolean; model_version?: string; trained_at?: string; metrics_loaded?: boolean }
  missing_configuration: string[]
  window_days: number
  simulated_visible?: boolean
}

export interface Alert {
  id: number
  severity: Severity
  title: string
  category?: string | null
  category_label: string
  source_ip?: string | null
  destination_ip?: string | null
  description?: string | null
  confidence?: number | null
  status: AlertStatus
  origin: AlertOrigin
  origin_label?: string
  is_simulated: boolean
  origin_detail?: string | null
  occurred_at?: string | null
  created_at: string
  updated_at: string
  severity_rank?: number
  record_id?: number | null
  prediction_id?: number | null
}

export interface TrafficRecord {
  id: number
  captured_at: string
  source_ip?: string | null
  destination_ip?: string | null
  source_port?: number | null
  destination_port?: number | null
  protocol?: string | null
  flow_duration_ms?: number | null
  total_packets?: number | null
  total_bytes?: number | null
  fwd_bytes?: number | null
  bwd_bytes?: number | null
  packets_per_sec?: number | null
  bytes_per_sec?: number | null
  label?: string | null
  category?: string | null
  category_label: string
  severity: Severity
  is_attack: boolean
  origin: Origin
  origin_detail?: string | null
  is_simulated: boolean
  has_features: boolean
  batch_id?: string | null
  features: Record<string, number | null> | null
  prediction: {
    predicted_category: string
    predicted_label: string
    confidence: number | null
    is_attack: boolean
    model_version?: string
    probabilities?: Record<string, number>
  } | null
  alerts?: Alert[]
}

export interface Paged<T> {
  items: T[]
  page: number
  page_size: number
  total: number
  pages: number
}

export interface TrafficSummary {
  total: number
  by_origin: Record<string, number>
  by_category: Record<string, number>
  by_protocol: Record<string, number>
  scored: number
  dataset_rows_available: number
  dataset_zip_present: boolean
  simulated_visible: boolean
}

export interface AlignmentReport {
  matched: string[]
  missing: string[]
  unexpected: string[]
  usable: boolean
  required_min_features: number
  expected_count: number
  matched_count: number
}

export interface UploadRow {
  row: number
  category: string
  category_label: string
  is_attack: boolean
  confidence: number
  truth: string | null
  correct: boolean | null
  top_probabilities: Record<string, number>
}

export interface UploadResponse {
  batch_id: string
  filename: string
  model_version: string
  rows_received: number
  rows_scored: number
  truncated: boolean
  attack_rows: number
  benign_rows: number
  alerts_created: number
  alerts_suppressed: number
  confidence_threshold: number
  predicted_distribution: Record<string, { count: number; label: string }>
  missing_values: {
    missing_cells: number
    rows_with_missing: number
    per_column: Record<string, number>
  }
  alignment: AlignmentReport
  errors: string[]
  rows: UploadRow[]
  is_simulated: boolean
  note: string
}

export interface ClassMetric {
  key?: string
  label: string
  precision: number
  recall: number
  f1: number
  support: number
  severity?: Severity
}

export interface ModelMetrics {
  available: boolean
  reason?: string
  model_version?: string
  model_type?: string
  trained_at?: string
  sklearn_version?: string
  python_version?: string
  seed?: number
  hyperparameters?: Record<string, number | string>
  provenance?: {
    dataset: string
    publisher: string
    paper: string
    official_url: string
    mirror_url: string
    full_dataset_rows: number
    rows_used_for_training: number
    class_counts: Record<string, number>
  }
  feature_count?: number
  model?: {
    accuracy: number
    balanced_accuracy: number
    precision_macro: number
    recall_macro: number
    f1_macro: number
    precision_weighted: number
    recall_weighted: number
    f1_weighted: number
    per_class: Record<string, { precision: number; recall: number; f1: number; support: number }>
    labels: string[]
    label_names: string[]
    confusion_matrix: number[][]
    train_seconds?: number
  }
  confusion_matrix_normalised?: number[][]
  class_rows?: ClassMetric[]
  baselines?: Record<string, { accuracy: number; f1_macro: number; description?: string }>
  feature_importances?: { feature: string; importance: number }[]
  notes?: string[]
  elapsed_seconds?: number
  model_file_bytes?: number
}

export interface ModelInfo {
  available: boolean
  reason?: string
  model_version?: string
  model_type?: string
  trained_at?: string
  sklearn_version?: string
  classes?: string[]
  features?: string[]
  feature_count?: number
  file_bytes?: number
  metrics?: { model?: ModelMetrics['model'] }
}

export interface DetectionSchema {
  model: ModelInfo
  expected_features: string[]
  expected_feature_count: number
  categories: { key: string; label: string; severity: Severity; is_attack: boolean }[]
  identifier_columns_ignored: string[]
  notes: string[]
  samples: { filename: string; bytes: number; url: string }[]
  limits: { max_bytes: number; max_rows: number }
}

export interface IntelSource {
  name: string
  type: string
  configured: boolean
  env_var: string
  enabled: boolean
  entries?: number
  age_hours?: number | null
  error?: string | null
}

export interface IntelResult {
  source: string
  is_live: boolean
  verdict: Verdict
  score: number | null
  detail: string
  categories: string[]
  error?: string | null
  feed?: { url?: string; entries?: number; age_hours?: number | null; last_feed_update?: string | null }
}

export interface IntelLookupResponse {
  ip: string
  verdict: Verdict
  score: number | null
  primary_source: string
  is_live: boolean
  sources: IntelResult[]
  degradations: string[]
  disclaimer: string
  queried_at: string
  configured_sources: IntelSource[]
}

export interface IntelHistoryItem {
  id: number
  ip: string
  verdict: Verdict
  score: number | null
  source: string
  is_live: boolean
  categories: string[]
  detail?: string | null
  error?: string | null
  queried_at: string
}

export interface ReportSummary {
  generated_at: string
  traffic: {
    total: number
    by_origin: Record<string, number>
    by_category: Record<string, { count: number; label: string }>
    by_protocol: Record<string, number>
    volume: Record<string, number>
  }
  alerts: {
    total: number
    by_severity: Record<string, number>
    by_origin: Record<string, number>
    by_status: Record<string, number>
  }
  predictions: {
    total: number
    attacks: number
    benign: number
    by_category: Record<string, number>
    confusion_vs_ground_truth: { actual: string; predicted: string; n: number }[]
    batches: number
  }
  threat_intel: { lookups: number }
  model: {
    available: boolean
    model_version?: string
    model_type?: string
    trained_at?: string
    accuracy?: number
    precision_macro?: number
    recall_macro?: number
    f1_macro?: number
    f1_weighted?: number
    test_rows?: number
    baselines?: Record<string, { accuracy?: number; f1_macro?: number }>
  }
  top_sources: { ip: string; flows: number; attacks: number }[]
  export_kinds: string[]
  data_caveat: string
}

export interface SettingsPayload {
  values: Record<string, string | number | boolean>
  meta: Record<
    string,
    {
      label: string
      description: string
      type: 'string' | 'float' | 'bool'
      affects: string
      default: string
      is_default: boolean
      min?: number
      max?: number
    }
  >
  model: { available: boolean; model_version?: string }
  environment: Record<string, string | number | boolean>
  api_keys: { env_var: string; configured: boolean; provider: string; used_by: string }[]
}

export interface ApiError {
  detail: string | unknown
  status: number
}
