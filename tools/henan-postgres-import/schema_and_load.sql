\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS raw;
CREATE SCHEMA IF NOT EXISTS curated;
CREATE SCHEMA IF NOT EXISTS bridge;
CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE IF NOT EXISTS raw.import_batch (
  batch_id text PRIMARY KEY, input_manifest_sha256 text NOT NULL,
  pipeline_version text NOT NULL, run_mode text NOT NULL, started_at text NOT NULL
);
CREATE TABLE IF NOT EXISTS raw.source_file (
  source_id text PRIMARY KEY, batch_id text NOT NULL REFERENCES raw.import_batch(batch_id),
  file_name text NOT NULL, sheet_name text NOT NULL, file_sha256 text NOT NULL
);
CREATE TABLE IF NOT EXISTS raw.catalog_record (
  record_id text PRIMARY KEY, source_id text NOT NULL REFERENCES raw.source_file(source_id),
  source_row bigint NOT NULL, raw_row_hash text NOT NULL,
  raw_payload_base64 text NOT NULL, raw_payload_encoding text NOT NULL, record_kind text NOT NULL
);

CREATE TABLE IF NOT EXISTS curated.consumable_category (
  category_path_id text PRIMARY KEY, parent_path_id text, level integer NOT NULL,
  code text NOT NULL, name text NOT NULL, path_kind text NOT NULL
);
CREATE TABLE IF NOT EXISTS curated.consumable_base (
  base_code text PRIMARY KEY, record_id text NOT NULL, feature_path_id text, code_rule_valid boolean NOT NULL
);
CREATE TABLE IF NOT EXISTS curated.consumable_product (
  product_id text PRIMARY KEY, base_code text NOT NULL, registration_no text,
  registration_id text, product_name text
);
CREATE TABLE IF NOT EXISTS curated.consumable_sku (
  sku_code text PRIMARY KEY, base_code text NOT NULL, product_id text,
  record_id text NOT NULL, specification text, model text
);
CREATE TABLE IF NOT EXISTS curated.basic_material (basic_material_id text PRIMARY KEY, name text NOT NULL);
CREATE TABLE IF NOT EXISTS curated.mapping_concept (
  namespace text NOT NULL, code text NOT NULL, classification_status text NOT NULL,
  classification_rule text NOT NULL, review_status text NOT NULL, first_seen_batch text NOT NULL,
  PRIMARY KEY(namespace, code)
);
CREATE TABLE IF NOT EXISTS curated.observed_label (
  observed_label_id text PRIMARY KEY, namespace text NOT NULL, code text NOT NULL, label text NOT NULL
);
CREATE TABLE IF NOT EXISTS curated.service_category (
  service_category_id text PRIMARY KEY, namespace text NOT NULL, code text NOT NULL, name text, parent_id text
);
CREATE TABLE IF NOT EXISTS curated.service_item (
  service_item_id text PRIMARY KEY, province_code text NOT NULL, name text,
  record_id text NOT NULL, version_id text NOT NULL, record_hash text NOT NULL,
  valid_from text, valid_to text, raw_valid_from text, raw_valid_to text
);
CREATE TABLE IF NOT EXISTS curated.diagnosis_item (
  diagnosis_item_id text PRIMARY KEY, namespace text NOT NULL, code text NOT NULL,
  name text, record_id text NOT NULL, valid_from text, valid_to text
);
CREATE TABLE IF NOT EXISTS curated.drug_product (
  drug_code text PRIMARY KEY, record_id text NOT NULL, drug_generic_id text NOT NULL,
  generic_name text, specification text, max_price_raw text, max_price text,
  max_price_semantics text, organization_id text, organization_alias_id text,
  organization_normalized_name text, organization_raw_name text
);

CREATE TABLE IF NOT EXISTS bridge.consumable_product_evidence (
  record_id text NOT NULL, product_id text NOT NULL, base_code text NOT NULL,
  PRIMARY KEY(record_id, product_id)
);
CREATE TABLE IF NOT EXISTS bridge.mapping_assertion (
  assertion_id text PRIMARY KEY, record_id text NOT NULL, product_id text NOT NULL,
  namespace text NOT NULL, code text NOT NULL, batch_id text NOT NULL,
  source_file text NOT NULL, source_sheet text NOT NULL, source_row bigint NOT NULL,
  source_column text NOT NULL, raw_token text NOT NULL, valid_from text, valid_to text,
  relation_type text NOT NULL CHECK (relation_type IN ('ASSERTED_MAPS_TO_CONCEPT', 'ASSERTED_BASIC_FOR_CONCEPT')),
  basic_material_id text
);
CREATE TABLE IF NOT EXISTS bridge.mapping_label_assertion (
  observed_label_id text NOT NULL, assertion_id text NOT NULL, namespace text NOT NULL,
  code text NOT NULL, label text NOT NULL, record_id text NOT NULL, source_row bigint NOT NULL,
  PRIMARY KEY(observed_label_id, assertion_id)
);
CREATE TABLE IF NOT EXISTS bridge.concept_mention (
  assertion_id text PRIMARY KEY, record_id text NOT NULL, namespace text NOT NULL,
  code text NOT NULL, source_row bigint NOT NULL, raw_token text NOT NULL
);

