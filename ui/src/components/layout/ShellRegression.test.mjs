import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const sideMenuSource = readFileSync(resolve(here, 'SideMenu.vue'), 'utf8')
const dashboardSource = readFileSync(resolve(here, '../../views/Dashboard/index.vue'), 'utf8')
const sidebarSource = readFileSync(resolve(here, 'Sidebar.vue'), 'utf8')
const appLogoSource = readFileSync(resolve(here, 'AppLogo.vue'), 'utf8')
const loginSource = readFileSync(resolve(here, '../../pages/Login.vue'), 'utf8')
const footerSource = readFileSync(resolve(here, 'AppFooter.vue'), 'utf8')
const loadingExampleSource = readFileSync(resolve(here, '../../views/ExampleLoading.vue'), 'utf8')
const loadingSource = readFileSync(resolve(here, '../common/ApboaLoading.vue'), 'utf8')
const indexHtmlSource = readFileSync(resolve(here, '../../../index.html'), 'utf8')
const docHtmlSource = readFileSync(resolve(here, '../../../doc.html'), 'utf8')

test('production menu icons are bundled instead of pointing at /src assets', () => {
  assert.doesNotMatch(sideMenuSource, /['"]\/src\/assets\//)
  assert.match(sideMenuSource, /import\s+homeAvatar\s+from\s+['"]@\/assets\/avatar\/home\.png['"]/)
})

test('dashboard renders usable navigation instead of permanent skeleton placeholders', () => {
  assert.doesNotMatch(dashboardSource, /skeleton/i)
  assert.match(dashboardSource, /router\.push\(item\.path\)/)
})

test('customer-facing shell consistently uses Kingsware branding', () => {
  assert.doesNotMatch(sidebarSource, /@\/assets\/logo\/logo_[13]\.png/)
  assert.match(sidebarSource, /<AppLogo/)
  assert.match(appLogoSource, /@\/assets\/images\/logo\/logo\.png/)
  assert.match(dashboardSource, /KINGSWARE 工作台/)
  assert.doesNotMatch(
    [dashboardSource, loginSource, footerSource].join('\n'),
    /APBOA|Apboa/,
  )
  assert.doesNotMatch(loadingExampleSource, /Apboa 加载动画示例|包裹式加载 \(ApboaSpin\)/)
})

test('loading screens no longer render the upstream logo mark', () => {
  assert.match(loadingSource, /brandLogo/)
  assert.doesNotMatch(loadingSource, /<svg/)
  assert.doesNotMatch(indexHtmlSource, /<svg/)
  assert.doesNotMatch(docHtmlSource, /<svg/)
})
