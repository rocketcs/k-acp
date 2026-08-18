#!/usr/bin/env python3
"""Reliable parameterized Neo4j loader for build_dataset.py CSV contracts."""
from __future__ import annotations
import argparse, csv, random, time
from itertools import islice
from pathlib import Path
from neo4j import GraphDatabase
from neo4j.exceptions import TransientError

def rows(path: Path, size: int):
    with path.open(encoding='utf-8', newline='') as f:
        reader, batch = csv.DictReader(f), []
        for row in reader:
            batch.append(row)
            if len(batch) == size:
                yield batch; batch = []
        if batch: yield batch

QUERIES = {
 'consumable_categories': "UNWIND $rows AS row OPTIONAL MATCH (p:ConsumableCategory {category_path_id:row.parent_path_id}) MERGE (c:ConsumableCategory {category_path_id:row.category_path_id}) ON CREATE SET c.created_at=datetime() SET c.level=toInteger(row.level),c.code=row.code,c.name=row.name,c.path_kind=row.path_kind FOREACH (_ IN CASE WHEN p IS NULL THEN [] ELSE [1] END | MERGE (p)-[:PARENT_OF]->(c))",
 'service_categories': "UNWIND $rows AS row MERGE (c:ServiceCategory {service_category_id:row.service_category_id}) ON CREATE SET c.namespace=row.namespace,c.code=row.code,c.created_at=datetime() SET c.name=row.name",
 'service_items': "UNWIND $rows AS row MATCH (record:CatalogRecord {record_id:row.record_id}) MERGE (s:ServiceItem {service_item_id:row.service_item_id}) ON CREATE SET s.province_code=row.province_code,s.created_at=datetime() SET s.name=row.name MERGE (v:CatalogRecordVersion {version_id:row.version_id}) ON CREATE SET v.domain='SERVICE',v.record_hash=row.record_hash,v.valid_from=CASE trim(row.valid_from) WHEN '' THEN null ELSE date(row.valid_from) END,v.valid_to=CASE trim(row.valid_to) WHEN '' THEN null ELSE date(row.valid_to) END,v.raw_valid_from=row.raw_valid_from,v.raw_valid_to=row.raw_valid_to,v.created_at=datetime() MERGE (record)-[:EVIDENCE_FOR]->(s) MERGE (record)-[:CAPTURED_AS_VERSION]->(v) MERGE (v)-[:VERSION_OF]->(s)",
 'diagnosis_items': "UNWIND $rows AS row MATCH (record:CatalogRecord {record_id:row.record_id}) MERGE (d:DiagnosisItem {diagnosis_item_id:row.diagnosis_item_id}) ON CREATE SET d.namespace=row.namespace,d.code=row.code,d.created_at=datetime() SET d.name=row.name,d.valid_from=CASE trim(row.valid_from) WHEN '' THEN null ELSE date(row.valid_from) END,d.valid_to=CASE trim(row.valid_to) WHEN '' THEN null ELSE date(row.valid_to) END MERGE (record)-[:EVIDENCE_FOR]->(d)",
 'mapping_concepts': "UNWIND $rows AS row MERGE (c:MappingConcept {namespace:row.namespace,code:row.code}) ON CREATE SET c.first_seen_batch=row.first_seen_batch,c.created_at=datetime() SET c.classification_status=row.classification_status,c.classification_rule=row.classification_rule,c.review_status=row.review_status",
 'consumable_bases': "UNWIND $rows AS row MATCH (record:CatalogRecord {record_id:row.record_id}) OPTIONAL MATCH (c:ConsumableCategory {category_path_id:row.feature_path_id}) MERGE (b:ConsumableBase {base_code:row.base_code}) ON CREATE SET b.created_at=datetime() SET b.code_rule_valid=toBoolean(row.code_rule_valid) FOREACH (_ IN CASE WHEN c IS NULL THEN [] ELSE [1] END | MERGE (b)-[:CLASSIFIED_AS]->(c)) MERGE (record)-[:EVIDENCE_FOR]->(b)",
 'consumable_products': "UNWIND $rows AS row MATCH (b:ConsumableBase {base_code:row.base_code}) MERGE (p:ConsumableProduct {product_id:row.product_id}) ON CREATE SET p.base_code=row.base_code,p.registration_no=row.registration_no,p.created_at=datetime() SET p.product_name=row.product_name MERGE (p)-[:PRODUCT_OF]->(b) FOREACH (_ IN CASE trim(row.registration_id) WHEN '' THEN [] ELSE [1] END | MERGE (reg:RegistrationIdentifier {registration_id:row.registration_id}) SET reg.raw_value=row.registration_no MERGE (p)-[:REGISTERED_AS]->(reg))",
 'consumable_product_evidence': "UNWIND $rows AS row MATCH (record:CatalogRecord {record_id:row.record_id}) MATCH (p:ConsumableProduct {product_id:row.product_id}) MERGE (record)-[:EVIDENCE_FOR]->(p)",
 'basic_materials': "UNWIND $rows AS row MERGE (b:BasicMaterial {basic_material_id:row.basic_material_id}) SET b.name=row.name",
 'consumable_skus': "UNWIND $rows AS row MATCH (record:CatalogRecord {record_id:row.record_id}) MATCH (b:ConsumableBase {base_code:row.base_code}) OPTIONAL MATCH (p:ConsumableProduct {product_id:row.product_id}) MERGE (s:ConsumableSku {sku_code:row.sku_code}) ON CREATE SET s.created_at=datetime() SET s.specification=row.specification,s.model=row.model MERGE (s)-[:SKU_OF]->(b) MERGE (record)-[:EVIDENCE_FOR]->(s) FOREACH (_ IN CASE WHEN p IS NULL THEN [] ELSE [1] END | MERGE (s)-[:SKU_FOR_PRODUCT]->(p))",
 'mapping_labels': "UNWIND $rows AS row MATCH (record:CatalogRecord {record_id:row.record_id}) MATCH (c:MappingConcept {namespace:row.namespace,code:row.code}) MERGE (l:ObservedLabel {observed_label_id:row.observed_label_id}) ON CREATE SET l.namespace=row.namespace,l.code=row.code,l.label=row.label MERGE (c)-[r:HAS_OBSERVED_LABEL {assertion_id:row.assertion_id}]->(l) ON CREATE SET r.source_row=toInteger(row.source_row),r.review_status='RAW' MERGE (record)-[:EVIDENCE_FOR]->(c)",
 'mapping_assertions': "UNWIND $rows AS row WITH row WHERE row.relation_type='ASSERTED_MAPS_TO_CONCEPT' MATCH (record:CatalogRecord {record_id:row.record_id}) MATCH (p:ConsumableProduct {product_id:row.product_id}) MATCH (c:MappingConcept {namespace:row.namespace,code:row.code}) MERGE (p)-[r:ASSERTED_MAPS_TO_CONCEPT {assertion_id:row.assertion_id}]->(c) ON CREATE SET r.batch_id=row.batch_id,r.record_id=row.record_id,r.source_file=row.source_file,r.source_sheet=row.source_sheet,r.source_row=toInteger(row.source_row),r.source_column=row.source_column,r.raw_value=row.raw_token,r.evidence_tier='RAW_ASSERTED',r.review_status='ACCEPTED',r.valid_from=CASE trim(row.valid_from) WHEN '' THEN null ELSE date(row.valid_from) END,r.valid_to=CASE trim(row.valid_to) WHEN '' THEN null ELSE date(row.valid_to) END MERGE (record)-[:EVIDENCE_FOR]->(c)",
 'mapping_basic': "UNWIND $rows AS row WITH row WHERE row.relation_type='ASSERTED_BASIC_FOR_CONCEPT' MATCH (p:ConsumableProduct {product_id:row.product_id}) MATCH (c:MappingConcept {namespace:row.namespace,code:row.code}) MATCH (m:BasicMaterial {basic_material_id:row.basic_material_id}) MERGE (p)-[r:ASSERTED_BASIC_FOR_CONCEPT {assertion_id:row.assertion_id}]->(c) ON CREATE SET r.basic_material_id=row.basic_material_id,r.raw_value=row.raw_token,r.review_status='ACCEPTED' MERGE (m)-[:EVIDENCE_FOR]->(c)",
 'concept_mentions': "UNWIND $rows AS row MATCH (record:CatalogRecord {record_id:row.record_id}) MATCH (c:MappingConcept {namespace:row.namespace,code:row.code}) MERGE (record)-[r:ASSERTED_MENTIONS_CONCEPT {assertion_id:row.assertion_id}]->(c) ON CREATE SET r.raw_value=row.raw_token,r.source_row=toInteger(row.source_row),r.evidence_tier='RAW_ASSERTED'",
 'drug_products': "UNWIND $rows AS row MATCH (record:CatalogRecord {record_id:row.record_id}) MERGE (g:DrugGeneric {drug_generic_id:row.drug_generic_id}) ON CREATE SET g.name=row.generic_name MERGE (d:DrugProduct {drug_code:row.drug_code}) ON CREATE SET d.created_at=datetime() SET d.generic_name=row.generic_name,d.specification=row.specification,d.max_price_raw=row.max_price_raw,d.max_price=CASE trim(row.max_price) WHEN '' THEN null ELSE toFloat(row.max_price) END,d.max_price_semantics=row.max_price_semantics MERGE (d)-[:HAS_GENERIC]->(g) MERGE (record)-[:EVIDENCE_FOR]->(d) FOREACH (_ IN CASE trim(row.organization_id) WHEN '' THEN [] ELSE [1] END | MERGE (o:Organization {organization_id:row.organization_id}) SET o.normalized_name=row.organization_normalized_name MERGE (a:OrganizationAlias {alias_id:row.organization_alias_id}) SET a.raw_name=row.organization_raw_name MERGE (o)-[:HAS_ALIAS]->(a) MERGE (d)-[:MANUFACTURED_BY]->(o))",
}
ORDER = ['consumable_categories','service_categories','service_items','diagnosis_items','mapping_concepts','consumable_bases','consumable_products','consumable_product_evidence','basic_materials','consumable_skus','mapping_labels','mapping_assertions','mapping_basic','concept_mentions','drug_products']

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--input',type=Path,required=True); ap.add_argument('--uri',default='bolt://127.0.0.1:7687'); ap.add_argument('--user',default='neo4j'); ap.add_argument('--password',required=True); ap.add_argument('--batch-size',type=int,default=1000); ap.add_argument('--only',choices=ORDER); ap.add_argument('--skip-batches',type=int,default=0); ap.add_argument('--take-batches',type=int); args=ap.parse_args()
    with GraphDatabase.driver(args.uri,auth=(args.user,args.password)) as driver:
        driver.verify_connectivity()
        with driver.session() as session:
            for name in ([args.only] if args.only else ORDER):
                path=args.input/f'{name if name != "mapping_basic" else "mapping_assertions"}.csv'
                batches=0; loaded=0
                iterator = islice(rows(path,args.batch_size), args.skip_batches, None if args.take_batches is None else args.skip_batches + args.take_batches)
                for batch in iterator:
                    for attempt in range(6):
                        try:
                            session.run(QUERIES[name], rows=batch).consume()
                            break
                        except TransientError:
                            if attempt == 5:
                                raise
                            time.sleep((2 ** attempt) + random.random())
                    batches+=1; loaded+=len(batch)
                print(f'{name}: {loaded} rows in {batches} committed batches',flush=True)
if __name__ == '__main__': main()
