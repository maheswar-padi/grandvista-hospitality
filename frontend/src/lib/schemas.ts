import { z } from 'zod';
import { hotelToday } from './dates';

// Client-side validation mirrors the server's rules for fast feedback; the server re-validates everything.

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Choose a date');

export const staySchema = z
  .object({
    checkIn: isoDate,
    checkOut: isoDate,
    guests: z.coerce.number().int().min(1, 'At least 1 guest').max(12, 'At most 12 guests'),
  })
  .refine((v) => v.checkIn >= hotelToday(), { path: ['checkIn'], message: 'Check-in cannot be in the past' })
  .refine((v) => v.checkOut > v.checkIn, { path: ['checkOut'], message: 'Check-out must be after check-in' });

export type StayForm = z.infer<typeof staySchema>;

export const loginSchema = z.object({
  email: z.string().trim().min(1, 'Enter your e-mail address').email('Enter a valid e-mail address'),
  password: z.string().min(1, 'Enter your password').max(128),
});

export type LoginForm = z.infer<typeof loginSchema>;

export const registerSchema = z
  .object({
    fullName: z.string().trim().min(2, 'Enter your full name').max(120),
    email: z.string().trim().min(1, 'Enter your e-mail address').email('Enter a valid e-mail address').max(254),
    phone: z
      .string()
      .trim()
      .regex(/^(\+?[0-9 ()-]{6,32})?$/, 'Enter a valid phone number')
      .optional(),
    password: z
      .string()
      .min(10, 'Use at least 10 characters')
      .max(128)
      .regex(/[A-Za-z]/, 'Include at least one letter')
      .regex(/\d/, 'Include at least one digit'),
    confirmPassword: z.string(),
  })
  .refine((v) => v.password === v.confirmPassword, { path: ['confirmPassword'], message: 'Passwords do not match' });

export type RegisterForm = z.infer<typeof registerSchema>;

export const guestSchema = z.object({
  fullName: z.string().trim().min(2, 'Enter the guest’s full name').max(120),
  email: z.string().trim().email('Enter a valid e-mail address').max(254),
  phone: z
    .string()
    .trim()
    .regex(/^(\+?[0-9 ()-]{6,32})?$/, 'Enter a valid phone number')
    .optional(),
  specialRequests: z.string().max(500, 'Keep requests under 500 characters').optional(),
});

export type GuestForm = z.infer<typeof guestSchema>;
