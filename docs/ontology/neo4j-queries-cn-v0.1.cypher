// 中文本体图谱：仓库 WH0000527 的一跳邻居
MATCH (仓库:`仓库` {`标识`: 'urn:kacp:mock:warehouse:WH0000527'})
OPTIONAL MATCH 路径=(仓库)-[:`包含库存`]->(库存:`库存记录`)
OPTIONAL MATCH (库存)-[:`对应物资`]->(物资:`物资`)
OPTIONAL MATCH (库存)-[:`由供应商提供`]->(供应商:`供应商`)
OPTIONAL MATCH (库存)-[:`归属项目`]->(项目:`项目`)
OPTIONAL MATCH (仓库)-[:`位于区域`]->(区域:`区域`)
RETURN 仓库,库存,物资,供应商,项目,区域
LIMIT 80;

// 中文三元组文本输出
MATCH (仓库:`仓库`)-[:`包含库存`]->(库存:`库存记录`)-[:`对应物资`]->(物资:`物资`)
RETURN '仓库:' + replace(仓库.`标识`,'urn:kacp:mock:warehouse:','') +
       ' —包含库存→ 库存记录:' + replace(库存.`标识`,'urn:kacp:mock:stock:','') +
       ' —对应物资→ 物资:' + replace(物资.`标识`,'urn:kacp:mock:material:','') AS 三元组
LIMIT 20;
