/**
 * 公共页面路由
 *
 * @author huxuehao
 */

import type { AppRouteRecordRaw } from '../types'
import { RouteNames, RoutePaths } from '@/router'

/**
 * 公共页面路由配置
 */
const commonRoutes: AppRouteRecordRaw[] = [
  {
    path: RoutePaths.ROOT,
    redirect: RoutePaths.DASHBOARD,
  },
  {
    path: RoutePaths.LARGE_SCREEN_IMAGE_CHAT,
    name: RouteNames.LARGE_SCREEN_IMAGE_CHAT,
    component: () => import('@/views/LargeScreenImageChat/index.vue'),
    meta: {
      title: '大屏生图',
      hidden: true,
    },
  },
  {
    path: RoutePaths.BIAOSHU_INTERPRETER_CHAT,
    name: RouteNames.BIAOSHU_INTERPRETER_CHAT,
    component: () => import('@/features/biaoshu-interpreter/BiaoshuInterpreterChat.vue'),
    meta: {
      title: '标书智能解读助手',
      hidden: true,
    },
  },
  {
    path: `${RoutePaths.CHAT_DIY}/:agentId`,
    name: RouteNames.CHAT_DIY,
    component: () => import('@/views/Chat/index.vue'),
    meta: {
      title: 'DIY 对话',
      hidden: true,
    },
  },
  {
    path: `${RoutePaths.CHAT}/:agentId`,
    name: RouteNames.CHAT,
    component: () => import('@/views/Chat/index.vue'),
    meta: {
      title: '对话',
      hidden: true,
    },
  },
  {
    path: `${RoutePaths.CHAT_HISTORY}/:agentId`,
    name: RouteNames.CHAT_HISTORY,
    component: () => import('@/views/ChatHistory/index.vue'),
    meta: {
      title: '对话历史',
      hidden: true,
    },
  },
  {
    path: `${RoutePaths.COMMUNICATION}/:chatKey`,
    name: RouteNames.COMMUNICATION,
    component: () => import('@/views/Communication/index.vue'),
    meta: {
      title: '对话',
      hidden: true,
    },
  },
  // 文档页面已迁移至 doc 子应用（doc.html）
  {
    path: RoutePaths.FORBIDDEN,
    name: RouteNames.FORBIDDEN,
    component: () => import('@/pages/Forbidden.vue'),
    meta: {
      title: '无访问权限',
      hidden: true,
    },
  },
  {
    path: RoutePaths.NOT_FOUND,
    name: RouteNames.NOT_FOUND,
    component: () => import('@/pages/NotFound.vue'),
    meta: {
      title: '页面不存在',
      hidden: true,
    },
  }
]

export default commonRoutes
