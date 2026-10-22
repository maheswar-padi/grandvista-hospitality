import { addDays, format, parseISO } from 'date-fns';

/** The hotel's time zone. Stay dates are calendar dates in this zone, whatever the browser's zone is. */
export const HOTEL_TIME_ZONE = 'Asia/Kolkata';

/** Today's date in the hotel's time zone as yyyy-MM-dd. */
export function hotelToday(now: Date = new Date()) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: HOTEL_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(now);
}

export function plusDays(isoDate: string, days: number) {
  return format(addDays(parseISO(isoDate), days), 'yyyy-MM-dd');
}

export function defaultStay() {
  const checkIn = plusDays(hotelToday(), 14);
  return { checkIn, checkOut: plusDays(checkIn, 2) };
}
