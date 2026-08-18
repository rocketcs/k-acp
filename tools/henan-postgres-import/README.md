# 河南医保目录 PostgreSQL 导入

`schema_and_load.sql` 对由 `tools/henan-neo4j-import/build_dataset.py` 生成的 CSV 进行 PostgreSQL 导入。

数据库使用 `raw`、`curated`、`bridge`、`audit` 四个 schema；所有 Neo4j 稳定 ID、命名空间、来源行和断言 ID 均保持不变，因此可用 `product_id`、`base_code`、`assertion_id`、`record_id` 与 Neo4j 交叉核验。
