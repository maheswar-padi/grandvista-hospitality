import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { AdminHeader, Table, td, th } from '../../components/admin/AdminUi';
import { Alert } from '../../components/ui/Alert';
import { Badge } from '../../components/ui/Badge';
import { Button } from '../../components/ui/Button';
import { TextField } from '../../components/ui/Field';
import { Pagination } from '../../components/ui/Pagination';
import { ErrorState, LoadingBlock } from '../../components/ui/States';
import { adminApi } from '../../lib/api/endpoints';
import { ApiError } from '../../lib/api/http';
import type { Role, User } from '../../lib/api/types';
import { useAuth } from '../../lib/auth/AuthContext';
import { formatDateTime } from '../../lib/format';

const ROLES: Role[] = ['CUSTOMER', 'STAFF', 'ADMIN'];

function RoleEditor({ user, self }: { user: User; self: boolean }) {
  const queryClient = useQueryClient();
  const [roles, setRoles] = useState<Role[]>(user.roles);
  const save = useMutation({ mutationFn: () => adminApi.updateRoles(user.id, roles), onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }) });
  const toggleStatus = useMutation({ mutationFn: () => adminApi.updateUserStatus(user.id, !user.enabled), onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'users'] }) });
  const changed = roles.slice().sort().join() !== user.roles.slice().sort().join();
  const error = (save.error ?? toggleStatus.error) as unknown;
  return (
    <div className="flex flex-wrap items-center gap-3">
      {ROLES.map((r) => (
        <label key={r} className="flex items-center gap-1 text-xs">
          <input type="checkbox" className="size-4 accent-ink-900" checked={roles.includes(r)} onChange={(e) => setRoles(e.target.checked ? [...roles, r] : roles.filter((x) => x !== r))} />
          {r.toLowerCase()}
        </label>
      ))}
      <Button size="sm" disabled={!changed || roles.length === 0} loading={save.isPending} onClick={() => save.mutate()}>Save roles</Button>
      {!self && <Button size="sm" variant={user.enabled ? 'secondary' : 'primary'} loading={toggleStatus.isPending} onClick={() => toggleStatus.mutate()}>{user.enabled ? 'Disable' : 'Enable'}</Button>}
      {error instanceof ApiError && <span className="text-xs text-red-700">{error.message}</span>}
    </div>
  );
}

export default function AdminUsersPage() {
  const { user: me } = useAuth();
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const users = useQuery({ queryKey: ['admin', 'users', q, page], queryFn: () => adminApi.users({ q: q || undefined, page, size: 25 }), placeholderData: keepPreviousData });
  return (
    <>
      <AdminHeader title="Users & roles" description="Grant staff or administrator access. Role changes sign the user out of other sessions so the change takes effect." />
      <Alert tone="info" className="mb-6">There is no public way to create an administrator. The first administrator is bootstrapped from environment variables at start-up.</Alert>
      <TextField label="Search" placeholder="Name or e-mail" wrapperClassName="mb-4 w-72" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
      {users.isPending ? <LoadingBlock /> : users.isError ? <ErrorState error={users.error} onRetry={() => users.refetch()} /> : (
        <>
          <Table caption="Users">
            <thead><tr><th className={th}>User</th><th className={th}>Status</th><th className={th}>Last sign-in</th><th className={th}>Roles</th></tr></thead>
            <tbody>
              {users.data.content.map((u) => (
                <tr key={u.id}>
                  <td className={td}>{u.fullName}<span className="block text-xs text-ink-500">{u.email}</span></td>
                  <td className={td}><Badge tone={u.enabled ? 'success' : 'neutral'}>{u.enabled ? 'Active' : 'Disabled'}</Badge></td>
                  <td className={`${td} text-xs`}>{formatDateTime(u.lastLoginAt)}</td>
                  <td className={td}><RoleEditor key={u.roles.join()} user={u} self={u.id === me?.id} /></td>
                </tr>
              ))}
            </tbody>
          </Table>
          <div className="mt-4"><Pagination page={users.data.page} totalPages={users.data.totalPages} onChange={setPage} /></div>
        </>
      )}
    </>
  );
}
