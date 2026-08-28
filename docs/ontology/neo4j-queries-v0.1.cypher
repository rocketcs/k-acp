// 1. 节点与关系规模
MATCH (n)
RETURN labels(n)[0] AS label, count(*) AS count
ORDER BY label;

MATCH ()-[r]->()
RETURN type(r) AS relationship, count(*) AS count
ORDER BY relationship;

// 2. 查询某仓库的库存
MATCH (w:Warehouse {id: 'urn:kacp:mock:warehouse:WH0000527'})
      -[:CONTAINS_STOCK]->(s:StockRecord)
      -[:REFERS_TO_MATERIAL]->(m:Material)
RETURN w.id AS warehouse,
       s.id AS stock,
       m.id AS material,
       s.quantity AS quantity,
       s.totalPrice AS totalPrice,
       s.usageCode AS usageCode;

// 3. 查询项目物资及供应商
MATCH (s:StockRecord)-[:ASSIGNED_TO_PROJECT]->(p:Project),
      (s)-[:SUPPLIED_BY]->(v:Supplier),
      (s)-[:REFERS_TO_MATERIAL]->(m:Material)
RETURN p.id AS project,
       m.id AS material,
       v.id AS supplier,
       sum(s.totalPrice) AS totalPrice
ORDER BY totalPrice DESC
LIMIT 20;

// 4. 按省份统计库存金额
MATCH (s:StockRecord)
RETURN s.provinceCode AS provinceCode,
       sum(s.totalPrice) AS totalPrice,
       count(s) AS stockCount
ORDER BY totalPrice DESC;

// 5. 质量检查：项目物资必须关联项目
MATCH (s:StockRecord)
WHERE s.usageCode = 'U1'
  AND NOT (s)-[:ASSIGNED_TO_PROJECT]->(:Project)
RETURN count(s) AS violations;

// 6. 质量检查：每条库存必须连接仓库、物资和供应商
MATCH (s:StockRecord)
WHERE NOT (s)<-[:CONTAINS_STOCK]-(:Warehouse)
   OR NOT (s)-[:REFERS_TO_MATERIAL]->(:Material)
   OR NOT (s)-[:SUPPLIED_BY]->(:Supplier)
RETURN count(s) AS violations;
