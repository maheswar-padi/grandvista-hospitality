import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Pencil, Plus } from 'lucide-react';
import { useState } from 'react';
import { Link } from 'react-router';
import { AdminHeader, Table, td, th } from '../../components/admin/AdminUi';
import { Alert } from '../../components/ui/Alert';
import { Badge, type BadgeTone } from '../../components/ui/Badge';
import { Button, ButtonLink } from '../../components/ui/Button';
import { Dialog } from '../../components/ui/Dialog';
import { SelectField, TextField } from '../../components/ui/Field';
import { Pagination } from '../../components/ui/Pagination';
import { EmptyState, ErrorState, LoadingBlock } from '../../components/ui/States';
import { adminApi } from '../../lib/api/endpoints';
import { ApiError } from '../../lib/api/http';
import type { Room, RoomStatus } from '../../lib/api/types';
import { useAuth } from '../../lib/auth/AuthContext';
import { formatMoney, humanize } from '../../lib/format';

const statusTone: Record<RoomStatus, BadgeTone> = { ACTIVE: 'success', MAINTENANCE: 'warning', OUT_OF_SERVICE: 'danger', INACTIVE: 'neutral' };
const statuses: RoomStatus[] = ['ACTIVE', 'MAINTENANCE', 'OUT_OF_SERVICE', 'INACTIVE'];

function StatusDialog({ room, onClose }: { room: Room; onClose: () => void }) {
  const queryClient = useQueryClient();
  const [status, setStatus] = useState<RoomStatus>(room.status);
  const [reason, setReason] = useState(room.statusReason ?? '');
  const change = useMutation({
    mutationFn: () => adminApi.changeStatus(room.id, status, reason || undefined),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['admin', 'rooms'] }),
  });
  return (
    <Dialog open onClose={onClose} title={`Room ${room.roomNumber} status`}>
      <div className="space-y-4">
        <SelectField label="Operational status" value={status} onChange={(e) => setStatus(e.target.value as RoomStatus)}>
          {statuses.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
        </SelectField>
        <TextField label="Reason (shown to staff)" value={reason} maxLength={255} onChange={(e) => setReason(e.target.value)} />
        <p className="text-xs text-ink-500">Only “Active” rooms can be booked. Existing confirmed stays are kept; retiring a room (Inactive) is refused while it has upcoming stays.</p>
        {change.data?.warning && <Alert tone="warning">{change.data.warning}</Alert>}
        {change.isSuccess && !change.data.warning && <Alert tone="success">Status updated.</Alert>}
        {change.error instanceof ApiError && <Alert tone="error">{change.error.message}</Alert>}
        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>Close</Button>
          <Button loading={change.isPending} disabled={status === room.status && reason === (room.statusReason ?? '')} onClick={() => change.mutate()}>Save status</Button>
        </div>
      </div>
    </Dialog>
  );
}

export default function AdminRoomsPage() {
  const { hasRole } = useAuth();
  const isAdmin = hasRole('ADMIN');
  const [status, setStatus] = useState<RoomStatus | ''>('');
  const [q, setQ] = useState('');
  const [page, setPage] = useState(0);
  const [editing, setEditing] = useState<Room | null>(null);
  const rooms = useQuery({
    queryKey: ['admin', 'rooms', status, q, page],
    queryFn: () => adminApi.rooms({ status: status || undefined, q: q || undefined, page, size: 25 }),
    placeholderData: keepPreviousData,
  });

  return (
    <>
      <AdminHeader
        title="Rooms & inventory"
        description="Every room in any status. Change operational status to take rooms out of sale for maintenance."
        actions={isAdmin && <ButtonLink to="/admin/rooms/new"><Plus className="size-4" aria-hidden /> New room</ButtonLink>}
      />
      <div className="mb-4 flex flex-wrap gap-3">
        <SelectField label="Status" wrapperClassName="w-48" value={status} onChange={(e) => { setStatus(e.target.value as RoomStatus | ''); setPage(0); }}>
          <option value="">All statuses</option>
          {statuses.map((s) => <option key={s} value={s}>{humanize(s)}</option>)}
        </SelectField>
        <TextField label="Search" placeholder="Room number or name" wrapperClassName="w-64" value={q} onChange={(e) => { setQ(e.target.value); setPage(0); }} />
      </div>
      {rooms.isPending ? <LoadingBlock /> : rooms.isError ? <ErrorState error={rooms.error} onRetry={() => rooms.refetch()} /> : rooms.data.content.length === 0 ? (
        <EmptyState title="No rooms found">Adjust the filters{isAdmin ? ' or create a room' : ''}.</EmptyState>
      ) : (
        <>
          <Table caption="Rooms">
            <thead><tr><th className={th}>Room</th><th className={th}>Type</th><th className={th}>Capacity</th><th className={th}>Base rate</th><th className={th}>Status</th><th className={th}><span className="sr-only">Actions</span></th></tr></thead>
            <tbody>
              {rooms.data.content.map((r) => (
                <tr key={r.id}>
                  <td className={td}><span className="font-semibold">{r.roomNumber}</span><span className="block text-xs text-ink-500">{r.name}</span></td>
                  <td className={td}>{r.roomType.name}</td>
                  <td className={td}>{r.maxOccupancy}</td>
                  <td className={`${td} tabular-nums`}>{formatMoney(r.basePrice, r.currency, 0)}</td>
                  <td className={td}><Badge tone={statusTone[r.status]}>{humanize(r.status)}</Badge>{r.statusReason && <span className="mt-1 block text-xs text-ink-500">{r.statusReason}</span>}</td>
                  <td className={`${td} whitespace-nowrap text-right`}>
                    <Button size="sm" variant="secondary" onClick={() => setEditing(r)}>Status</Button>{' '}
                    {isAdmin && <Link to={`/admin/rooms/${r.id}`} className="inline-flex items-center gap-1 px-2 text-sm font-medium text-gold-700 hover:underline"><Pencil className="size-3.5" aria-hidden /> Edit</Link>}
                  </td>
                </tr>
              ))}
            </tbody>
          </Table>
          <div className="mt-4"><Pagination page={rooms.data.page} totalPages={rooms.data.totalPages} onChange={setPage} /></div>
        </>
      )}
      {editing && <StatusDialog room={editing} onClose={() => setEditing(null)} />}
    </>
  );
}
