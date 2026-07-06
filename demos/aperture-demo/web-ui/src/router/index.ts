import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/authStore'
import type { Permission } from '@/stores/permissions'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    requiresAdmin?: boolean
    requiresPermission?: Permission | Permission[]
  }
}

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/auth/LoginView.vue'), meta: { public: true } },
  { path: '/accept-invite', name: 'accept-invite', component: () => import('@/views/auth/AcceptInviteView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/layouts/ProductShell.vue'),
    children: [
      { path: '', redirect: '/dashboard' },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { requiresPermission: 'workspace:view' } },
      { path: 'customers', name: 'customers', component: () => import('@/views/customers/CustomersView.vue'), meta: { requiresPermission: 'customers:read' } },
      { path: 'customers/:id', name: 'customer-detail', component: () => import('@/views/customers/CustomerDetailView.vue'), meta: { requiresPermission: 'customers:read' } },
      { path: 'products', name: 'products', component: () => import('@/views/products/ProductsView.vue'), meta: { requiresPermission: 'products:read' } },
      { path: 'products/:id', name: 'product-detail', component: () => import('@/views/products/ProductDetailView.vue'), meta: { requiresPermission: 'products:read' } },
      { path: 'invoices', name: 'invoices', component: () => import('@/views/invoices/InvoicesView.vue'), meta: { requiresPermission: 'invoices:read' } },
      { path: 'invoices/new', name: 'invoice-new', component: () => import('@/views/invoices/InvoiceBuilderView.vue'), meta: { requiresPermission: 'invoices:write' } },
      { path: 'invoices/:id', name: 'invoice-detail', component: () => import('@/views/invoices/InvoiceDetailView.vue'), meta: { requiresPermission: 'invoices:read' } },
      { path: 'payments', name: 'payments', component: () => import('@/views/payments/PaymentsView.vue'), meta: { requiresPermission: 'payments:read' } },
      { path: 'insights', name: 'insights', component: () => import('@/views/insights/GraphQLInsightsView.vue'), meta: { requiresPermission: 'invoices:read' } },
      { path: 'suppliers', name: 'suppliers', component: () => import('@/views/suppliers/SuppliersView.vue'), meta: { requiresPermission: 'suppliers:read' } },
      { path: 'admin', name: 'admin', component: () => import('@/views/admin/AdminView.vue'), meta: { requiresAdmin: true, requiresPermission: 'admin:view' } },
      { path: 'settings', name: 'settings', component: () => import('@/views/SettingsView.vue') },
      { path: 'forbidden', name: 'forbidden', component: () => import('@/views/ForbiddenView.vue') },
    ],
  },
  { path: '/:pathMatch(.*)*', name: 'not-found', component: () => import('@/views/NotFoundView.vue') },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 }),
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isAuthenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (!to.meta.public) {
    await auth.ensureCurrentUser()
    if (!auth.isAuthenticated) return { name: 'login', query: { redirect: to.fullPath } }
  }

  if (to.meta.requiresAdmin && !auth.canAccessAdmin) {
    return { name: 'forbidden' }
  }

  const required = to.meta.requiresPermission
  if (required) {
    const permissions = Array.isArray(required) ? required : [required]
    if (!permissions.every((permission) => auth.hasPermission(permission))) return { name: 'forbidden' }
  }
})

export default router