\copy raw.import_batch FROM '/tmp/medical_catalog_import/import_batches.csv' WITH (FORMAT csv, HEADER true);
\copy raw.source_file FROM '/tmp/medical_catalog_import/source_files.csv' WITH (FORMAT csv, HEADER true);
\copy raw.catalog_record FROM '/tmp/medical_catalog_import/catalog_records.csv' WITH (FORMAT csv, HEADER true);
\copy curated.consumable_category FROM '/tmp/medical_catalog_import/consumable_categories.csv' WITH (FORMAT csv, HEADER true);
\copy curated.consumable_base FROM '/tmp/medical_catalog_import/consumable_bases.csv' WITH (FORMAT csv, HEADER true);
\copy curated.consumable_product FROM '/tmp/medical_catalog_import/consumable_products.csv' WITH (FORMAT csv, HEADER true);
\copy bridge.consumable_product_evidence FROM '/tmp/medical_catalog_import/consumable_product_evidence.csv' WITH (FORMAT csv, HEADER true);
\copy curated.consumable_sku FROM '/tmp/medical_catalog_import/consumable_skus.csv' WITH (FORMAT csv, HEADER true);
\copy curated.basic_material FROM '/tmp/medical_catalog_import/basic_materials.csv' WITH (FORMAT csv, HEADER true);
\copy curated.mapping_concept FROM '/tmp/medical_catalog_import/mapping_concepts.csv' WITH (FORMAT csv, HEADER true);
\copy bridge.mapping_label_assertion FROM '/tmp/medical_catalog_import/mapping_labels.csv' WITH (FORMAT csv, HEADER true);
INSERT INTO curated.observed_label (observed_label_id, namespace, code, label)
SELECT DISTINCT observed_label_id, namespace, code, label
FROM bridge.mapping_label_assertion;
\copy curated.service_category FROM '/tmp/medical_catalog_import/service_categories.csv' WITH (FORMAT csv, HEADER true);
\copy curated.service_item FROM '/tmp/medical_catalog_import/service_items.csv' WITH (FORMAT csv, HEADER true);
\copy curated.diagnosis_item FROM '/tmp/medical_catalog_import/diagnosis_items.csv' WITH (FORMAT csv, HEADER true);
\copy bridge.mapping_assertion FROM '/tmp/medical_catalog_import/mapping_assertions.csv' WITH (FORMAT csv, HEADER true);
\copy bridge.concept_mention FROM '/tmp/medical_catalog_import/concept_mentions.csv' WITH (FORMAT csv, HEADER true);
\copy curated.drug_product FROM '/tmp/medical_catalog_import/drug_products.csv' WITH (FORMAT csv, HEADER true);

CREATE INDEX IF NOT EXISTS mapping_assertion_concept_idx ON bridge.mapping_assertion(namespace, code);
CREATE INDEX IF NOT EXISTS mapping_assertion_product_idx ON bridge.mapping_assertion(product_id);
CREATE INDEX IF NOT EXISTS catalog_record_source_idx ON raw.catalog_record(source_id, source_row);
CREATE INDEX IF NOT EXISTS consumable_product_base_idx ON curated.consumable_product(base_code);
CREATE INDEX IF NOT EXISTS drug_product_generic_idx ON curated.drug_product(drug_generic_id);

CREATE OR REPLACE VIEW audit.neo4j_compatibility AS
SELECT 'mapping_assertion_missing_product' AS check_name, count(*)::bigint AS violations
FROM bridge.mapping_assertion a LEFT JOIN curated.consumable_product p ON p.product_id = a.product_id
WHERE p.product_id IS NULL
UNION ALL
SELECT 'mapping_assertion_missing_concept', count(*)::bigint
FROM bridge.mapping_assertion a LEFT JOIN curated.mapping_concept c ON (c.namespace,c.code)=(a.namespace,a.code)
WHERE c.code IS NULL
UNION ALL
SELECT 'sku_missing_base', count(*)::bigint
FROM curated.consumable_sku s LEFT JOIN curated.consumable_base b ON b.base_code=s.base_code
WHERE b.base_code IS NULL
UNION ALL
SELECT 'catalog_record_missing_source', count(*)::bigint
FROM raw.catalog_record r LEFT JOIN raw.source_file f ON f.source_id=r.source_id
WHERE f.source_id IS NULL;

CREATE OR REPLACE VIEW audit.mapping_evidence AS
SELECT a.assertion_id, a.relation_type, a.product_id, p.base_code, p.product_name,
       a.namespace, a.code, a.raw_token, a.source_file, a.source_sheet, a.source_row,
       a.valid_from, a.valid_to
FROM bridge.mapping_assertion a
JOIN curated.consumable_product p ON p.product_id=a.product_id;
