import { lazy } from 'react';
import { createBrowserRouter } from 'react-router';
import { Layout } from './components/layout/Layout';
import { RequireAuth } from './components/RequireAuth';
import { HomePage } from './pages/HomePage';
import { NotFoundPage, RouteErrorPage } from './pages/StatusPages';

// Route-level code splitting: the admin area (and its charting library) loads only when visited.
const SearchPage = lazy(() => import('./pages/SearchPage'));
const RoomDetailsPage = lazy(() => import('./pages/RoomDetailsPage'));
const LoginPage = lazy(() => import('./pages/LoginPage'));
const RegisterPage = lazy(() => import('./pages/RegisterPage'));
const CheckoutPage = lazy(() => import('./pages/CheckoutPage'));
const PaymentPage = lazy(() => import('./pages/PaymentPage'));
const ConfirmationPage = lazy(() => import('./pages/ConfirmationPage'));
const AccountPage = lazy(() => import('./pages/AccountPage'));
const BookingDetailPage = lazy(() => import('./pages/BookingDetailPage'));
const AdminLayout = lazy(() => import('./pages/admin/AdminLayout'));
const AdminOverviewPage = lazy(() => import('./pages/admin/AdminOverviewPage'));
const AdminRoomsPage = lazy(() => import('./pages/admin/AdminRoomsPage'));
const AdminRoomEditorPage = lazy(() => import('./pages/admin/AdminRoomEditorPage'));
const AdminPricingPage = lazy(() => import('./pages/admin/AdminPricingPage'));
const AdminBookingsPage = lazy(() => import('./pages/admin/AdminBookingsPage'));
const AdminPaymentsPage = lazy(() => import('./pages/admin/AdminPaymentsPage'));
const AdminUsersPage = lazy(() => import('./pages/admin/AdminUsersPage'));
const AdminReconciliationPage = lazy(() => import('./pages/admin/AdminReconciliationPage'));

export const router = createBrowserRouter([
  {
    element: <Layout />,
    errorElement: <RouteErrorPage />,
    children: [
      { path: '/', element: <HomePage /> },
      { path: '/rooms', element: <SearchPage /> },
      { path: '/rooms/:roomId', element: <RoomDetailsPage /> },
      { path: '/login', element: <LoginPage /> },
      { path: '/register', element: <RegisterPage /> },
      { path: '/checkout', element: <RequireAuth roles={['CUSTOMER']}><CheckoutPage /></RequireAuth> },
      { path: '/bookings/:bookingId/pay', element: <RequireAuth><PaymentPage /></RequireAuth> },
      { path: '/bookings/:bookingId/confirmation', element: <RequireAuth><ConfirmationPage /></RequireAuth> },
      { path: '/account', element: <RequireAuth><AccountPage /></RequireAuth> },
      { path: '/account/bookings/:bookingId', element: <RequireAuth><BookingDetailPage /></RequireAuth> },
      {
        path: '/admin',
        element: <RequireAuth roles={['ADMIN', 'STAFF']}><AdminLayout /></RequireAuth>,
        children: [
          { index: true, element: <AdminOverviewPage /> },
          { path: 'rooms', element: <AdminRoomsPage /> },
          { path: 'rooms/new', element: <RequireAuth roles={['ADMIN']}><AdminRoomEditorPage /></RequireAuth> },
          { path: 'rooms/:roomId', element: <AdminRoomEditorPage /> },
          { path: 'pricing', element: <AdminPricingPage /> },
          { path: 'bookings', element: <AdminBookingsPage /> },
          { path: 'payments', element: <AdminPaymentsPage /> },
          { path: 'reconciliation', element: <AdminReconciliationPage /> },
          { path: 'users', element: <RequireAuth roles={['ADMIN']}><AdminUsersPage /></RequireAuth> },
        ],
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]);
