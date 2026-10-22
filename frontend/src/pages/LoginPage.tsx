import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router';
import { Alert } from '../components/ui/Alert';
import { Button } from '../components/ui/Button';
import { TextField } from '../components/ui/Field';
import { ApiError } from '../lib/api/http';
import { useAuth } from '../lib/auth/AuthContext';
import { safeRedirect } from '../lib/safeRedirect';
import { loginSchema, type LoginForm } from '../lib/schemas';
import { AuthCard } from './AuthCard';

export default function LoginPage() {
  const { login, status } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeRedirect(params.get('next'));
  const [error, setError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<LoginForm>({ resolver: zodResolver(loginSchema) });

  if (status === 'authenticated') return <Navigate to={next} replace />;

  const onSubmit = async (values: LoginForm) => {
    setError(null);
    try {
      const user = await login(values.email, values.password);
      const personnel = user.roles.includes('ADMIN') || user.roles.includes('STAFF');
      navigate(params.get('next') ? next : personnel ? '/admin' : '/account', { replace: true });
    } catch (e) {
      setError(e instanceof ApiError ? e.message : 'Sign-in failed. Please try again.');
    }
  };

  return (
    <AuthCard title="Welcome back" subtitle="Sign in to manage your stays.">
      <form onSubmit={handleSubmit(onSubmit)} className="space-y-5" noValidate>
        {error && <Alert tone="error">{error}</Alert>}
        <TextField label="E-mail address" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
        <TextField label="Password" type="password" autoComplete="current-password" error={errors.password?.message} {...register('password')} />
        <Button type="submit" size="lg" className="w-full" loading={isSubmitting}>
          Sign in
        </Button>
        <p className="text-center text-sm text-ink-600">
          New to GrandVista?{' '}
          <Link to={`/register${params.get('next') ? `?next=${encodeURIComponent(next)}` : ''}`} className="font-semibold text-gold-700 underline-offset-4 hover:underline">
            Create an account
          </Link>
        </p>
      </form>
    </AuthCard>
  );
}
