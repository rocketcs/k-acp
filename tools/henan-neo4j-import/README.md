# 河南医保目录 Neo4j 导入工件

`build_dataset.py` 从六份只读 XLSX 中提取真实有值行，生成可复跑的 UTF-8 CSV、质量报告和固定 Cypher 导入脚本。

运行：

```bash
python3 tools/henan-neo4j-import/build_dataset.py --output /tmp/henan-neo4j-import
```

生成目录可被复制到 Neo4j 容器的 `/import`，再由 `import.cypher` 执行。脚本不改写任何 XLSX。
