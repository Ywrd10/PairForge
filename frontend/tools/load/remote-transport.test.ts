import { afterEach, expect, it, vi } from 'vitest'
import { approvedRemote, request, configureRemoteTarget } from './transport.ts'
afterEach(()=>vi.unstubAllGlobals())
it('sends authenticated requests only to the explicit HTTPS origin and rejects redirects',async()=>{
  const send=vi.fn().mockResolvedValue(Response.json({id:'verified'}));vi.stubGlobal('fetch',send)
  configureRemoteTarget(approvedRemote)
  await request('/auth/me','private-token',undefined,new AbortController().signal,1000)
  expect(send).toHaveBeenCalledWith(`${approvedRemote}/api/auth/me`,expect.objectContaining({redirect:'error',method:'GET',headers:expect.objectContaining({Origin:approvedRemote,Authorization:'Bearer private-token'})}))
})
