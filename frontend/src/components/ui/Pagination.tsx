import { ChevronLeft, ChevronRight } from 'lucide-react';
import { Button } from './Button';

export function Pagination({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (page: number) => void }) {
  if (totalPages <= 1) return null;
  return (
    <nav className="flex items-center justify-center gap-3" aria-label="Pagination">
      <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => onChange(page - 1)} icon={<ChevronLeft className="size-4" aria-hidden />}>
        Previous
      </Button>
      <span className="text-sm text-ink-600" aria-live="polite">
        Page {page + 1} of {totalPages}
      </span>
      <Button variant="secondary" size="sm" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>
        Next <ChevronRight className="size-4" aria-hidden />
      </Button>
    </nav>
  );
}
