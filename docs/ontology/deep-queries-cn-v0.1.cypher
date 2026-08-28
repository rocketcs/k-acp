// 深度问题 1：哪些设备类别占用库存金额最高？
MATCH (库存:`库存记录`)-[:`对应物资`]->(物资:`物资`)
RETURN 物资.`类别` AS 设备类别,
       count(库存) AS 库存记录数,
       sum(库存.`金额`) AS 库存金额
ORDER BY 库存金额 DESC;

// 深度问题 2：供应商集中度（金额前十）
MATCH (库存:`库存记录`)-[:`由供应商提供`]->(供应商:`供应商`)
RETURN 供应商.`名称` AS 供应商,
       count(库存) AS 记录数,
       sum(库存.`金额`) AS 供应金额
ORDER BY 供应金额 DESC LIMIT 10;

// 深度问题 3：从仓库追到项目和供应商
MATCH (仓库:`仓库` {`标识`:$仓库标识})-[:`包含库存`]->(库存:`库存记录`)
OPTIONAL MATCH (库存)-[:`对应物资`]->(物资:`物资`)
OPTIONAL MATCH (库存)-[:`由供应商提供`]->(供应商:`供应商`)
OPTIONAL MATCH (库存)-[:`归属项目`]->(项目:`项目`)
RETURN 物资.`名称` AS 物资,
       物资.`型号` AS 型号,
       库存.`用途` AS 用途,
       供应商.`名称` AS 供应商,
       项目.`名称` AS 项目,
       库存.`数量` AS 数量,
       库存.`金额` AS 金额
ORDER BY 金额 DESC LIMIT 50;

// 深度问题 4：数据完整性与金额一致性（金额允许浮点误差 0.01）
MATCH (库存:`库存记录`)
RETURN count(库存) AS 总记录数,
       sum(CASE WHEN (库存)-[:`对应物资`]->() THEN 0 ELSE 1 END) AS 缺物资数,
       sum(CASE WHEN (库存)-[:`由供应商提供`]->() THEN 0 ELSE 1 END) AS 缺供应商数,
       sum(CASE WHEN abs(库存.`数量`*库存.`单价`-库存.`金额`)>0.01 THEN 1 ELSE 0 END) AS 金额异常数;
