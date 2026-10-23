import { createContext, useContext } from 'react';
import type { Property } from './api/types';

/** The hotel created by the first migration; pricing and new rooms default to it when "All hotels" is selected. */
export const DEFAULT_PROPERTY_ID = 'd1000000-0000-4000-8000-000000000001';

export interface AdminPropertyState {
  /** undefined = all hotels (portfolio view) */
  propertyId: string | undefined;
  setPropertyId: (id: string | undefined) => void;
  properties: Property[];
  /** The selected hotel, or the default hotel for screens that always work on one hotel (pricing, new rooms). */
  effective: Property | undefined;
}

export const AdminPropertyContext = createContext<AdminPropertyState>({
  propertyId: undefined,
  setPropertyId: () => {},
  properties: [],
  effective: undefined,
});

export function useAdminProperty() {
  return useContext(AdminPropertyContext);
}

export function effectiveProperty(properties: Property[], propertyId: string | undefined) {
  return (
    properties.find((p) => p.id === propertyId) ??
    properties.find((p) => p.id === DEFAULT_PROPERTY_ID) ??
    properties[0]
  );
}

export const PROPERTY_STORAGE_KEY = 'gv.admin.propertyId';

export function readStoredProperty(): string | undefined {
  try {
    return window.localStorage.getItem(PROPERTY_STORAGE_KEY) || undefined;
  } catch {
    return undefined;
  }
}

export function storeProperty(id: string | undefined) {
  try {
    if (id) window.localStorage.setItem(PROPERTY_STORAGE_KEY, id);
    else window.localStorage.removeItem(PROPERTY_STORAGE_KEY);
  } catch {
    // storage unavailable (private mode): the choice simply is not remembered
  }
}
