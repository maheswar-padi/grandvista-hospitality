import { Compass, ServerCrash } from 'lucide-react';
import { isRouteErrorResponse, useRouteError } from 'react-router';
import { ButtonLink } from '../components/ui/Button';
import { EmptyState } from '../components/ui/States';

export function NotFoundPage() {
  return (
    <div className="container-page py-20">
      <EmptyState title="We couldn’t find that page" icon={<Compass className="size-6" aria-hidden />} action={<ButtonLink to="/">Back to the home page</ButtonLink>}>
        The link may be out of date, or the page may have moved.
      </EmptyState>
    </div>
  );
}

export function RouteErrorPage() {
  const error = useRouteError();
  const notFound = isRouteErrorResponse(error) && error.status === 404;
  if (notFound) return <NotFoundPage />;
  return (
    <div className="container-page py-20">
      <EmptyState title="Something went wrong" icon={<ServerCrash className="size-6" aria-hidden />} action={<a href="/" className="text-sm font-medium text-gold-700 underline">Reload the site</a>}>
        An unexpected error occurred while displaying this page.
      </EmptyState>
    </div>
  );
}
