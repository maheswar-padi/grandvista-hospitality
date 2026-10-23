import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Trash2, Upload } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate, useParams } from 'react-router';
import { z } from 'zod';
import { AdminHeader } from '../../components/admin/AdminUi';
import { Alert } from '../../components/ui/Alert';
import { Button } from '../../components/ui/Button';
import { SelectField, TextAreaField, TextField } from '../../components/ui/Field';
import { ErrorState, LoadingBlock } from '../../components/ui/States';
import { adminApi, roomsApi, type RoomBody } from '../../lib/api/endpoints';
import { ApiError } from '../../lib/api/http';
import { useAdminProperty } from '../../lib/adminProperty';
import { useAuth } from '../../lib/auth/AuthContext';

const optionalInt = z.preprocess((v) => (v === '' || v === null || Number.isNaN(v) ? undefined : Number(v)), z.number().int().optional());

const schema = z.object({
  roomNumber: z.string().trim().min(1, 'Required').max(20).regex(/^[A-Za-z0-9-]+$/, 'Letters, digits and “-” only'),
  roomTypeId: z.string().min(1, 'Choose a room type'),
  name: z.string().trim().min(2, 'Required').max(120),
  description: z.string().max(4000).optional(),
  maxOccupancy: z.coerce.number().int().min(1).max(12),
  bedConfiguration: z.string().trim().min(2, 'Required').max(120),
  sizeSqM: optionalInt,
  floor: optionalInt,
  viewType: z.string().max(40).optional(),
  basePrice: z.coerce.number().min(1, 'Must be at least 1').max(10_000_000),
  amenityCodes: z.array(z.string()),
});
type FormValues = z.infer<typeof schema>;

const ACCEPTED = 'image/jpeg,image/png,image/webp';

