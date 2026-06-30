import { describe, expect, it } from 'vitest'
import { ApiError } from '@/api/http/client'
import { email, mapApiFieldErrors, positiveNumber, required } from '@/lib/formValidation'

describe('form validation helpers', () => {
  it('validates common form primitives', () => {
    expect(required('')).toBe('This field is required.')
    expect(required('Acme')).toBeUndefined()
    expect(email('not-an-email')).toBe('Enter a valid email address.')
    expect(email('person@example.com')).toBeUndefined()
    expect(positiveNumber(0)).toBe('Enter a number greater than zero.')
    expect(positiveNumber(20)).toBeUndefined()
  })

  it('maps JSON:API error pointers into field errors', () => {
    const error = new ApiError('Validation failed', 422, {
      errors: [
        { detail: 'Email is already in use.', source: { pointer: '/data/attributes/email' } },
        { detail: 'Name is required.', source: { parameter: 'name' } },
      ],
    })

    expect(mapApiFieldErrors(error)).toEqual({ email: 'Email is already in use.', name: 'Name is required.' })
  })
})
