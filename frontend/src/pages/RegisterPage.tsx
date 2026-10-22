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
import { registerSchema, type RegisterForm } from '../lib/schemas';
import { AuthCard } from './AuthCard';

export default function RegisterPage() {
  const { register: registerAccount, status } = useAuth();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const next = safeRedirect(params.get('next'), '/account');
  const [error, setError] = useState<string | null>(null);
  const {
    register,
    handleSubmit,
    setError: setFieldError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterForm>({ resolver: zodResolver(registerSchema) });

  if (status === 'authenticated') return <Navigate to={next} replace />;

  const onSubmit = async (values: RegisterForm) => {
    setError(null);
    try {
      await registerAccount({ email: values.email, password: values.password, fullName: values.fullName, phone: values.phone || undefined });
      navigate(next, { replace: true });
    } catch (e) {
      if (e instanceof ApiError) {
        e.fieldErrors.forEach((f) => {
          if (f.field in values) setFieldError(f.field as keyof RegisterForm, { message: f.message });
        });
        setError(e.code === 'EMAIL_ALREADY_REGISTERED' ? 'An account with this e-mail already exists. Try signing in.' : e.message);
      } else {
        setError('Registration failed. Please try again.');
      }
    }
  };

  return (
    <AuthCard title="Create your account" subtitle="Book faster and keep all your reservations in one place.">
      <form onSubmit={handleSubmit(onSubmit)} className="space-y-5" noValidate>
        {error && <Alert tone="error">{error}</Alert>}
        <TextField label="Full name" autoComplete="name" error={errors.fullName?.message} {...register('fullName')} />
        <TextField label="E-mail address" type="email" autoComplete="email" error={errors.email?.message} {...register('email')} />
        <TextField label="Phone (optional)" type="tel" autoComplete="tel" error={errors.phone?.message} {...register('phone')} />
        <TextField
          label="Password"
          type="password"
          autoComplete="new-password"
          hint="At least 10 characters, including a letter and a digit."
          error={errors.password?.message}
          {...register('password')}
        />
        <TextField label="Confirm password" type="password" autoComplete="new-password" error={errors.confirmPassword?.message} {...register('confirmPassword')} />
        <Button type="submit" size="lg" className="w-full" loading={isSubmitting}>
          Create account
        </Button>
        <p className="text-center text-sm text-ink-600">
          Already registered?{' '}
          <Link to={`/login${params.get('next') ? `?next=${encodeURIComponent(next)}` : ''}`} className="font-semibold text-gold-700 underline-offset-4 hover:underline">
            Sign in
          </Link>
        </p>
      </form>
    </AuthCard>
  );
}