export default function AdminRoomEditorPage() {
  const { roomId } = useParams();
  const isNew = !roomId;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const { hasRole } = useAuth();
  const readOnly = !hasRole('ADMIN');
  const fileInput = useRef<HTMLInputElement>(null);
  const [altText, setAltText] = useState('');

  const { effective } = useAdminProperty();
  const amenities = useQuery({ queryKey: ['amenities'], queryFn: roomsApi.amenities });
  const room = useQuery({ queryKey: ['admin', 'room', roomId], queryFn: () => adminApi.room(roomId!), enabled: !isNew });
  // a room belongs to one hotel: existing rooms keep theirs, new rooms are created in the selected (or default) hotel
  const hotel = isNew ? effective : room.data?.property;
  const types = useQuery({
    queryKey: ['room-types', hotel?.id],
    queryFn: () => roomsApi.roomTypes(hotel?.id),
    enabled: !!hotel,
  });

  const { register, handleSubmit, reset, formState: { errors } } = useForm({
    resolver: zodResolver(schema),
    defaultValues: { maxOccupancy: 2, amenityCodes: [] as string[], basePrice: 5000 },
  });
  useEffect(() => {
    if (room.data) {
      const r = room.data;
      reset({
        roomNumber: r.roomNumber, roomTypeId: r.roomType.id, name: r.name, description: r.description ?? '', maxOccupancy: r.maxOccupancy,
        bedConfiguration: r.bedConfiguration, sizeSqM: r.sizeSqM ?? undefined, floor: r.floor ?? undefined, viewType: r.viewType ?? '',
        basePrice: r.basePrice, amenityCodes: r.amenities.map((a) => a.code),
      });
    }
  }, [room.data, reset]);

  const save = useMutation({
    mutationFn: (v: FormValues) => {
      const body: RoomBody = { ...v, description: v.description || undefined, viewType: v.viewType || undefined, currency: 'INR', version: room.data?.version };
      return isNew ? adminApi.createRoom(body) : adminApi.updateRoom(roomId!, body);
    },
    onSuccess: (saved) => {
      void queryClient.invalidateQueries({ queryKey: ['admin', 'rooms'] });
      queryClient.setQueryData(['admin', 'room', saved.id], saved);
      if (isNew) navigate(`/admin/rooms/${saved.id}`, { replace: true });
    },
  });
  const upload = useMutation({
    mutationFn: (file: File) => adminApi.uploadImage(roomId!, file, altText || undefined),
    onSuccess: () => {
      setAltText('');
      if (fileInput.current) fileInput.current.value = '';
      void room.refetch();
    },
  });
  const removeImage = useMutation({ mutationFn: (imageId: string) => adminApi.deleteImage(roomId!, imageId), onSuccess: () => void room.refetch() });

  if (!isNew && room.isPending) return <LoadingBlock />;
  if (!isNew && room.isError) return <ErrorState error={room.error} onRetry={() => room.refetch()} />;
  const saveError = save.error instanceof ApiError ? save.error : null;

  return (
    <>
      <Link to="/admin/rooms" className="mb-2 inline-flex items-center gap-1.5 py-2.5 text-sm text-ink-600 hover:text-ink-900"><ArrowLeft className="size-4" aria-hidden /> All rooms</Link>
      <AdminHeader title={isNew ? 'New room' : `Room ${room.data?.roomNumber}`} description={`${hotel ? `${hotel.name}, ${hotel.city}. ` : ''}${isNew ? 'New rooms are created as Active in this hotel; choose another hotel in the menu to add rooms there.' : 'Changes to the base rate apply to new quotes only; existing bookings keep their price snapshot.'}`} />
      <form onSubmit={handleSubmit((v) => save.mutate(v as FormValues))} className="card space-y-6 p-6" noValidate>
        <fieldset disabled={readOnly} className="grid gap-5 sm:grid-cols-2">
          <TextField label="Room number" disabled={!isNew} error={errors.roomNumber?.message} {...register('roomNumber')} />
          <SelectField label="Room type" error={errors.roomTypeId?.message} {...register('roomTypeId')}>
            <option value="">Choose…</option>
            {types.data?.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </SelectField>
          <TextField label="Display name" wrapperClassName="sm:col-span-2" error={errors.name?.message} {...register('name')} />
          <TextAreaField label="Description" wrapperClassName="sm:col-span-2" error={errors.description?.message} {...register('description')} />
          <TextField label="Maximum guests" type="number" min={1} max={12} error={errors.maxOccupancy?.message} {...register('maxOccupancy')} />
          <TextField label="Bed configuration" error={errors.bedConfiguration?.message} {...register('bedConfiguration')} />
          <TextField label="Size (m²)" type="number" error={errors.sizeSqM?.message} {...register('sizeSqM')} />
          <TextField label="Floor" type="number" error={errors.floor?.message} {...register('floor')} />
          <TextField label="View" placeholder="e.g. Sea, City" error={errors.viewType?.message} {...register('viewType')} />
          <TextField label="Base nightly rate (INR)" type="number" step="0.01" error={errors.basePrice?.message} {...register('basePrice')} />
          <fieldset className="sm:col-span-2">
            <legend className="mb-2 text-sm font-medium text-ink-800">Amenities</legend>
            <div className="grid gap-2 sm:grid-cols-3">
              {amenities.data?.map((a) => (
                <label key={a.code} className="flex items-center gap-2 text-sm">
                  <input type="checkbox" value={a.code} className="size-4 accent-ink-900" {...register('amenityCodes')} /> {a.name}
                </label>
              ))}
            </div>
          </fieldset>
        </fieldset>
        {saveError && <Alert tone="error" title={saveError.code === 'CONCURRENT_MODIFICATION' ? 'Someone else changed this room' : undefined}>{saveError.code === 'CONCURRENT_MODIFICATION' ? 'Reload the page to see the latest version before saving again.' : saveError.message}</Alert>}
        {save.isSuccess && !isNew && <Alert tone="success">Room saved.</Alert>}
        {!readOnly && <div className="flex justify-end"><Button type="submit" loading={save.isPending}>{isNew ? 'Create room' : 'Save changes'}</Button></div>}
      </form>

      {!isNew && room.data && (
        <section className="card mt-8 p-6" aria-labelledby="photos-heading">
          <h2 id="photos-heading" className="text-2xl">Photographs</h2>
          <p className="mt-1 text-sm text-ink-600">JPEG, PNG or WebP up to 5 MB; up to 10 per room. Files are checked by content, not by name.</p>
          {room.data.images.length === 0 ? <p className="mt-4 text-sm text-ink-500">No photographs yet — an illustration is shown to guests.</p> : (
            <ul className="mt-4 grid gap-4 sm:grid-cols-3">
              {room.data.images.map((img) => (
                <li key={img.id} className="overflow-hidden rounded-xl ring-1 ring-ink-100">
                  <img src={img.url} alt={img.altText ?? ''} className="aspect-[3/2] w-full object-cover" />
                  {!readOnly && (
                    <div className="flex items-center justify-between p-2 text-xs">
                      <span className="truncate text-ink-500">{img.altText ?? 'No description'}</span>
                      <Button size="sm" variant="ghost" aria-label="Delete photograph" loading={removeImage.isPending && removeImage.variables === img.id} onClick={() => removeImage.mutate(img.id)}><Trash2 className="size-4" aria-hidden /></Button>
                    </div>
                  )}
                </li>
              ))}
            </ul>
          )}
          {!readOnly && (
            <div className="mt-6 flex flex-wrap items-end gap-3">
              <TextField label="Description of the photo (alt text)" wrapperClassName="w-72" value={altText} maxLength={200} onChange={(e) => setAltText(e.target.value)} />
              <input ref={fileInput} type="file" accept={ACCEPTED} className="sr-only" id="photo-upload" onChange={(e) => e.target.files?.[0] && upload.mutate(e.target.files[0])} />
              <label htmlFor="photo-upload" className="inline-flex h-11 cursor-pointer items-center gap-2 rounded-full border border-ink-200 bg-white px-5 text-sm font-medium hover:bg-ink-50">
                <Upload className="size-4" aria-hidden /> {upload.isPending ? 'Uploading…' : 'Upload photograph'}
              </label>
            </div>
          )}
          {upload.error instanceof ApiError && <Alert tone="error" className="mt-4">{upload.error.message}</Alert>}
        </section>
      )}
    </>
  );
}
