<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { ChartSpec } from './chartSpec.ts'

use([CanvasRenderer, BarChart, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const props = defineProps<{ spec: ChartSpec }>()

/** 受限 spec → ECharts option（确定性转换，不做任何数据加工）。 */
const option = computed(() => {
  const s = props.spec
  const unitSuffix = s.yUnit ? `（${s.yUnit}）` : ''
  if (s.type === 'pie') {
    return {
      title: { text: s.title, left: 'center', textStyle: { fontSize: 14, fontWeight: 600 } },
      tooltip: { trigger: 'item', formatter: '{b}: {c} ({d}%)' },
      legend: { type: 'scroll', bottom: 0, left: 'center', icon: 'circle' },
      series: [{
        type: 'pie',
        radius: ['32%', '62%'],
        center: ['50%', '46%'],
        label: { formatter: '{b}\n{d}%', fontSize: 11 },
        data: s.data.map((d) => ({ name: d.name, value: d.value })),
      }],
    }
  }
  const rotate = s.data.length > 8 ? 40 : s.data.length > 5 ? 20 : 0
  return {
    title: { text: `${s.title}${unitSuffix}`, left: 'center', textStyle: { fontSize: 14, fontWeight: 600 } },
    tooltip: { trigger: 'axis' },
    grid: { left: 16, right: 24, bottom: 16, top: 56, containLabel: true },
    xAxis: {
      type: 'category',
      name: s.xLabel,
      nameLocation: 'middle',
      nameGap: 34,
      nameTextStyle: { fontSize: 11 },
      axisLabel: { rotate, interval: 0, fontSize: 11 },
      data: s.data.map((d) => d.name),
    },
    yAxis: {
      type: 'value',
      name: s.yLabel ?? '',
      nameTextStyle: { fontSize: 11, align: 'left' },
      axisLabel: { formatter: (v: number) => (Math.abs(v) >= 1e8 ? `${(v / 1e8).toFixed(1)}亿` : Math.abs(v) >= 1e4 ? `${(v / 1e4).toFixed(0)}万` : String(v)) },
    },
    series: [{
      type: s.type === 'line' ? 'line' : 'bar',
      name: s.yLabel ?? s.title,
      data: s.data.map((d) => d.value),
      barMaxWidth: 42,
      ...(s.type === 'line' ? { smooth: true } : {}),
    }],
  }
})
</script>

<template>
  <div class="nanwang-chart-card">
    <VChart class="nanwang-chart-canvas" :option="option" autoresize />
  </div>
</template>

<style scoped lang="scss">
.nanwang-chart-card {
  margin: 12px 0;
  padding: 12px 8px 4px;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background: #fff;
}

.nanwang-chart-canvas {
  width: 100%;
  height: 340px;
}
</style>
