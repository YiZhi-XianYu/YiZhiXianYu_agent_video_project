import { get, post } from '@/api/client'
import type { AuthUser } from '@/api/types'

export function getCurrentUser(): Promise<AuthUser> {
  return get<AuthUser>('/api/v1/auth/me')
}

export function login(email: string, password: string): Promise<AuthUser> {
  return post<AuthUser>('/api/v1/auth/login', { email, password })
}

export function register(email: string, displayName: string, password: string): Promise<AuthUser> {
  return post<AuthUser>('/api/v1/auth/register', { email, displayName, password })
}

export function logout(): Promise<void> {
  return post<void>('/api/v1/auth/logout')
}
